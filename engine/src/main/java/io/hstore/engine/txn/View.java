package io.hstore.engine.txn;

import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.AtomRecord.EdgeRecord;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.catalog.Symbol;
import io.hstore.engine.topology.Hyperedge;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.tree.Entry;
import io.hstore.engine.tree.NodeSource;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.Tree;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public abstract sealed class View permits Snapshot, Transaction {

    abstract <V> Tree<V> tree(Slot<V> slot);

    void exposeLazily() {
    }

    private <V> Tree<V> lazyTree(Slot<V> slot) {
        exposeLazily();
        return tree(slot);
    }

    void observeAtom(long id, Optional<AtomRecord> record) {
    }

    <V> void observeKey(Slot<V> slot, long key, Optional<V> value) {
    }

    public abstract long generation();

    public abstract int branch();

    public abstract NodeSource source();

    public Optional<AtomRecord> atom(long id) {
        Optional<AtomRecord> record = tree(EngineSlots.CATALOG).get(id);
        observeAtom(id, record);
        return record;
    }

    public boolean exists(long id) {
        return atom(id).isPresent();
    }

    public Optional<Hyperedge> edge(long id) {
        return atom(id).flatMap(record -> record instanceof EdgeRecord edge
                ? Optional.of(hyperedge(id, edge, true))
                : Optional.empty());
    }

    public Hyperedge requireEdge(long id) {
        return switch (atom(id).orElseThrow(() -> HStoreException.invalid("atom " + id + " does not exist"))) {
            case EdgeRecord edge -> hyperedge(id, edge, true);
            case AtomRecord.NodeRecord _ -> throw HStoreException.invalid("atom " + id + " is not a hyperedge");
        };
    }

    public Hyperedge edge(long id, EdgeRecord record) {
        return hyperedge(id, record, true);
    }

    public boolean contains(long edge, long member) {
        return incidence(edge, member).isPresent();
    }

    public Optional<Incidence> incidence(long edge, long member) {
        return atom(edge).flatMap(record -> record instanceof EdgeRecord found
                ? hyperedge(edge, found, false).get(member)
                : Optional.empty());
    }

    public long cardinality(long edge) {
        return atom(edge).map(record -> record instanceof EdgeRecord found ? found.cardinality() : 0L).orElse(0L);
    }

    public Stream<IncidentEdge> incident(long atom) {
        return Views.incident(lazyTree(EngineSlots.REVERSE), atom);
    }

    public Tree<?> incidentTree(long atom) {
        return EngineSlots.REVERSE_INDEX.tree(lazyTree(EngineSlots.REVERSE), atom);
    }

    public long degree(long atom) {
        return EngineSlots.REVERSE_INDEX.count(tree(EngineSlots.REVERSE), atom);
    }

    public OptionalLong resolve(int tenant, int type, String canonicalKey) {
        return Views.resolve(tree(EngineSlots.CANONICAL), tenant, type, canonicalKey);
    }

    public LongStream atomsOfType(int tenant, int type) {
        return EngineSlots.TYPE_INDEX.stream(lazyTree(EngineSlots.TYPES), EngineSlots.typeKey(tenant, type)).mapToLong(Entry::key);
    }

    public Tree<?> typeTree(int tenant, int type) {
        return EngineSlots.TYPE_INDEX.tree(lazyTree(EngineSlots.TYPES), EngineSlots.typeKey(tenant, type));
    }

    public long countOfType(int tenant, int type) {
        return EngineSlots.TYPE_INDEX.count(tree(EngineSlots.TYPES), EngineSlots.typeKey(tenant, type));
    }

    public Stream<Entry<AtomRecord>> atoms() {
        return lazyTree(EngineSlots.CATALOG).stream();
    }

    public Optional<Symbol> symbol(int id) {
        return tree(EngineSlots.SYMBOLS).get(id);
    }

    public <V> Optional<V> get(Slot<V> slot, long key) {
        Optional<V> value = tree(slot).get(key);
        observeKey(slot, key, value);
        return value;
    }

    public <V> Tree<V> scan(Slot<V> slot) {
        return lazyTree(slot);
    }

    private Hyperedge hyperedge(long id, EdgeRecord record, boolean lazy) {
        if (lazy) {
            exposeLazily();
        }
        return Hyperedge.of(id, record.kind(), source(), record.members(), record.order());
    }

    static Ref membersOf(Optional<AtomRecord> record) {
        return record.map(value -> value instanceof EdgeRecord edge ? edge.members() : Ref.EMPTY).orElse(Ref.EMPTY);
    }
}
