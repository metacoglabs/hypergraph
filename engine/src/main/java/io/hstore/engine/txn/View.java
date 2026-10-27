// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

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
import java.util.function.LongConsumer;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public abstract sealed class View permits Snapshot, Transaction {

    public interface Unrecorded extends AutoCloseable {
        @Override
        void close();
    }

    static final Unrecorded NOT_RECORDING = () -> {
    };

    abstract <V> Tree<V> tree(Slot<V> slot);

    void exposeLazily() {
    }

    private <V> Tree<V> lazyTree(Slot<V> slot) {
        exposeLazily();
        return tree(slot);
    }

    void observeAtom(long id, Optional<AtomRecord> record) {
    }

    void observeKey(Slot<?> slot, long key) {
    }

    void observeRange(Slot<?> slot, long key, long low, long high) {
    }

    void observeSlot(Slot<?> slot) {
    }

    public Unrecorded unrecorded() {
        return NOT_RECORDING;
    }

    public boolean recordsReads() {
        return false;
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
        observeKey(EngineSlots.REVERSE, atom);
        return Views.incident(lazyTree(EngineSlots.REVERSE), atom);
    }

    public void forEachIncidentEdge(long atom, LongConsumer action) {
        observeKey(EngineSlots.REVERSE, atom);
        EngineSlots.REVERSE_INDEX.forEachPosting(lazyTree(EngineSlots.REVERSE), atom, action);
    }

    public Tree<?> incidentTree(long atom) {
        observeKey(EngineSlots.REVERSE, atom);
        return EngineSlots.REVERSE_INDEX.tree(lazyTree(EngineSlots.REVERSE), atom);
    }

    public long degree(long atom) {
        observeKey(EngineSlots.REVERSE, atom);
        return EngineSlots.REVERSE_INDEX.count(tree(EngineSlots.REVERSE), atom);
    }

    public OptionalLong resolve(int tenant, int type, String canonicalKey) {
        long hash = EngineSlots.canonicalKey(tenant, type, canonicalKey);
        observeKey(EngineSlots.CANONICAL, hash);
        return Views.resolve(tree(EngineSlots.CANONICAL), tenant, type, canonicalKey, hash);
    }

    public LongStream atomsOfType(int tenant, int type) {
        long key = EngineSlots.typeKey(tenant, type);
        observeKey(EngineSlots.TYPES, key);
        return EngineSlots.TYPE_INDEX.stream(lazyTree(EngineSlots.TYPES), key).mapToLong(Entry::key);
    }

    public Tree<?> typeTree(int tenant, int type) {
        long key = EngineSlots.typeKey(tenant, type);
        observeKey(EngineSlots.TYPES, key);
        return EngineSlots.TYPE_INDEX.tree(lazyTree(EngineSlots.TYPES), key);
    }

    public long countOfType(int tenant, int type) {
        long key = EngineSlots.typeKey(tenant, type);
        observeKey(EngineSlots.TYPES, key);
        return EngineSlots.TYPE_INDEX.count(tree(EngineSlots.TYPES), key);
    }

    public Stream<Entry<AtomRecord>> atoms() {
        observeSlot(EngineSlots.CATALOG);
        return lazyTree(EngineSlots.CATALOG).stream();
    }

    public Optional<Symbol> symbol(int id) {
        return tree(EngineSlots.SYMBOLS).get(id);
    }

    public <V> Optional<V> get(Slot<V> slot, long key) {
        observeKey(slot, key);
        return tree(slot).get(key);
    }

    public <V> Optional<V> nested(Slot<V> slot, long key, long low, long high) {
        observeRange(slot, key, low, high);
        exposeLazily();
        return tree(slot).get(key);
    }

    public <V> Tree<V> scan(Slot<V> slot) {
        observeSlot(slot);
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
