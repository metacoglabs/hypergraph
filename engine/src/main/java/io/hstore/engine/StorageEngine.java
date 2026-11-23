// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.CatalogImage;
import io.hstore.engine.catalog.CatalogStore;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.catalog.SlotRegistry;
import io.hstore.engine.feed.ChangeFeed;
import io.hstore.engine.feed.FeedCodec;
import io.hstore.engine.maintenance.ChangeLogCheck;
import io.hstore.engine.maintenance.Checkpointer;
import io.hstore.engine.maintenance.Compactor;
import io.hstore.engine.maintenance.Recovery;
import io.hstore.engine.maintenance.ReferenceCounter;
import io.hstore.engine.page.FileHandle;
import io.hstore.engine.page.PageStore;
import io.hstore.engine.tree.NodeCache;
import io.hstore.engine.tree.NodeSource;
import io.hstore.engine.tree.PagedNodeSource;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.TransactionManager;
import io.hstore.engine.txn.TxnOptions;
import io.hstore.engine.wal.WriteAheadLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

public final class StorageEngine implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("hstore.engine");
    private static final String FORMAT_VERSION = "10";
    private static final int CHANGED_COUNTS_LIMIT = 1 << 20;

    private static final int MAX_ATTEMPTS = 8;

    private final Path directory;
    private final EngineOptions options;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final PageStore pages;
    private final NodeCache cache;
    private final WriteAheadLog wal;
    private final ChangeFeed feed;
    private final SlotRegistry slots;
    private final TransactionManager transactions;
    private final Checkpointer checkpointer;
    private final Compactor compactor;
    private final ReferenceCounter references;
    private final Dictionary dictionary;
    private final Recovery.Outcome recovery;
    private final ReentrantLock maintenance = new ReentrantLock();
    private final ReentrantLock settings = new ReentrantLock();
    private final Thread maintainer;
    private final List<Runnable> checkpointListeners = new CopyOnWriteArrayList<>();
    private long writtenAtCheckpoint;
    private volatile OptionalLong checkpointAt;
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private volatile long wakeAtWal = Long.MAX_VALUE;
    private volatile long wakeAtPages = Long.MAX_VALUE;
    private volatile boolean closed;

    private StorageEngine(Path directory, EngineOptions options, FileChannel lockChannel, FileLock lock) {
        this.directory = directory;
        this.options = options;
        this.lockChannel = lockChannel;
        this.lock = lock;
        CatalogStore catalog = CatalogStore.open(directory.resolve("catalog"));
        CatalogImage checkpoint = Recovery.discover(catalog);
        this.pages = PageStore.open(directory.resolve("data").resolve("segments"), options.pageSize(), options.pagesPerSegment(),
                options.maxOpenSegmentFiles(), checkpoint.segments(), checkpoint.checkpointLsn());
        this.wal = WriteAheadLog.open(directory.resolve("wal").resolve("segments"), options.walSegmentBytes(), options.faults());
        this.slots = new SlotRegistry(options.slots());
        FeedCodec codec = new FeedCodec(slots);
        this.feed = ChangeFeed.open(directory.resolve("feed"), codec, options.walSegmentBytes());
        this.cache = new NodeCache(options.nodeCacheBytes(), options.pageSize());
        PagedNodeSource source = new PagedNodeSource(pages, cache);
        this.recovery = Recovery.recover(checkpoint, wal, pages, feed, slots, source);
        this.references = ReferenceCounter.recover(pages, source, slots, checkpoint.current(), checkpoint.history(),
                recovery.image().current().id());
        TransactionManager.Storage storage = new TransactionManager.Storage(pages, source, wal, feed, codec, slots,
                options.derivations(), options.durability(), options.walMode(), options.faults(), options.historyLimit(), references,
                options.writeBufferBytes());
        this.transactions = new TransactionManager(storage, recovery.image().current(), recovery.image().history());
        this.checkpointer = new Checkpointer(transactions, pages, source, wal, feed, catalog, references, slots, options.faults(),
                checkpoint.current(), recovery.image().checkpointLsn());
        this.compactor = new Compactor(transactions, pages, checkpointer, options.faults());
        this.dictionary = new Dictionary(transactions);
        this.checkpointAt = storedCheckpointAt();
        checkpointer.checkpoint();
        compactor.reclaim();
        references.start();
        LOG.log(System.Logger.Level.INFO, "database at {0} is ready: generation {1}, {2} data segments, page size {3}, {4} durability, {5} wal",
                directory, transactions.current().id(), pages.segments().size(), options.pageSize(), options.durability(), options.walMode());
        armWake();
        this.maintainer = Thread.ofVirtual().name("hstore-maintenance").start(this::maintain);
        transactions.whenFull(() -> LockSupport.unpark(maintainer));
        transactions.afterCommit(this::wakeIfDue);
    }

    public static StorageEngine open(Path directory, EngineOptions options) {
        try {
            Files.createDirectories(directory);
            verifyFormat(directory, options);
            FileChannel channel = FileChannel.open(directory.resolve("LOCK"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw HStoreException.invalid("database " + directory + " is opened by another process");
            }
            try {
                return new StorageEngine(directory, options, channel, lock);
            } catch (RuntimeException | Error failure) {
                channel.close();
                throw failure;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open database at " + directory, e);
        }
    }

    private static void verifyFormat(Path directory, EngineOptions options) throws IOException {
        Path file = directory.resolve("FORMAT");
        Properties format = new Properties();
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) {
                format.load(in);
            }
            if (!format.getProperty("format", "1").equals(FORMAT_VERSION)) {
                throw HStoreException.invalid("database " + directory + " uses storage format " + format.getProperty("format", "1")
                        + "; this build reads format " + FORMAT_VERSION + " (memory documents); export and reload it");
            }
            int stored = Integer.parseInt(format.getProperty("page-size"));
            if (stored != options.pageSize()) {
                throw HStoreException.invalid("database uses " + stored + " byte pages, options request " + options.pageSize());
            }
            return;
        }
        format.setProperty("format", FORMAT_VERSION);
        format.setProperty("page-size", Integer.toString(options.pageSize()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        format.store(bytes, null);
        Path temporary = directory.resolve("FORMAT.tmp");
        try (FileChannel channel = FileHandle.channel(temporary, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(bytes.toByteArray()));
            channel.force(true);
        }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        FileHandle.syncDirectory(directory);
    }

    public Path directory() {
        return directory;
    }

    public EngineOptions options() {
        return options;
    }

    public Transaction begin() {
        return begin(TxnOptions.defaults());
    }

    public Transaction begin(TxnOptions txnOptions) {
        requireOpen();
        return transactions.begin(txnOptions);
    }

    public Snapshot snapshot() {
        return snapshot(Branch.MAIN);
    }

    public Snapshot snapshot(int branch) {
        requireOpen();
        return transactions.snapshot(branch);
    }

    public Snapshot snapshotAt(long generation, int branch) {
        requireOpen();
        return transactions.snapshotAt(generation, branch);
    }

    public <T> T read(Function<? super Snapshot, T> work) {
        try (Snapshot snapshot = snapshot()) {
            return work.apply(snapshot);
        }
    }

    public <T> T write(Function<? super Transaction, T> work) {
        return write(TxnOptions.defaults(), work);
    }

    public <T> T write(TxnOptions txnOptions, Function<? super Transaction, T> work) {
        for (int attempt = 1; ; attempt++) {
            try (Transaction txn = begin(txnOptions)) {
                T result = work.apply(txn);
                txn.commit();
                return result;
            } catch (HStoreException.Conflict conflict) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw conflict;
                }
                backoff(attempt);
            }
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Duration.ofMillis(ThreadLocalRandom.current().nextLong(1, 1L << Math.min(attempt, 6))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw HStoreException.io("interrupted while retrying a conflicted transaction", e);
        }
    }

    public Branch createBranch(String name, int parent) {
        requireOpen();
        return transactions.createBranch(name, parent);
    }

    public void dropBranch(int branch) {
        transactions.closeBranch(branch, Branch.State.DROPPED);
    }

    public void markMerged(int branch) {
        transactions.closeBranch(branch, Branch.State.MERGED);
    }

    public List<Branch> branches() {
        return transactions.current().branches().values().stream().filter(Branch::isActive).toList();
    }

    public Dictionary dictionary() {
        return dictionary;
    }

    public ChangeFeed feed() {
        return feed;
    }

    public TransactionManager transactions() {
        return transactions;
    }

    public NodeSource source() {
        return transactions.source();
    }

    public SlotRegistry slots() {
        return slots;
    }

    public Recovery.Outcome recovery() {
        return recovery;
    }

    public void onCheckpoint(Runnable listener) {
        checkpointListeners.add(listener);
    }

    public Checkpointer.Result checkpoint() {
        requireOpen();
        maintenance.lock();
        try {
            return timedCheckpoint();
        } finally {
            maintenance.unlock();
        }
    }

    private Checkpointer.Result timedCheckpoint() {
        long started = System.nanoTime();
        Checkpointer.Result result = checkpointer.checkpoint();
        writtenAtCheckpoint = pages.bytesWritten();
        LOG.log(System.Logger.Level.INFO, "checkpoint complete: generation {0}, lsn {1}, {2} wal segments retained, {3} ms",
                result.generation(), result.lsn(), result.walSegments(), (System.nanoTime() - started) / 1_000_000);
        for (Runnable listener : checkpointListeners) {
            try {
                listener.run();
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "checkpoint listener failed", failure);
            }
        }
        feed.retain(transactions.current().id() - options.feedRetention());
        return result;
    }

    public Compactor.Report compact() {
        requireOpen();
        maintenance.lock();
        try {
            timedCheckpoint();
            Compactor.Report report = compactor.compact(options.compactionLiveRatio());
            timedCheckpoint();
            List<Integer> reclaimed = compactor.reclaim();
            LOG.log(System.Logger.Level.INFO, "compaction moved the live pages out of segments {0} and reclaimed {1}", report.compacted(), reclaimed);
            return report;
        } finally {
            maintenance.unlock();
        }
    }

    public Map<Integer, Long> liveness() {
        return pages.liveBytes();
    }

    public CheckpointThreshold checkpointThreshold() {
        return CheckpointThreshold.resolve(checkpointAt, options.checkpointWalBytes(),
                pages.liveBytes().values().stream().mapToLong(Long::longValue).sum());
    }

    public CheckpointThreshold checkpointAt(OptionalLong bytes) {
        requireOpen();
        if (bytes.isPresent() && bytes.getAsLong() < 1) {
            throw HStoreException.invalid("a checkpoint threshold must be at least one byte, not " + bytes.getAsLong());
        }
        settings.lock();
        try {
            transactions.system(Branch.MAIN, workspace -> {
                workspace.write(EngineSlots.SETTINGS, EngineSlots.CHECKPOINT_WAL_BYTES,
                        _ -> bytes.isPresent() ? Optional.of(bytes.getAsLong()) : Optional.empty());
                return null;
            });
            checkpointAt = bytes;
        } finally {
            settings.unlock();
        }
        LockSupport.unpark(maintainer);
        return checkpointThreshold();
    }

    private OptionalLong storedCheckpointAt() {
        try (Snapshot snapshot = transactions.snapshot(Branch.MAIN)) {
            return snapshot.get(EngineSlots.SETTINGS, EngineSlots.CHECKPOINT_WAL_BYTES).stream().mapToLong(Long::longValue).findFirst();
        }
    }

    public ReferenceCounter.Verification verifyReferences() {
        requireOpen();
        maintenance.lock();
        try {
            timedCheckpoint();
            return transactions.exclusive(() -> references.verify(checkpointer.materialized(), transactions.history()));
        } finally {
            maintenance.unlock();
        }
    }

    public ChangeLogCheck.Result verifyChangeLog() {
        requireOpen();
        return transactions.exclusive(() -> ChangeLogCheck.verify(wal, transactions.history(), slots, transactions.source()));
    }

    public EngineStats stats() {
        TransactionManager.Statistics txn = transactions.statistics();
        return new EngineStats(transactions.current().id(), txn.commits(), txn.rebases(), txn.conflicts(),
                pages.pagesRead(), pages.pagesWritten(), pages.bytesWritten(), pages.bytesRead(), cache.hits(), cache.misses(),
                wal.bytesAppended(), wal.segmentCount(), feed.size(), pages.segments());
    }

    private void maintain() {
        while (!closed) {
            try {
                LockSupport.parkNanos(options.maintenanceInterval().toNanos());
                if (Thread.interrupted()) {
                    return;
                }
                if (closed || !maintenance.tryLock()) {
                    continue;
                }
                try {
                    long threshold = checkpointThreshold().bytes();
                    if (transactions.recountUnwritten() > options.writeBufferBytes() / 2
                            || checkpointer.walSinceCheckpoint() > threshold
                            || pages.bytesWritten() - writtenAtCheckpoint > threshold
                            || references.pending() > CHANGED_COUNTS_LIMIT) {
                        timedCheckpoint();
                    }
                    if (compactor.worthwhile(options.compactionLiveRatio())) {
                        Compactor.Report report = compactor.compact(options.compactionLiveRatio(), 1);
                        if (!report.compacted().isEmpty()) {
                            timedCheckpoint();
                            LOG.log(System.Logger.Level.INFO, "background compaction moved the live pages out of segments {0}", report.compacted());
                        }
                    }
                    List<Integer> reclaimed = compactor.reclaim();
                    if (!reclaimed.isEmpty()) {
                        LOG.log(System.Logger.Level.INFO, "reclaimed retired segments {0}", reclaimed);
                    }
                    armWake();
                } finally {
                    maintenance.unlock();
                }
            } catch (RuntimeException e) {
                if (!closed) {
                    System.getLogger("hstore").log(System.Logger.Level.WARNING, "background maintenance failed", e);
                }
            }
        }
    }

    private void armWake() {
        long threshold = checkpointThreshold().bytes();
        wakeAtWal = saturatedSum(wal.bytesAppended(), Math.max(0, threshold - checkpointer.walSinceCheckpoint()));
        wakeAtPages = saturatedSum(writtenAtCheckpoint, threshold);
        wakePending.set(false);
    }

    private void wakeIfDue() {
        if ((wal.bytesAppended() > wakeAtWal || pages.bytesWritten() > wakeAtPages) && wakePending.compareAndSet(false, true)) {
            LockSupport.unpark(maintainer);
        }
    }

    private static long saturatedSum(long base, long increment) {
        return increment > Long.MAX_VALUE - base ? Long.MAX_VALUE : base + increment;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("storage engine at " + directory + " is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        maintenance.lock();
        try {
            timedCheckpoint();
        } finally {
            maintenance.unlock();
        }
        shutdown();
        LOG.log(System.Logger.Level.INFO, "database at {0} shut down cleanly", directory);
    }

    public void halt() {
        if (!closed) {
            shutdown();
        }
    }

    private void shutdown() {
        boolean interrupted = Thread.interrupted();
        try {
            closed = true;
            transactions.shutdown();
            references.close();
            maintainer.interrupt();
            try {
                maintainer.join(Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                interrupted = true;
            }
            feed.close();
            wal.close();
            pages.close();
            cache.clear();
            try {
                lock.release();
                lockChannel.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
