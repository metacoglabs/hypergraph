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
import io.hstore.engine.tree.WriteScope;

import java.util.ArrayList;
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

    private final TransactionManager manager;
    private final long id;
    private final Generation snapshot;
    private final Branch branch;
    private final TxnOptions options;
    private final WriteScope scope;
    private final Workspace workspace;
    private final List<Op> ops = new ArrayList<>();
    private final Map<Long, Footprint> footprints = new HashMap<>();
    private final Map<Long, Optional<AtomRecord>> atomReads = new HashMap<>();
    private final Map<KeyRead, Optional<?>> keyReads = new HashMap<>();
    private State state = State.ACTIVE;
    private long spilledPages;

    Transaction(TransactionManager manager, long id, Generation snapshot, TxnOptions options) {
        this.manager = manager;
        this.id = id;
        this.snapshot = snapshot;
        this.branch = snapshot.branch(options.branch());
        this.options = options;
        this.scope = new WriteScope((ref, schema) -> {
            Ref stored = manager.spill(this.id, ref, schema);
            spilledPages++;
            return stored;
        });
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
    void observeAtom(long atom, Optional<AtomRecord> record) {
        if (options.isolation() == Isolation.SERIALIZABLE && !atomReads.containsKey(atom)) {
            atomReads.put(atom, snapshotValue(EngineSlots.CATALOG, atom));
        }
    }

    @Override
    <V> void observeKey(Slot<V> slot, long key, Optional<V> value) {
        KeyRead read = new KeyRead(slot, key);
        if (options.isolation() == Isolation.SERIALIZABLE && !keyReads.containsKey(read)) {
            keyReads.put(read, snapshotValue(slot, key));
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
        return new Tree<>(slot.schema(), source(), branch.roots().get(slot)).get(key);
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

    boolean carriesStoredRoots() {
        return ops.stream().anyMatch(op -> op instanceof Op.EdgeOp(long _, EdgeAction.Load _));
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
            throw failure;
        } finally {
            manager.unpin(snapshot.id());
        }
    }

    public void abort() {
        if (state == State.ACTIVE || state == State.FAILED) {
            state = State.ABORTED;
            if (spilledPages > 0) {
                manager.logAbort(id);
            }
            manager.unpin(snapshot.id());
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
        if (options.isolation() != Isolation.SERIALIZABLE) {
            return;
        }
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
            Optional<?> now = new Tree<>(read.slot().schema(), source(), latest.get(read.slot())).get(read.key());
            if (!now.equals(seen)) {
                throw HStoreException.conflict("serializable read of " + read.slot() + " key " + read.key() + " was invalidated");
            }
        });
    }
}
