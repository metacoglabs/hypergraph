package io.hstore.engine.txn;

import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.EngineSlots.RequestRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.catalog.Generation;
import io.hstore.engine.catalog.RootVector;
import io.hstore.engine.catalog.SlotChange;
import io.hstore.engine.catalog.SlotRegistry;
import io.hstore.engine.feed.ChangeFeed;
import io.hstore.engine.feed.CommitEvent;
import io.hstore.engine.feed.FeedCodec;
import io.hstore.engine.page.Checksums;
import io.hstore.engine.page.PageHeader;
import io.hstore.engine.page.PageId;
import io.hstore.engine.page.PageStore;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.Materializer;
import io.hstore.engine.tree.Node;
import io.hstore.engine.tree.PagedNodeSource;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.TreeWalker;
import io.hstore.engine.tree.VisitedPages;
import io.hstore.engine.tree.WriteScope;
import io.hstore.engine.wal.WalRecord;
import io.hstore.engine.wal.WriteAheadLog;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class TransactionManager {

    private static final System.Logger LOG = System.getLogger("hstore.txn");

    public record Storage(PageStore pages, PagedNodeSource source, WriteAheadLog wal, ChangeFeed feed, FeedCodec feedCodec,
                          SlotRegistry slots, List<Derivation> derivations, Durability durability, WalMode walMode,
                          CrashPoint.Injector faults, int historyLimit) {
    }

    public record Statistics(long commits, long rebases, long conflicts, long readOnly, long pagesWritten, long walBytes,
                             double averageGroupCommit) {
    }

    private record Appended(GroupCommitter.Pending pending, long pages, long walBytes) {
    }

    private record PageWrite(long pageId, MemorySegment image) {
    }

    private final Storage storage;
    private final List<Derivation> derivations;
    private final AtomicReference<Generation> current;
    private final AtomicReference<Generation> head;
    private final GroupCommitter committer;
    private final ConcurrentSkipListMap<Long, Generation> history = new ConcurrentSkipListMap<>();
    private final ConcurrentSkipListMap<Long, Integer> pins = new ConcurrentSkipListMap<>();
    private final ReentrantLock commitLock = new ReentrantLock();
    private final AtomicLong atoms;
    private final AtomicLong txns;
    private final LongAdder commits = new LongAdder();
    private final LongAdder rebases = new LongAdder();
    private final LongAdder conflicts = new LongAdder();
    private final LongAdder readOnly = new LongAdder();
    private final LongAdder pagesWritten = new LongAdder();
    private final LongAdder walBytes = new LongAdder();
    private volatile long relocationFence;

    public TransactionManager(Storage storage, Generation recovered, Collection<Generation> retained) {
        this.storage = storage;
        this.derivations = Stream.concat(EngineDerivations.ALL.stream(), storage.derivations().stream()).toList();
        this.current = new AtomicReference<>(recovered);
        this.head = new AtomicReference<>(recovered);
        this.committer = new GroupCommitter(this::makeDurable);
        this.atoms = new AtomicLong(recovered.nextAtom());
        this.txns = new AtomicLong(recovered.nextTxn());
        retained.forEach(generation -> history.put(generation.id(), generation));
        history.put(recovered.id(), recovered);
    }

    public PagedNodeSource source() {
        return storage.source();
    }

    public SlotRegistry slots() {
        return storage.slots();
    }

    public Generation current() {
        return current.get();
    }

    public Collection<Generation> history() {
        return List.copyOf(history.values());
    }

    public long allocateAtom() {
        return atoms.getAndIncrement();
    }

    Workspace workspace(WriteScope scope, RootVector base, Workspace.EdgeGuard guard, boolean replaying) {
        return new Workspace(storage.source(), scope, derivations, base, guard, replaying);
    }

    public Snapshot snapshot(int branch) {
        Generation generation = pinCurrent();
        try {
            return new Snapshot(this, generation, generation.branch(branch));
        } catch (RuntimeException e) {
            unpin(generation.id());
            throw e;
        }
    }

    public Snapshot snapshotAt(long generationId, int branch) {
        pin(generationId);
        Generation generation = history.get(generationId);
        if (generation == null) {
            unpin(generationId);
            throw HStoreException.invalid("generation " + generationId + " is no longer retained");
        }
        try {
            return new Snapshot(this, generation, generation.branch(branch));
        } catch (RuntimeException e) {
            unpin(generationId);
            throw e;
        }
    }

    public Optional<Generation> generationAsOf(long wallTime) {
        return history.descendingMap().values().stream().filter(generation -> generation.wallTime() <= wallTime).findFirst();
    }

    public Transaction begin(TxnOptions options) {
        Generation generation = pinCurrent();
        try {
            return new Transaction(this, txns.getAndIncrement(), generation, options);
        } catch (RuntimeException e) {
            unpin(generation.id());
            throw e;
        }
    }

    private Generation pinCurrent() {
        Generation generation = current.get();
        pin(generation.id());
        return generation;
    }

    void pin(long generation) {
        pins.merge(generation, 1, Integer::sum);
    }

    void unpin(long generation) {
        pins.computeIfPresent(generation, (_, count) -> count > 1 ? count - 1 : null);
    }

    public OptionalLong oldestPinned() {
        Map.Entry<Long, Integer> first = pins.firstEntry();
        return first == null ? OptionalLong.empty() : OptionalLong.of(first.getKey());
    }

    CommitResult commit(Transaction txn) {
        if (txn.readOnly()) {
            readOnly.increment();
            return new CommitResult(txn.id(), txn.generation(), CommitResult.Outcome.READ_ONLY, 0, 0, 0, 0);
        }
        Appended appended;
        boolean fast;
        Workspace workspace;
        commitLock.lock();
        try {
            Generation latest = head.get();
            Branch branch = latest.branch(txn.branch());
            Optional<String> request = txn.options().requestId();
            Optional<RequestRecord> prior = request.flatMap(id -> priorRequest(branch, id));
            if (prior.isPresent()) {
                return new CommitResult(prior.get().txnId(), prior.get().generation(), CommitResult.Outcome.DUPLICATE, 0, 0, 0, 0);
            }
            boolean requestsStable = request.isEmpty()
                    || Ref.same(txn.baseRoots().get(EngineSlots.REQUESTS), branch.roots().get(EngineSlots.REQUESTS));
            boolean serializable = txn.options().isolation() == Isolation.SERIALIZABLE;
            fast = branch.roots().sameAs(txn.baseRoots()) || (requestsStable && txn.workspace().untouchedSince(branch.roots(), serializable));
            if (!fast && txn.generation() < relocationFence && txn.carriesStoredRoots()) {
                conflicts.increment();
                throw HStoreException.conflict("bulk-loaded roots of transaction " + txn.id() + " predate a compaction");
            }
            try {
                if (fast) {
                    workspace = txn.workspace();
                } else {
                    txn.validateReads(branch.roots());
                    workspace = txn.replay(branch.roots());
                }
            } catch (HStoreException.Conflict conflict) {
                conflicts.increment();
                throw conflict;
            }
            request.ifPresent(id -> workspace.write(EngineSlots.REQUESTS, Hashing.of(id),
                    _ -> Optional.of(new RequestRecord(id, txn.id(), latest.id() + 1))));
            RootVector merged = fast ? workspace.rebasedOnto(branch.roots()) : workspace.roots();
            appended = append(latest, branch.withRoots(merged), txn.id(),
                    workspace.memberChanges(), workspace.slotChanges(), List.of(), txn.options().maxPages() - txn.spilledPages());
        } finally {
            commitLock.unlock();
        }
        Generation published = committer.await(appended.pending());
        commits.increment();
        if (!fast) {
            rebases.increment();
        }
        return new CommitResult(txn.id(), published.id(), fast ? CommitResult.Outcome.COMMITTED : CommitResult.Outcome.REBASED,
                appended.pages() + txn.spilledPages(), appended.walBytes(),
                workspace.memberChanges().size(), workspace.slotChanges().size());
    }

    private Optional<RequestRecord> priorRequest(Branch branch, String requestId) {
        Optional<RequestRecord> found = new Tree<>(EngineSlots.REQUEST_SCHEMA, source(), branch.roots().get(EngineSlots.REQUESTS))
                .get(Hashing.of(requestId));
        if (found.isPresent() && !found.get().requestId().equals(requestId)) {
            throw HStoreException.invalid("request id hash collision between '" + requestId + "' and '" + found.get().requestId() + "'");
        }
        return found;
    }

    public <T> T system(int branchId, Function<Workspace, T> work) {
        T result;
        Optional<Appended> appended = Optional.empty();
        commitLock.lock();
        try {
            Generation latest = head.get();
            Branch branch = latest.branch(branchId);
            Workspace workspace = workspace(new WriteScope(), branch.roots(), Workspace.EdgeGuard.NONE, false);
            result = work.apply(workspace);
            RootVector roots = workspace.roots();
            if (!roots.sameAs(branch.roots())) {
                appended = Optional.of(append(latest, branch.withRoots(roots), txns.getAndIncrement(), workspace.memberChanges(),
                        workspace.slotChanges(), List.of(), Long.MAX_VALUE));
            }
        } finally {
            commitLock.unlock();
        }
        appended.ifPresent(pending -> committer.await(pending.pending()));
        return result;
    }

    public Branch createBranch(String name, int parentId) {
        Branch created;
        Appended appended;
        commitLock.lock();
        try {
            Generation latest = head.get();
            Branch parent = latest.branch(parentId);
            boolean taken = latest.branches().values().stream().anyMatch(branch -> branch.isActive() && branch.name().equals(name));
            if (taken) {
                throw HStoreException.invalid("branch '" + name + "' already exists");
            }
            int id = new TreeSet<>(latest.branches().keySet()).last() + 1;
            long txn = txns.getAndIncrement();
            created = new Branch(id, name, parentId, latest.id(), System.currentTimeMillis(), Branch.State.ACTIVE, parent.roots());
            appended = append(latest, created, txn, List.of(), List.of(), List.of(meta(txn, created)), Long.MAX_VALUE);
        } finally {
            commitLock.unlock();
        }
        committer.await(appended.pending());
        LOG.log(System.Logger.Level.INFO, "created branch {0} (#{1}) from generation {2}", name, created.id(), created.baseGeneration());
        return created;
    }

    public void closeBranch(int id, Branch.State state) {
        if (id == Branch.MAIN || state == Branch.State.ACTIVE) {
            throw HStoreException.invalid("branch " + id + " cannot transition to " + state);
        }
        Appended appended;
        commitLock.lock();
        try {
            Generation latest = head.get();
            long txn = txns.getAndIncrement();
            Branch closed = latest.branch(id).withState(state).withRoots(RootVector.EMPTY);
            appended = append(latest, closed, txn, List.of(), List.of(), List.of(meta(txn, closed)), Long.MAX_VALUE);
        } finally {
            commitLock.unlock();
        }
        committer.await(appended.pending());
        LOG.log(System.Logger.Level.INFO, "branch #{0} is now {1}", id, state);
    }

    private static WalRecord meta(long txn, Branch branch) {
        return new WalRecord.BranchMeta(txn, branch.id(), branch.name(), branch.parent(), branch.baseGeneration(), branch.createdAt(), branch.state());
    }

    public long relocate(LongPredicate moving) {
        Generation snapshot;
        long txnId;
        commitLock.lock();
        try {
            snapshot = head.get();
            txnId = txns.getAndIncrement();
        } finally {
            commitLock.unlock();
        }
        TreeWalker walker = new TreeWalker(storage.source().scanning());
        VisitedPages reached = new VisitedPages();
        Map<Long, Ref> moved = new HashMap<>();
        List<WalRecord> records = new ArrayList<>();
        List<PageWrite> writes = new ArrayList<>();
        Materializer materializer = materializer(snapshot.id() + 1, txnId, records, writes);
        WriteScope copies = new WriteScope();
        List<Runnable> pairings = new ArrayList<>();
        snapshot.branches().values().stream().filter(Branch::isActive).forEach(branch -> branch.roots().roots().forEach((slot, ref) -> {
            TreeSchema<?> schema = storage.slots().slot(slot).schema();
            Ref copy = materializer.materialize(walker.relocate(ref, schema, moving, copies, pageId -> reached.add(pageId, 0),
                    leaf -> copyPage(leaf, snapshot.id() + 1, txnId, records, writes)), schema);
            pairings.add(() -> walker.pairMoves(ref, copy, schema, moved));
        }));
        writes.forEach(write -> storage.pages().write(write.pageId(), write.image()));
        storage.wal().append(records);
        pairings.forEach(Runnable::run);
        pagesWritten.add(materializer.pagesWritten());
        Set<Integer> swapped = new HashSet<>();
        boolean committed = false;
        long generation = snapshot.id();
        while (true) {
            Appended appended = null;
            commitLock.lock();
            try {
                Generation latest = head.get();
                Branch branch = latest.branches().values().stream()
                        .filter(Branch::isActive).filter(candidate -> !swapped.contains(candidate.id()))
                        .findFirst().orElse(null);
                if (branch == null) {
                    if (!committed && !records.isEmpty()) {
                        logAbort(txnId);
                    }
                    return generation;
                }
                swapped.add(branch.id());
                WriteScope merge = new WriteScope();
                RootVector roots = branch.roots().map((slot, ref) ->
                        walker.adopt(ref, storage.slots().slot(slot).schema(), moved, reached::contains, merge));
                if (!roots.sameAs(branch.roots())) {
                    appended = append(latest, branch.withRoots(roots), committed ? txns.getAndIncrement() : txnId,
                            List.of(), List.of(), List.of(), Long.MAX_VALUE);
                    committed = true;
                    relocationFence = appended.pending().generation().id();
                }
            } finally {
                commitLock.unlock();
            }
            if (appended != null) {
                generation = committer.await(appended.pending()).id();
            }
        }
    }

    public <T> T exclusive(Supplier<T> action) {
        commitLock.lock();
        try {
            committer.drain();
            return action.get();
        } finally {
            commitLock.unlock();
        }
    }

    public void shutdown() {
        commitLock.lock();
        try {
            committer.close();
        } finally {
            commitLock.unlock();
        }
    }

    void logAbort(long txnId) {
        storage.wal().append(List.of(new WalRecord.Abort(txnId)));
    }

    Ref spill(long txnId, Ref ref, TreeSchema<?> schema) {
        List<WalRecord> records = new ArrayList<>();
        List<PageWrite> writes = new ArrayList<>();
        Materializer materializer = materializer(head.get().id() + 1, txnId, records, writes);
        Ref stored = materializer.materialize(ref, schema);
        writes.forEach(write -> storage.pages().write(write.pageId(), write.image()));
        storage.wal().append(records);
        pagesWritten.add(materializer.pagesWritten());
        return stored;
    }

    private Materializer materializer(long epoch, long txnId, List<WalRecord> records, List<PageWrite> writes) {
        return new Materializer(new Materializer.Sink() {
            @Override
            public long allocate(int length) {
                return storage.pages().allocate(epoch, length);
            }

            @Override
            public void accept(long pageId, MemorySegment image, Node frozen) {
                stage(txnId, pageId, image, records, writes);
                storage.source().admit(pageId, frozen);
            }
        }, epoch, storage.pages().pageSize());
    }

    private void stage(long txnId, long pageId, MemorySegment image, List<WalRecord> records, List<PageWrite> writes) {
        records.add(storage.walMode() == WalMode.PAGE_IMAGES
                ? new WalRecord.Page(txnId, pageId, image.toArray(ValueLayout.JAVA_BYTE))
                : new WalRecord.PageRef(txnId, pageId, Math.toIntExact(image.byteSize()), Checksums.crc32c(image)));
        writes.add(new PageWrite(pageId, image));
    }

    private Ref.Stored copyPage(Ref.Stored stored, long epoch, long txnId, List<WalRecord> records, List<PageWrite> writes) {
        MemorySegment page = storage.pages().read(stored.pageId());
        if (PageHeader.verify(page, stored.pageId()).height() != 0) {
            return stored;
        }
        MemorySegment image = MemorySegment.ofArray(page.asSlice(0, PageHeader.SIZE + PageHeader.payloadLength(page)).toArray(ValueLayout.JAVA_BYTE));
        long pageId = storage.pages().allocate(epoch, Math.toIntExact(image.byteSize()));
        PageHeader.assign(image, pageId);
        stage(txnId, pageId, image, records, writes);
        return new Ref.Stored(pageId, PageId.unitsFor(image.byteSize()), stored.summary());
    }

    private Appended append(Generation latest, Branch changed, long txnId, List<MemberChange> members,
                            List<SlotChange<?>> slotChanges, List<WalRecord> meta, long pageBudget) {
        long pendingPages = changed.roots().roots().entrySet().stream()
                .mapToLong(entry -> Materializer.pendingNodes(entry.getValue(), storage.slots().slot(entry.getKey()).schema()))
                .sum();
        if (pendingPages > pageBudget) {
            throw HStoreException.limit("transaction " + txnId + " would write " + pendingPages + " pages, over its quota");
        }
        long generationId = latest.id() + 1;
        List<WalRecord> records = new ArrayList<>();
        List<PageWrite> writes = new ArrayList<>();
        records.add(new WalRecord.Begin(txnId, changed.id(), latest.id()));
        Materializer materializer = materializer(generationId, txnId, records, writes);
        RootVector stored = changed.roots().map((slot, ref) -> materializer.materialize(ref, storage.slots().slot(slot).schema()));
        storage.faults().reach(CrashPoint.PAGE);
        Branch previous = latest.branches().get(changed.id());
        RootVector before = previous == null ? RootVector.EMPTY : previous.roots();
        new TreeSet<>(Stream.concat(before.roots().keySet().stream(), stored.roots().keySet().stream()).toList()).forEach(slot -> {
            if (!Ref.same(before.get(slot), stored.get(slot))) {
                records.add(new WalRecord.Root(txnId, changed.id(), slot, stored.get(slot)));
            }
        });
        records.addAll(meta);
        long wallTime = System.currentTimeMillis();
        CommitEvent event = new CommitEvent(generationId, txnId, wallTime, changed.id(), members, slotChanges);
        if (!event.isEmpty()) {
            records.add(new WalRecord.Feed(txnId, storage.feedCodec().encode(event)));
        }
        long walStart = storage.wal().end();
        storage.wal().append(records);
        storage.faults().reach(CrashPoint.WAL_APPEND);
        writes.forEach(write -> storage.pages().write(write.pageId(), write.image()));
        storage.faults().reach(CrashPoint.DATA_WRITE);
        Generation next = latest.successor(txnId, wallTime, atoms.get(), txns.get(), changed.withRoots(stored));
        storage.wal().append(new WalRecord.Commit(txnId, next.id(), wallTime, next.nextAtom(), next.nextTxn()));
        storage.faults().reach(CrashPoint.COMMIT_APPEND);
        head.set(next);
        long written = storage.wal().end() - walStart;
        pagesWritten.add(materializer.pagesWritten());
        walBytes.add(written);
        GroupCommitter.Pending pending = new GroupCommitter.Pending(next, () -> {
            current.set(next);
            history.put(next.id(), next);
            if (!event.isEmpty()) {
                storage.feed().append(event);
            }
            trimHistory();
            storage.faults().reach(CrashPoint.CATALOG_PUBLISH);
        });
        committer.submit(pending);
        return new Appended(pending, materializer.pagesWritten(), written);
    }

    private void makeDurable(int batchSize) {
        if (storage.durability() != Durability.SYNC) {
            return;
        }
        if (storage.walMode() == WalMode.PAGE_REFERENCES) {
            storage.pages().sync();
        }
        storage.faults().reach(CrashPoint.DATA_SYNC);
        storage.wal().sync();
        storage.faults().reach(CrashPoint.WAL_SYNC);
    }

    private void trimHistory() {
        long excess = history.size() - storage.historyLimit();
        long latest = current.get().id();
        for (Iterator<Long> generations = history.keySet().iterator(); excess > 0 && generations.hasNext(); ) {
            long generation = generations.next();
            if (generation != latest && !pins.containsKey(generation)) {
                generations.remove();
                excess--;
            }
        }
    }

    public Statistics statistics() {
        return new Statistics(commits.sum(), rebases.sum(), conflicts.sum(), readOnly.sum(), pagesWritten.sum(), walBytes.sum(),
                committer.averageBatch());
    }
}
