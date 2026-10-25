// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.txn;

import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.AtomRecord.EdgeRecord;
import io.hstore.engine.catalog.AtomRecord.NodeRecord;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.catalog.Generation;
import io.hstore.engine.catalog.RootVector;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Hyperedge;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.TopologySchemas;
import io.hstore.engine.tree.NodeSource;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeDiff;
import io.hstore.engine.tree.ValueCodec;
import io.hstore.engine.tree.WriteScope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public final class Transaction extends View implements AutoCloseable {

    public enum State { ACTIVE, COMMITTED, ABORTED, FAILED }

    private record Footprint(Ref base, boolean existed, Set<Long> members, boolean commutative) {
        Footprint touch(EdgeAction action) {
            action.member().ifPresent(members::add);
            return new Footprint(base, existed, members, commutative && action.member().isPresent());
        }
    }

    private record KeyRead(Slot<?> slot, long key) {
    }

    private record RangeRead(Slot<?> slot, long key, long low, long high) {
    }

    static final int PROMOTE_AFTER = 8192;

    private final TransactionManager manager;
    private final long id;
    private final Retained retained;
    private final Generation snapshot;
    private final Branch branch;
    private final TxnOptions options;
    private final WriteScope scope;
    private final Workspace workspace;
    private final List<Op> ops = new ArrayList<>();
    private final Map<Long, Footprint> footprints = new HashMap<>();
    private final Map<Long, Optional<AtomRecord>> atomReads = new HashMap<>();
    private final Map<KeyRead, Optional<?>> keyReads = new HashMap<>();
    private final Map<RangeRead, Optional<?>> rangeReads = new HashMap<>();
    private final Map<Slot<?>, Integer> readsPerSlot = new HashMap<>();
    private final Set<Slot<?>> slotReads = new HashSet<>();
    private final boolean serializable;
    private final Unrecorded resume;
    private int unrecorded;
    private State state = State.ACTIVE;
    private long[] spilled = new long[0];
    private int spilledPages;

    Transaction(TransactionManager manager, long id, Retained retained, Generation snapshot, TxnOptions options) {
        this.manager = manager;
        this.id = id;
        this.retained = retained;
        this.snapshot = snapshot;
        this.branch = snapshot.branch(options.branch());
        this.options = options;
        this.serializable = options.isolation() == Isolation.SERIALIZABLE;
        this.resume = serializable ? () -> unrecorded = Math.max(0, unrecorded - 1) : NOT_RECORDING;
        this.scope = new WriteScope((ref, schema) -> manager.spill(this.id, ref, schema, this::spilled));
        this.workspace = manager.workspace(scope, branch.roots(), Workspace.EdgeGuard.NONE, false);
    }

    public long id() {
        return id;
    }

    public State state() {
        return state;
    }

    public TxnOptions options() {
        return options;
    }

    @Override
    public long generation() {
        return snapshot.id();
    }

    @Override
    public int branch() {
        return branch.id();
    }

    @Override
    public NodeSource source() {
        return manager.source();
    }

    @Override
    <V> Tree<V> tree(Slot<V> slot) {
        return workspace.tree(slot);
    }

    @Override
    void exposeLazily() {
        scope.freeze();
    }

    @Override
    public Unrecorded unrecorded() {
        if (serializable) {
            unrecorded++;
        }
        return resume;
    }

    private boolean recording() {
        return serializable && unrecorded == 0;
    }

    @Override
    void observeAtom(long atom, Optional<AtomRecord> record) {
        if (!recording() || slotReads.contains(EngineSlots.CATALOG) || atomReads.containsKey(atom)) {
            return;
        }
        atomReads.put(atom, snapshotValue(EngineSlots.CATALOG, atom));
        if (atomReads.size() > PROMOTE_AFTER) {
            observeSlot(EngineSlots.CATALOG);
        }
    }

    @Override
    void observeKey(Slot<?> slot, long key) {
        if (!recording() || slotReads.contains(slot)) {
            return;
        }
        KeyRead read = new KeyRead(slot, key);
        if (!keyReads.containsKey(read)) {
            keyReads.put(read, snapshotValue(slot, key));
            counted(slot);
        }
    }

    @Override
    void observeRange(Slot<?> slot, long key, long low, long high) {
        if (!recording() || slotReads.contains(slot) || keyReads.containsKey(new KeyRead(slot, key))) {
            return;
        }
        RangeRead read = new RangeRead(slot, key, low, high);
        if (!rangeReads.containsKey(read)) {
            rangeReads.put(read, snapshotValue(slot, key));
            counted(slot);
        }
    }

    @Override
    void observeSlot(Slot<?> slot) {
        if (!recording() || !slotReads.add(slot)) {
            return;
        }
        keyReads.keySet().removeIf(read -> read.slot().equals(slot));
        rangeReads.keySet().removeIf(read -> read.slot().equals(slot));
        readsPerSlot.remove(slot);
        if (slot.equals(EngineSlots.CATALOG)) {
            atomReads.clear();
        }
    }

    private void counted(Slot<?> slot) {
        if (readsPerSlot.merge(slot, 1, Integer::sum) > PROMOTE_AFTER) {
            observeSlot(slot);
        }
    }

    public long createNode(int type, String canonicalKey) {
        return create(NodeRecord.of(type, canonicalKey));
    }

    public long createNode(NodeRecord record) {
        return create(record);
    }

    public long createEdge(int type, EdgeKind kind) {
        return create(EdgeRecord.of(type, kind));
    }

    public long createEdge(EdgeRecord record) {
        return create(record);
    }

    private long create(AtomRecord record) {
        long atom = manager.allocateAtom();
        execute(new Op.CreateAtom(atom, record));
        return atom;
    }

    public void adopt(long atom, AtomRecord record) {
        execute(new Op.CreateAtom(atom, record instanceof EdgeRecord edge ? edge.withRoots(Ref.EMPTY, Ref.EMPTY) : record));
    }

    public void updateAtom(long atom, UnaryOperator<AtomRecord> change) {
        execute(new Op.UpdateAtom(atom, change));
    }

    public void delete(long atom) {
        execute(new Op.DeleteAtom(atom));
    }

    public void insert(long edge, Incidence incidence) {
        edgeAction(edge, new EdgeAction.Insert(incidence));
    }

    public void insert(long edge, long member) {
        insert(edge, Incidence.of(member));
    }

    public void upsert(long edge, long member, UnaryOperator<Incidence> patch) {
        edgeAction(edge, new EdgeAction.Upsert(member, patch));
    }

    public void replace(long edge, Incidence incidence) {
        edgeAction(edge, new EdgeAction.Replace(incidence));
    }

    public void remove(long edge, long member) {
        edgeAction(edge, new EdgeAction.Remove(member));
    }

    public void insertAt(long edge, long index, Incidence incidence) {
        edgeAction(edge, new EdgeAction.InsertAt(index, incidence));
    }

    public void append(long edge, Incidence incidence) {
        insertAt(edge, requireEdge(edge).size(), incidence);
    }

    public void removeAt(long edge, long index) {
        edgeAction(edge, new EdgeAction.RemoveAt(index));
    }

    public void updateAt(long edge, long index, UnaryOperator<Incidence> patch) {
        edgeAction(edge, new EdgeAction.UpdateAt(index, patch));
    }

    public void load(long edge, List<Incidence> incidences) {
        requireActive();
        Hyperedge current = workspace.edge(edge);
        Hyperedge built = Hyperedge.build(edge, current.kind(), source(), scope, incidences);
        edgeAction(edge, new EdgeAction.Load(built.members().root(), built.order().root()));
    }

    public void apply(String description, Consumer<Workspace> action) {
        execute(new Op.Apply(description, action));
    }

    public <V> void put(Slot<V> slot, long key, V value) {
        update(slot, key, _ -> Optional.of(value));
    }

    public <V> void delete(Slot<V> slot, long key) {
        update(slot, key, _ -> Optional.empty());
    }

    public <V> void update(Slot<V> slot, long key, UnaryOperator<Optional<V>> change) {
        Optional<V> expected = workspace.get(slot, key);
        execute(new Op.SlotWrite<>(slot, key, current -> current.equals(expected), change));
    }

    public <V> void merge(Slot<V> slot, long key, Predicate<Optional<V>> precondition, UnaryOperator<Optional<V>> change) {
        execute(new Op.SlotWrite<>(slot, key, precondition, change));
    }

    private <V> Optional<V> snapshotValue(Slot<V> slot, long key) {
        return snapshotTree(slot).get(key);
    }

    private <V> Tree<V> snapshotTree(Slot<V> slot) {
        return new Tree<>(slot.schema(), source(), branch.roots().get(slot));
    }

    private void edgeAction(long edge, EdgeAction action) {
        requireActive();
        footprints.compute(edge, (_, existing) -> (existing == null ? baseFootprint(edge) : existing).touch(action));
        execute(new Op.EdgeOp(edge, action));
    }

    private Footprint baseFootprint(long edge) {
        Optional<AtomRecord> base = new Tree<>(EngineSlots.ATOM_SCHEMA, source(), branch.roots().get(EngineSlots.CATALOG)).get(edge);
        Ref members = membersOf(base);
        boolean set = base.map(record -> record instanceof EdgeRecord found && found.kind() == EdgeKind.SET).orElse(false);
        return new Footprint(members, base.isPresent(), new HashSet<>(), set);
    }

    private void execute(Op op) {
        requireActive();
        try {
            op.apply(workspace);
        } catch (HStoreException.InvalidSchema | HStoreException.ResourceLimit rejected) {
            throw rejected;
        } catch (RuntimeException | Error failure) {
            state = State.FAILED;
            throw failure;
        }
        ops.add(op);
    }

    private void requireActive() {
        if (state != State.ACTIVE) {
            throw new IllegalStateException("transaction " + id + " is " + state);
        }
    }

    public boolean readOnly() {
        return ops.isEmpty();
    }

    public CommitResult commit() {
        requireActive();
        try {
            CommitResult result = manager.commit(this);
            state = State.COMMITTED;
            return result;
        } catch (RuntimeException | Error failure) {
            state = State.ABORTED;
            if (failure instanceof HStoreException.Conflict || failure instanceof HStoreException.ResourceLimit) {
                manager.discard(spilled());
            }
            throw failure;
        } finally {
            retained.unpin();
        }
    }

    public void abort() {
        if (state == State.ACTIVE || state == State.FAILED) {
            state = State.ABORTED;
            if (spilledPages > 0) {
                manager.logAbort(id);
                manager.discard(spilled());
            }
            retained.unpin();
        }
    }

    @Override
    public void close() {
        abort();
    }

    Workspace workspace() {
        return workspace;
    }

    RootVector baseRoots() {
        return branch.roots();
    }

    long spilledPages() {
        return spilledPages;
    }

    long[] spilled() {
        return Arrays.copyOf(spilled, spilledPages);
    }

    private void spilled(long pageId) {
        if (spilledPages == spilled.length) {
            spilled = Arrays.copyOf(spilled, Math.max(16, spilled.length * 2));
        }
        spilled[spilledPages++] = pageId;
    }

    Workspace replay(RootVector latest) {
        Set<Long> verified = new HashSet<>();
        Workspace replayed = manager.workspace(scope, latest, (edge, target) -> {
            if (verified.add(edge)) {
                verifyEdge(edge, target);
            }
        }, true);
        ops.forEach(op -> op.apply(replayed));
        return replayed;
    }

    private void verifyEdge(long edge, Workspace target) {
        Footprint footprint = footprints.get(edge);
        if (footprint == null) {
            return;
        }
        Optional<AtomRecord> latest = target.get(EngineSlots.CATALOG, edge);
        if (latest.isEmpty()) {
            if (footprint.existed()) {
                throw HStoreException.conflict("hyperedge " + edge + " was deleted concurrently");
            }
            return;
        }
        Ref current = membersOf(latest);
        if (Ref.same(current, footprint.base())) {
            return;
        }
        if (!footprint.commutative() || !(latest.get() instanceof EdgeRecord record) || record.kind() != EdgeKind.SET) {
            throw HStoreException.conflict("hyperedge " + edge + " root changed since the transaction began");
        }
        Tree<Incidence> before = new Tree<>(TopologySchemas.SET_MEMBERS, source(), footprint.base());
        Tree<Incidence> after = before.withRoot(current);
        boolean overlapping = TreeDiff.diff(before, after).anyMatch(delta -> footprint.members().contains(delta.key()));
        if (overlapping) {
            throw HStoreException.conflict("hyperedge " + edge + " has concurrent changes to the same members");
        }
    }

    void validateReads(RootVector latest) {
        if (!serializable) {
            return;
        }
        slotReads.forEach(slot -> {
            if (!sameSlot(slot, latest)) {
                throw HStoreException.conflict("serializable scan of " + slot + " was invalidated");
            }
        });
        Tree<AtomRecord> catalog = new Tree<>(EngineSlots.ATOM_SCHEMA, source(), latest.get(EngineSlots.CATALOG));
        atomReads.forEach((atom, seen) -> {
            Optional<AtomRecord> now = catalog.get(atom);
            boolean unchanged = seen.isPresent() == now.isPresent()
                    && (seen.isEmpty() || Objects.equals(seen.get(), now.get())
                    || (seen.get() instanceof EdgeRecord was && now.get() instanceof EdgeRecord is && Ref.same(was.members(), is.members())));
            if (!unchanged) {
                throw HStoreException.conflict("serializable read of atom " + atom + " was invalidated");
            }
        });
        keyReads.forEach((read, seen) -> {
            if (!sameValue(read.slot(), seen, latestValue(read.slot(), read.key(), latest))) {
                throw HStoreException.conflict("serializable read of " + describe(read.slot(), read.key()) + " was invalidated");
            }
        });
        rangeReads.forEach((read, seen) -> {
            if (!sameRange(read, seen, latestValue(read.slot(), read.key(), latest))) {
                throw HStoreException.conflict("serializable read of " + describe(read.slot(), read.key())
                        + " between " + read.low() + " and " + read.high() + " was invalidated");
            }
        });
    }

    private <V> boolean sameSlot(Slot<V> slot, RootVector latest) {
        return TreeDiff.sameContent(snapshotTree(slot), new Tree<>(slot.schema(), source(), latest.get(slot)));
    }

    private Optional<?> latestValue(Slot<?> slot, long key, RootVector latest) {
        return new Tree<>(slot.schema(), source(), latest.get(slot)).get(key);
    }

    @SuppressWarnings("unchecked")
    private <V> boolean sameValue(Slot<V> slot, Optional<?> seen, Optional<?> now) {
        if (seen.isEmpty() || now.isEmpty()) {
            return seen.isEmpty() && now.isEmpty();
        }
        return TreeDiff.sameValue(slot.schema(), source(), (V) seen.get(), (V) now.get());
    }

    @SuppressWarnings("unchecked")
    private <V> boolean sameRange(RangeRead read, Optional<?> seen, Optional<?> now) {
        Slot<V> slot = (Slot<V>) read.slot();
        ValueCodec<V> codec = slot.schema().codec();
        if (seen.isEmpty() && now.isEmpty()) {
            return true;
        }
        if (!codec.holdsRefs()) {
            return sameValue(slot, seen, now);
        }
        if (seen.isPresent() && now.isPresent()
                && !Objects.equals(codec.mapRefs((V) seen.get(), (_, _) -> Ref.EMPTY), codec.mapRefs((V) now.get(), (_, _) -> Ref.EMPTY))) {
            return false;
        }
        List<Tree<?>> before = seen.map(value -> TreeDiff.nested(source(), codec, (V) value)).orElse(List.of());
        List<Tree<?>> after = now.map(value -> TreeDiff.nested(source(), codec, (V) value)).orElse(List.of());
        if (seen.isPresent() && now.isPresent() ? before.size() != after.size() : before.isEmpty() && after.isEmpty()) {
            return false;
        }
        for (int i = 0; i < Math.max(before.size(), after.size()); i++) {
            Tree<?> was = before.isEmpty() ? after.get(i).withRoot(Ref.EMPTY) : before.get(i);
            Tree<?> is = after.isEmpty() ? before.get(i).withRoot(Ref.EMPTY) : after.get(i);
            if (!TreeDiff.sameNested(was, is, read.low(), read.high())) {
                return false;
            }
        }
        return true;
    }

    private static String describe(Slot<?> slot, long key) {
        if (slot.equals(EngineSlots.REVERSE)) {
            return "the incident edges of atom " + key;
        }
        if (slot.equals(EngineSlots.TYPES)) {
            return "the atoms of type " + (int) key + " in tenant " + (key >>> 32);
        }
        if (slot.equals(EngineSlots.CANONICAL)) {
            return "canonical key hash " + Long.toHexString(key);
        }
        return slot + " key " + key;
    }
}
