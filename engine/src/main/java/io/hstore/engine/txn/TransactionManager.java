// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

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
import io.hstore.engine.page.PageStore;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.Materializer;
import io.hstore.engine.tree.Node;
import io.hstore.engine.tree.PagedNodeSource;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.Epoch;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.WriteScope;
import io.hstore.engine.wal.WalRecord;
import io.hstore.engine.wal.WriteAheadLog;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class TransactionManager {

    private static final System.Logger LOG = System.getLogger("hstore.txn");

    public record Storage(PageStore pages, PagedNodeSource source, WriteAheadLog wal, ChangeFeed feed, FeedCodec feedCodec,
                          SlotRegistry slots, List<Derivation> derivations, Durability durability, WalMode walMode,
                          CrashPoint.Injector faults, int historyLimit, ReferenceTracker references, long writeBufferBytes) {
    }

    public record Statistics(long commits, long rebases, long conflicts, long readOnly, long pagesWritten, long walBytes,
                             double averageGroupCommit) {
    }

    private record Appended(GroupCommitter.Pending pending, long pages, long walBytes) {
    }

    private record PageWrite(long pageId, MemorySegment image) {
    }

    private static final long[] NO_PAGES = new long[0];
    private static final long ROOM_WAIT_NANOS = 30_000_000_000L;
    private static final long ROOM_POLL_NANOS = 50_000_000L;

    private final Storage storage;
    private final List<Derivation> derivations;
    private final AtomicReference<Retained> current;
    private final AtomicReference<Generation> head;
    private final GroupCommitter committer;
    private final ConcurrentSkipListMap<Long, Retained> history = new ConcurrentSkipListMap<>();
    private final ReentrantLock commitLock = new ReentrantLock();
    private final ConcurrentSkipListMap<Long, Long> commitEnds = new ConcurrentSkipListMap<>();
    private final AtomicLong unwritten = new AtomicLong();
    private volatile Epoch published = publishedEpoch(new Epoch());
    private final ReentrantLock roomLock = new ReentrantLock();
    private final Condition room = roomLock.newCondition();
    private volatile Runnable full = () -> {
    };
    private volatile Runnable committed = () -> {
    };
    private volatile boolean stopped;
    private final AtomicLong atoms;
    private final AtomicLong txns;
    private final LongAdder commits = new LongAdder();
    private final LongAdder rebases = new LongAdder();
    private final LongAdder conflicts = new LongAdder();
    private final LongAdder readOnly = new LongAdder();
    private final LongAdder pagesWritten = new LongAdder();
    private final LongAdder walBytes = new LongAdder();

    public TransactionManager(Storage storage, Generation recovered, Collection<Generation> retained) {
        this.storage = storage;
        this.derivations = Stream.concat(EngineDerivations.ALL.stream(), storage.derivations().stream()).toList();
        this.head = new AtomicReference<>(recovered);
        this.committer = new GroupCommitter(this::makeDurable);
        this.atoms = new AtomicLong(recovered.nextAtom());
        this.txns = new AtomicLong(recovered.nextTxn());
        retained.forEach(this::retain);
        this.current = new AtomicReference<>(retain(recovered));
        commitEnds.put(recovered.id(), storage.wal().end());
    }

    public PagedNodeSource source() {
        return storage.source();
    }

    public SlotRegistry slots() {
        return storage.slots();
    }

    public Generation current() {
        return current.get().generation();
    }

    public Collection<Generation> history() {
        return history.values().stream().map(Retained::generation).toList();
    }

    private Retained retain(Generation generation) {
        Retained retained = history.get(generation.id());
        if (retained == null) {
            retained = new Retained(generation);
            history.put(generation.id(), retained);
        } else {
            retained.rebind(generation);
        }
        return retained;
    }

    public long allocateAtom() {
        return atoms.getAndIncrement();
    }

    Workspace workspace(WriteScope scope, RootVector base, Workspace.EdgeGuard guard, boolean replaying) {
        return new Workspace(storage.source(), scope, derivations, base, guard, replaying);
    }

    public Snapshot snapshot(int branch) {
        return snapshot(pinCurrent(), branch);
    }

    public Snapshot snapshotAt(long generationId, int branch) {
        Retained retained = history.get(generationId);
        if (retained == null || !retained.pin()) {
            throw HStoreException.invalid("generation " + generationId + " is no longer retained");
        }
        return snapshot(retained, branch);
    }

    private Snapshot snapshot(Retained retained, int branch) {
        try {
            Generation generation = retained.generation();
            return new Snapshot(this, retained, generation, generation.branch(branch));
        } catch (RuntimeException e) {
            retained.unpin();
            throw e;
        }
    }

    public Optional<Generation> generationAsOf(long wallTime) {
        return history.descendingMap().values().stream().map(Retained::generation)
                .filter(generation -> generation.wallTime() <= wallTime).findFirst();
    }

    public Transaction begin(TxnOptions options) {
        Retained retained = pinCurrent();
        try {
            return new Transaction(this, txns.getAndIncrement(), retained, retained.generation(), options);
        } catch (RuntimeException e) {
            retained.unpin();
            throw e;
        }
    }

    private Retained pinCurrent() {
        while (true) {
            Retained retained = current.get();
            if (retained.pin()) {
                return retained;
            }
        }
    }

    public OptionalLong oldestPinned() {
        for (Map.Entry<Long, Retained> entry : history.entrySet()) {
            if (entry.getValue().pinned()) {
                return OptionalLong.of(entry.getKey());
            }
        }
        return OptionalLong.empty();
    }

    public long oldestReadable() {
        long oldest = history.firstKey();
        OptionalLong pinned = oldestPinned();
        return pinned.isPresent() ? Math.min(oldest, pinned.getAsLong()) : oldest;
    }

    CommitResult commit(Transaction txn) {
        CommitResult result = commitOnce(txn);
        if (result.outcome() == CommitResult.Outcome.DUPLICATE && current().id() < result.generation()) {
            committer.awaitPublished(result.generation());
        }
        return result;
    }

    private CommitResult commitOnce(Transaction txn) {
        if (txn.readOnly()) {
            readOnly.increment();
            return new CommitResult(txn.id(), txn.generation(), CommitResult.Outcome.READ_ONLY, 0, 0, 0, 0);
        }
        awaitRoom();
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
                discard(txn.spilled());
                return new CommitResult(prior.get().txnId(), prior.get().generation(), CommitResult.Outcome.DUPLICATE, 0, 0, 0, 0);
            }
            boolean requestsStable = request.isEmpty()
                    || Ref.same(txn.baseRoots().get(EngineSlots.REQUESTS), branch.roots().get(EngineSlots.REQUESTS));
            boolean serializable = txn.options().isolation() == Isolation.SERIALIZABLE;
            fast = branch.roots().sameAs(txn.baseRoots()) || (requestsStable && txn.workspace().untouchedSince(branch.roots(), serializable));
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
            appended = append(latest, branch.withRoots(merged), txn.id(), workspace.memberChanges(), workspace.slotChanges(),
                    List.of(), txn.options().maxPages() - txn.spilledPages(), txn.spilled(), workspace.scope());
        } finally {
            commitLock.unlock();
        }
        Generation published = committer.await(appended.pending());
        commits.increment();
        committed.run();
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
                        workspace.slotChanges(), List.of(), Long.MAX_VALUE, NO_PAGES, workspace.scope()));
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
            appended = append(latest, created, txn, List.of(), List.of(), List.of(meta(txn, created)), Long.MAX_VALUE, NO_PAGES, null);
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
            appended = append(latest, closed, txn, List.of(), List.of(), List.of(meta(txn, closed)), Long.MAX_VALUE, NO_PAGES, null);
        } finally {
            commitLock.unlock();
        }
        committer.await(appended.pending());
        LOG.log(System.Logger.Level.INFO, "branch #{0} is now {1}", id, state);
    }

    private static WalRecord meta(long txn, Branch branch) {
        return new WalRecord.BranchMeta(txn, branch.id(), branch.name(), branch.parent(), branch.baseGeneration(), branch.createdAt(), branch.state());
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
        stopped = true;
        signalRoom();
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

    void discard(long[] spilled) {
        if (spilled.length > 0) {
            storage.references().discarded(spilled);
        }
    }

    Ref spill(long txnId, Ref ref, TreeSchema<?> schema, LongConsumer written) {
        List<WalRecord> records = new ArrayList<>();
        List<PageWrite> writes = new ArrayList<>();
        Materializer materializer = materializer(head.get().id() + 1, txnId, records, writes);
        Ref stored = materializer.materialize(ref, schema);
        try {
            writes.forEach(write -> {
                storage.pages().writeUnsettled(write.pageId(), write.image());
                written.accept(write.pageId());
            });
            storage.wal().append(records);
        } finally {
            writes.forEach(write -> storage.pages().settle(write.pageId()));
        }
        pagesWritten.add(materializer.pagesWritten());
        return stored;
    }

    private Materializer materializer(long epoch, long txnId, List<WalRecord> records, List<PageWrite> writes) {
        return materializer(epoch, txnId, records, writes, null);
    }

    private Materializer materializer(long epoch, long txnId, List<WalRecord> records, List<PageWrite> writes, Epoch stamping) {
        return new Materializer(new Materializer.Sink() {
            @Override
            public long allocate(int length) {
                return storage.pages().allocate(length);
            }

            @Override
            public void accept(long pageId, MemorySegment image, Node frozen) {
                records.add(storage.walMode() == WalMode.PAGE_IMAGES
                        ? new WalRecord.Page(txnId, pageId, storage.pages().addressOf(pageId), image.toArray(ValueLayout.JAVA_BYTE))
                        : new WalRecord.PageRef(txnId, pageId, storage.pages().addressOf(pageId), Math.toIntExact(image.byteSize()), Checksums.crc32c(image)));
                writes.add(new PageWrite(pageId, image));
                storage.source().admit(pageId, frozen);
            }
        }, epoch, storage.pages().pageSize(), stamping, stamping);
    }

    private Appended append(Generation latest, Branch changed, long txnId, List<MemberChange> members,
                            List<SlotChange<?>> slotChanges, List<WalRecord> meta, long pageBudget, long[] spilled, WriteScope scope) {
        committer.ensureRunning();
        long unwrittenNodes = 0;
        long unwrittenHeap = 0;
        if (scope != null) {
            for (Map.Entry<Integer, Ref> root : changed.roots().roots().entrySet()) {
                Materializer.Unwritten unwritten = Materializer.unwritten(root.getValue(), storage.slots().slot(root.getKey()).schema(), scope);
                unwrittenNodes += unwritten.nodes();
                unwrittenHeap += unwritten.heapBytes();
            }
        }
        if (unwrittenNodes > pageBudget) {
            throw HStoreException.limit("transaction " + txnId + " would write " + unwrittenNodes + " pages, over its quota");
        }
        long generationId = latest.id() + 1;
        List<WalRecord> records = new ArrayList<>();
        List<PageWrite> writes = new ArrayList<>();
        records.add(new WalRecord.Begin(txnId, changed.id(), latest.id()));
        Materializer grafts = materializer(generationId, txnId, records, writes, published);
        Branch previous = latest.branches().get(changed.id());
        RootVector before = previous == null ? RootVector.EMPTY : previous.roots();
        if (meta.isEmpty()) {
            records.add(new WalRecord.Changes(txnId, changed.id(), ChangeLog.encode(before, changed.roots(), storage.slots(), storage.source(),
                    grafts::materialize)));
        }
        storage.faults().reach(CrashPoint.PAGE);
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
        Generation next = latest.successor(txnId, wallTime, atoms.get(), txns.get(), changed);
        storage.wal().append(new WalRecord.Commit(txnId, next.id(), wallTime, next.nextAtom(), next.nextTxn()));
        storage.faults().reach(CrashPoint.COMMIT_APPEND);
        long end = storage.wal().end();
        commitEnds.put(next.id(), end);
        head.set(next);
        unwritten.addAndGet(unwrittenHeap);
        long written = end - walStart;
        pagesWritten.add(grafts.pagesWritten());
        walBytes.add(written);
        long[] provisional = provisional(spilled, writes);
        GroupCommitter.Pending pending = new GroupCommitter.Pending(next, () -> {
            current.set(retain(next));
            storage.references().provisional(next.id(), provisional);
            if (!event.isEmpty()) {
                storage.feed().append(event);
            }
            trimHistory();
            storage.faults().reach(CrashPoint.CATALOG_PUBLISH);
        }, provisional.length > 0);
        committer.submit(pending);
        return new Appended(pending, grafts.pagesWritten(), written);
    }

    private static long[] provisional(long[] spilled, List<PageWrite> writes) {
        if (writes.isEmpty()) {
            return spilled;
        }
        long[] pages = Arrays.copyOf(spilled, spilled.length + writes.size());
        for (int i = 0; i < writes.size(); i++) {
            pages[spilled.length + i] = writes.get(i).pageId();
        }
        return pages;
    }

    public void whenFull(Runnable action) {
        full = action;
    }

    public void afterCommit(Runnable action) {
        committed = action;
    }

    public long recountUnwritten() {
        long bytes = head.get().branches().values().stream()
                .flatMap(branch -> branch.roots().roots().values().stream())
                .mapToLong(Materializer::unwrittenHeap)
                .sum();
        unwritten.set(bytes);
        return bytes;
    }

    private void awaitRoom() {
        if (unwritten.get() <= storage.writeBufferBytes() || recountUnwritten() <= storage.writeBufferBytes()) {
            return;
        }
        full.run();
        long deadline = System.nanoTime() + ROOM_WAIT_NANOS;
        roomLock.lock();
        try {
            while (unwritten.get() > storage.writeBufferBytes() && !stopped) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    LOG.log(System.Logger.Level.WARNING, "the write buffer stayed full for {0} s; committing anyway",
                            ROOM_WAIT_NANOS / 1_000_000_000L);
                    return;
                }
                room.awaitNanos(Math.min(left, ROOM_POLL_NANOS));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            roomLock.unlock();
        }
    }

    private void signalRoom() {
        roomLock.lock();
        try {
            room.signalAll();
        } finally {
            roomLock.unlock();
        }
    }

    public Epoch publishedEpoch() {
        return published;
    }

    private static Epoch publishedEpoch(Epoch epoch) {
        epoch.publish();
        return epoch;
    }

    public long unwrittenBytes() {
        return unwritten.get();
    }

    public long commitEnd(long generation) {
        Long end = commitEnds.get(generation);
        if (end == null) {
            throw new IllegalStateException("the log end of generation " + generation + " is not known");
        }
        return end;
    }

    public void checkpointed(Generation materialized, Epoch epoch) {
        epoch.publish();
        published = epoch;
        commitEnds.headMap(materialized.id(), false).clear();
        Materializer.Rebinding rebinding = new Materializer.Rebinding(epoch);
        rebound(head.get(), rebinding);
        storage.faults().reach(CrashPoint.CHECKPOINT_REBIND);
        exclusive(() -> {
            Generation rebound = rebound(head.get(), rebinding);
            head.set(rebound);
            current.set(retain(rebound));
            return null;
        });
        recountUnwritten();
        signalRoom();
    }

    private Generation rebound(Generation latest, Materializer.Rebinding rebinding) {
        Map<Integer, Branch> branches = new TreeMap<>();
        latest.branches().forEach((id, branch) -> branches.put(id, branch.withRoots(branch.roots().map((slot, ref) ->
                rebinding.rebound(ref, storage.slots().slot(slot).schema())))));
        return new Generation(latest.id(), latest.wallTime(), latest.txnId(), latest.nextAtom(), latest.nextTxn(), branches);
    }

    private void makeDurable(int batchSize, boolean wrotePages) {
        if (storage.durability() != Durability.SYNC) {
            return;
        }
        if (wrotePages && storage.walMode() == WalMode.PAGE_REFERENCES) {
            storage.pages().sync();
        }
        storage.faults().reach(CrashPoint.DATA_SYNC);
        storage.wal().sync();
        storage.faults().reach(CrashPoint.WAL_SYNC);
    }

    private void trimHistory() {
        long excess = history.size() - storage.historyLimit();
        Retained latest = current.get();
        for (Iterator<Retained> generations = history.values().iterator(); excess > 0 && generations.hasNext(); ) {
            Retained generation = generations.next();
            if (generation != latest && generation.drop()) {
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
