package io.hstore.db;

import io.hstore.db.property.PropertyBag.Property;
import io.hstore.db.property.PropertyBag;
import io.hstore.db.property.PropertyIndex;
import io.hstore.db.property.PropertySlots;
import io.hstore.db.schema.Schema;
import io.hstore.db.schema.TypeDef;
import io.hstore.db.security.Principal;
import io.hstore.db.semantic.SemanticPlane;
import io.hstore.db.value.Json;
import io.hstore.db.value.TypeTag;
import io.hstore.db.value.Value;
import io.hstore.db.value.Values;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Hyperedge;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.Weight;
import io.hstore.engine.tree.Entry;
import io.hstore.engine.txn.View;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public sealed class Reader permits Writer {

    final HypergraphDatabase database;
    private final View view;
    private final Principal principal;

    Reader(HypergraphDatabase database, View view, Principal principal) {
        this.database = database;
        this.view = view;
        this.principal = principal;
    }

    public Principal principal() {
        return principal;
    }

    public int tenant() {
        return principal.tenant();
    }

    public boolean visible(long atom) {
        return view.atom(atom).map(record -> record.tenant() == tenant()).orElse(false);
    }

    public View view() {
        return view;
    }

    public HypergraphDatabase database() {
        return database;
    }

    public Schema schema() {
        return database.schema();
    }

    public long generation() {
        return view.generation();
    }

    public Optional<Atom> atom(long id) {
        return view.atom(id)
                .filter(record -> record.tenant() == tenant())
                .map(record -> new Atom(id, schema().require(view, record.type()), record));
    }

    public Atom require(long id) {
        return atom(id).orElseThrow(() -> HStoreException.invalid("atom " + id + " does not exist"));
    }

    public Optional<TypeDef> type(String name) {
        return schema().type(view, name);
    }

    public TypeDef requireType(String name) {
        return schema().require(view, name);
    }

    public OptionalLong find(String type, String key) {
        return type(type).map(found -> view.resolve(tenant(), found.id(), key)).orElse(OptionalLong.empty());
    }

    public long resolve(String type, String key) {
        return find(type, key).orElseThrow(() -> HStoreException.invalid("no " + type + " named '" + key + "'"));
    }

    public LongStream atoms(String type) {
        return atomsOfType(requireType(type).id());
    }

    public LongStream atomsOfType(int typeId) {
        return view.atomsOfType(tenant(), typeId);
    }

    public long count(String type) {
        return countOfType(requireType(type).id());
    }

    public long countOfType(int typeId) {
        return view.countOfType(tenant(), typeId);
    }

    public Stream<Atom> allAtoms(boolean edges) {
        return view.atoms()
                .filter(entry -> entry.value().tenant() == tenant() && edges == (entry.value() instanceof AtomRecord.EdgeRecord))
                .map(entry -> new Atom(entry.key(), schema().require(view, entry.value().type()), entry.value()));
    }

    public Optional<Value> property(long atom, String name) {
        OptionalInt key = schema().existingPropertyKey(name);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return bag(atom).get(key.getAsInt()).map(Property::value);
    }

    public Optional<Value> property(long atom, String name, long validAt) {
        OptionalInt key = schema().existingPropertyKey(name);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return bag(atom).get(key.getAsInt()).filter(property -> property.validAt(validAt)).map(Property::value);
    }

    public Map<String, Value> properties(long atom) {
        Map<String, Value> named = new LinkedHashMap<>();
        bag(atom).entries().forEach((key, property) -> named.put(schema().propertyName(key), property.value()));
        named.remove(Schema.DOCUMENT);
        return named;
    }

    PropertyBag bag(long atom) {
        return visible(atom) ? view.get(PropertySlots.PROPERTIES, atom).orElse(PropertyBag.EMPTY) : PropertyBag.EMPTY;
    }

    public String text(Value value) {
        return switch (value) {
            case Value.Payload(long ref, long _, String _) -> database.payloads().readText(ref);
            default -> Values.plain(value);
        };
    }

    public Optional<Json> document(long atom) {
        return property(atom, Schema.DOCUMENT)
                .filter(value -> value instanceof Value.Payload)
                .map(value -> Json.parse(text(value)));
    }

    public Hyperedge edge(long id) {
        AtomRecord record = view.atom(id).filter(found -> found.tenant() == tenant())
                .orElseThrow(() -> HStoreException.invalid("atom " + id + " does not exist"));
        if (!(record instanceof AtomRecord.EdgeRecord edge)) {
            throw HStoreException.invalid("atom " + id + " is not a hyperedge");
        }
        return view.edge(id, edge);
    }

    public long cardinality(long edge) {
        return visible(edge) ? view.cardinality(edge) : 0;
    }

    public long degree(long atom) {
        return visible(atom) ? view.degree(atom) : 0;
    }

    public Stream<Member> members(long edge) {
        Hyperedge hyperedge = edge(edge);
        long[] position = {0};
        return hyperedge.stream().map(incidence -> member(incidence, hyperedge.kind() == EdgeKind.ORDERED ? position[0]++ : -1));
    }

    public LongStream memberIds(long edge) {
        return edge(edge).memberIds();
    }

    public Stream<Member> members(long edge, long validAt) {
        return edge(edge).validAt(validAt).map(incidence -> member(incidence, -1));
    }

    public Stream<Member> membersWithRole(long edge, String role) {
        return edge(edge).withRoleSets(Set.copyOf(database.engine().dictionary().roleSetsContaining(role)))
                .map(incidence -> member(incidence, -1));
    }

    public Stream<Member> membersWeighted(long edge, double low, double high) {
        return edge(edge).weightedBetween(Weight.of(low), Weight.of(high)).map(incidence -> member(incidence, -1));
    }

    Member member(Incidence incidence, long position) {
        return new Member(incidence.member(), roles(incidence.roleSet()), incidence.weightValue(), incidence.validity(),
                position, incidence.dataRef(), incidence.qualifier());
    }

    public List<String> roles(int roleSet) {
        return database.engine().dictionary().roles(roleSet);
    }

    public Stream<Incident> incident(long atom) {
        if (!visible(atom)) {
            return Stream.empty();
        }
        return view.incident(atom).map(incident -> new Incident(incident.edge(), roles(incident.roleSet()), incident.locator()));
    }

    public long position(Incident incident) {
        return view.atom(incident.edge())
                .filter(record -> record instanceof AtomRecord.EdgeRecord edge && edge.kind() == EdgeKind.ORDERED)
                .map(_ -> edge(incident.edge()).positionOfLocator(incident.locator()))
                .orElse(-1L);
    }

    public LongStream lookup(String type, String property, Value value) {
        return range(type, property, Optional.of(value), Optional.of(value), true, true);
    }

    public LongStream range(String type, String property, Optional<Value> low, Optional<Value> high, boolean lowInclusive, boolean highInclusive) {
        TypeDef definition = requireType(type);
        Predicate<Value> accept = candidate -> low.map(bound -> lowInclusive ? candidate.compareTo(bound) >= 0 : candidate.compareTo(bound) > 0).orElse(true)
                && high.map(bound -> highInclusive ? candidate.compareTo(bound) <= 0 : candidate.compareTo(bound) < 0).orElse(true);
        OptionalInt key = schema().existingPropertyKey(property);
        if (key.isEmpty()) {
            return LongStream.empty();
        }
        Optional<TypeTag> indexedTag = indexedTag(definition, property);
        if (indexedTag.isPresent()) {
            TypeTag tag = indexedTag.get();
            return PropertyIndex.candidates(view.scan(PropertySlots.PROPERTY_INDEX), tenant(), key.getAsInt(), tag,
                            PropertyIndex.lowerKey(tag, low), PropertyIndex.upperKey(tag, high))
                    .filter(entry -> accept.test(entry.value()))
                    .mapToLong(Entry::key)
                    .filter(owner -> view.atom(owner).map(record -> record.type() == definition.id() && record.tenant() == tenant()).orElse(false))
                    .sorted()
                    .distinct();
        }
        if (property.startsWith("$")) {
            return atomsOfType(definition.id())
                    .filter(atom -> document(atom).stream().flatMap(json -> json.select(property)).anyMatch(accept));
        }
        return atomsOfType(definition.id())
                .filter(atom -> bag(atom).get(key.getAsInt()).map(Property::value).filter(accept).isPresent());
    }

    public static Optional<TypeTag> indexedTag(TypeDef type, String property) {
        return type.property(property)
                .filter(TypeDef.PropertyDef::indexed)
                .map(TypeDef.PropertyDef::type)
                .or(() -> type.jsonIndexes().stream().filter(index -> index.path().equals(property)).map(TypeDef.JsonIndex::type).findFirst());
    }

    public List<SemanticPlane.Hit> similar(String text, int k, SemanticPlane.Consistency consistency) {
        SemanticPlane semantic = database.semantic();
        return semantic.nearest(view, semantic.model(semantic.encoder().model()), semantic.encoder().encode(text).vector(), k, consistency, this::visible);
    }

    public List<SemanticPlane.Hit> similar(String model, float[] vector, int k, SemanticPlane.Consistency consistency) {
        SemanticPlane semantic = database.semantic();
        return semantic.nearest(view, semantic.model(model), vector, k, consistency, this::visible);
    }

    public Optional<float[]> embedding(long atom) {
        return visible(atom) ? database.semantic().vector(view, atom) : Optional.empty();
    }
}
