package io.hstore.db;

import io.hstore.db.property.PropertyBag.Property;
import io.hstore.db.property.PropertyBag;
import io.hstore.db.property.PropertyIndex;
import io.hstore.db.property.PropertySlots;
import io.hstore.db.schema.AtomKind;
import io.hstore.db.schema.Schema;
import io.hstore.db.schema.TypeDef.JsonIndex;
import io.hstore.db.schema.TypeDef.PropertyDef;
import io.hstore.db.schema.TypeDef;
import io.hstore.db.security.Principal;
import io.hstore.db.security.Quota.Usage;
import io.hstore.db.security.Security;
import io.hstore.db.semantic.Embedding;
import io.hstore.db.semantic.Encoder;
import io.hstore.db.value.Json;
import io.hstore.db.value.TypeTag;
import io.hstore.db.value.Value;
import io.hstore.db.value.Values;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.Validity;
import io.hstore.engine.topology.Weight;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.txn.Transaction;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

public final class Writer extends Reader {

    private static final int INLINE_TEXT_LIMIT = 1024;

    private final Transaction txn;

    Writer(HypergraphDatabase database, Transaction txn, Principal principal) {
        super(database, txn, principal);
        this.txn = txn;
    }

    public Transaction transaction() {
        return txn;
    }

    public TypeDef defineNode(String name, List<PropertyDef> properties) {
        principal().requireAdmin();
        return schema().define(txn, name, AtomKind.NODE, properties, List.of());
    }

    public TypeDef defineEdge(String name, AtomKind kind, List<PropertyDef> properties, List<String> roles) {
        principal().requireAdmin();
        if (!kind.isEdge()) {
            throw HStoreException.invalid("edge types must be SET_EDGE or ORDERED_EDGE");
        }
        return schema().define(txn, name, kind, properties, roles);
    }

    public TypeDef createIndex(String type, String property) {
        principal().requireAdmin();
        TypeDef current = requireType(type);
        PropertyDef existing = current.property(property)
                .orElseThrow(() -> HStoreException.invalid(type + " has no declared property '" + property + "'"));
        TypeDef updated = schema().alter(txn, current.withProperty(new PropertyDef(property, existing.type(), true, existing.required())));
        int key = schema().propertyKey(property);
        List<Integer> tenants = Security.tenants(view()).stream().map(Security.Tenant::id).toList();
        txn.apply("backfill " + type + "." + property, workspace -> tenants.forEach(tenant ->
                EngineSlots.TYPE_INDEX.stream(workspace.tree(EngineSlots.TYPES), EngineSlots.typeKey(tenant, updated.id())).toList()
                        .forEach(entry -> workspace.get(PropertySlots.PROPERTIES, entry.key())
                                .flatMap(bag -> bag.get(key))
                                .ifPresent(found -> PropertyIndex.add(workspace, tenant, key, found.value(), entry.key())))));
        return updated;
    }

    public TypeDef createJsonIndex(String type, String path, TypeTag tag) {
        principal().requireAdmin();
        TypeDef current = requireType(type);
        TypeDef updated = schema().alter(txn, current.withJsonIndex(new JsonIndex(path, tag)));
        int key = schema().propertyKey(path);
        for (Security.Tenant tenant : Security.tenants(view())) {
            Reader scoped = new Reader(database, view(), principal().inTenant(tenant.id()));
            for (long owner : scoped.atoms(type).boxed().toList()) {
                scoped.document(owner).ifPresent(json -> json.select(path)
                        .filter(value -> !(value instanceof Value.Null))
                        .forEach(value -> {
                            Value coerced = Values.coerce(value, tag);
                            txn.apply("backfill " + path, workspace -> PropertyIndex.add(workspace, tenant.id(), key, coerced, owner));
                        }));
            }
        }
        return updated;
    }

    public long node(String type, String key) {
        return node(type, key, Map.of());
    }

    public long node(String type, String key, Map<String, ?> properties) {
        principal().requireWrite();
        TypeDef definition = kindOf(type, AtomKind.NODE);
        Security.charge(txn, tenant(), new Usage(1, 0, 0));
        long id = txn.createNode(new AtomRecord.NodeRecord(definition.id(), key, 0, 0, 0, tenant(), 0));
        assign(id, definition, properties);
        return id;
    }

    public long edge(String type) {
        return edge(type, Map.of());
    }

    public long edge(String type, Map<String, ?> properties) {
        TypeDef definition = requireType(type);
        AtomKind kind = definition.kind();
        if (!kind.isEdge()) {
            throw HStoreException.invalid(type + " is a node type");
        }
        principal().requireWrite();
        Security.charge(txn, tenant(), new Usage(1, 1, 0));
        long id = txn.createEdge(new AtomRecord.EdgeRecord(definition.id(), kind.edgeKind().orElseThrow(), Ref.EMPTY, Ref.EMPTY, 0, 0, tenant(), 0));
        assign(id, definition, properties);
        return id;
    }

    private TypeDef kindOf(String type, AtomKind kind) {
        TypeDef definition = requireType(type);
        if (definition.kind() != kind) {
            throw HStoreException.invalid(type + " is a " + definition.kind() + " type");
        }
        return definition;
    }

    private void assign(long atom, TypeDef definition, Map<String, ?> properties) {
        definition.properties().stream()
                .filter(PropertyDef::required)
                .filter(property -> !properties.containsKey(property.name()))
                .findFirst()
                .ifPresent(missing -> {
                    throw HStoreException.invalid(definition.name() + " requires property '" + missing.name() + "'");
                });
        properties.forEach((name, value) -> set(atom, name, Value.of(value)));
    }

    public void add(long edge, long member, String... roles) {
        add(edge, member, MemberSpec.roles(roles));
    }

    public void add(long edge, long member, MemberSpec spec) {
        Incidence incidence = incidence(edge, member, spec);
        txn.upsert(edge, member, _ -> incidence);
    }

    public void insertAt(long edge, long index, long member, MemberSpec spec) {
        txn.insertAt(edge, index, incidence(edge, member, spec));
    }

    public void append(long edge, long member, MemberSpec spec) {
        txn.append(edge, incidence(edge, member, spec));
    }

    public void remove(long edge, long member) {
        principal().requireWrite();
        require(edge);
        txn.remove(edge, member);
    }

    public void removeAt(long edge, long index) {
        principal().requireWrite();
        require(edge);
        txn.removeAt(edge, index);
    }

    public void load(long edge, List<Long> members, MemberSpec spec) {
        txn.load(edge, members.stream().map(member -> incidence(edge, member, spec)).toList());
    }

    public void qualify(long edge, long member, long qualifier) {
        principal().requireWrite();
        require(edge);
        txn.upsert(edge, member, incidence -> incidence.withQualifier(qualifier));
    }

    private Incidence incidence(long edge, long member, MemberSpec spec) {
        principal().requireWrite();
        Atom container = require(edge);
        if (!container.isEdge()) {
            throw HStoreException.invalid("atom " + edge + " is not a hyperedge");
        }
        spec.roles().stream().filter(role -> !container.type().allowsRole(role)).findFirst().ifPresent(role -> {
            throw HStoreException.invalid(container.type().name() + " does not declare role '" + role + "'");
        });
        if (!visible(member)) {
            throw HStoreException.invalid("member atom " + member + " does not exist");
        }
        int roleSet = database.engine().dictionary().roleSet(spec.roles());
        return new Incidence(member, roleSet, spec.weight().stream().mapToLong(Weight::of).findFirst().orElse(Weight.ONE),
                spec.validity().from(), spec.validity().to(), spec.dataRef(), spec.qualifier());
    }

    public void set(long atom, String name, Value value) {
        set(atom, name, value, Validity.ALWAYS);
    }

    public void set(long atom, String name, Value value, Validity validity) {
        principal().requireWrite();
        Atom target = require(atom);
        Value stored = target.type().property(name).map(def -> Values.coerce(value, def.type())).orElse(value);
        stored = externalizeLargeText(stored);
        Property property = new Property(stored, validity.from(), validity.to());
        updateBag(atom, schema().propertyKey(name), bag -> bag.with(schema().propertyKey(name), property));
    }

    public void unset(long atom, String name) {
        principal().requireWrite();
        require(atom);
        schema().existingPropertyKey(name).ifPresent(key -> updateBag(atom, key, bag -> bag.without(key)));
    }

    private Value externalizeLargeText(Value value) {
        if (value instanceof Value.Text(String text) && text.length() > INLINE_TEXT_LIMIT) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            return new Value.Payload(writePayload(bytes), bytes.length, "text/plain");
        }
        return value;
    }

    private void updateBag(long atom, int key, UnaryOperator<PropertyBag> change) {
        Optional<Property> expected = bag(atom).get(key);
        txn.merge(PropertySlots.PROPERTIES, atom,
                current -> current.orElse(PropertyBag.EMPTY).get(key).equals(expected),
                current -> {
                    PropertyBag next = change.apply(current.orElse(PropertyBag.EMPTY));
                    return next.isEmpty() ? Optional.empty() : Optional.of(next);
                });
    }

    long writePayload(byte[] bytes) {
        Security.charge(txn, tenant(), new Usage(0, 0, bytes.length));
        return database.payloads().write(bytes);
    }

    public void document(long atom, Json json) {
        principal().requireWrite();
        require(atom);
        byte[] bytes = json.print().getBytes(StandardCharsets.UTF_8);
        long ref = writePayload(bytes);
        set(atom, Schema.DOCUMENT, new Value.Payload(ref, bytes.length, "application/json"));
    }

    public void embed(long atom, String text) {
        Encoder.Encoded encoded = database.semantic().encoder().encode(text);
        embed(atom, encoded.model(), encoded.version(), encoded.vector());
    }

    public void embed(long atom, String model, int version, float[] vector) {
        principal().requireWrite();
        require(atom);
        long ref = writePayload(Embedding.encode(vector));
        Embedding embedding = new Embedding(database.semantic().model(model), version, vector.length, ref);
        txn.put(Embedding.EMBEDDINGS, atom, embedding);
        txn.updateAtom(atom, record -> record instanceof AtomRecord.NodeRecord node ? node.withEmbeddingRef(ref) : record);
    }

    public void delete(long atom) {
        principal().requireWrite();
        Atom target = require(atom);
        Security.charge(txn, tenant(), new Usage(-1, target.isEdge() ? -1 : 0, 0));
        txn.delete(PropertySlots.PROPERTIES, atom);
        txn.delete(Embedding.EMBEDDINGS, atom);
        txn.delete(atom);
    }
}
