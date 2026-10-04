package io.hstore.engine;

import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.CatalogImage;
import io.hstore.engine.catalog.CatalogStore;
import io.hstore.engine.catalog.SlotRegistry;
import io.hstore.engine.feed.ChangeFeed;
import io.hstore.engine.feed.FeedCodec;
import io.hstore.engine.maintenance.Checkpointer;
import io.hstore.engine.maintenance.Compactor;
import io.hstore.engine.maintenance.Recovery;
import io.hstore.engine.page.PageStore;
import io.hstore.engine.tree.NodeCache;
import io.hstore.engine.tree.NodeSource;
import io.hstore.engine.tree.PagedNodeSource;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.TransactionManager;
import io.hstore.engine.txn.TxnOptions;
import io.hstore.engine.wal.WriteAheadLog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

public final class StorageEngine implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("hstore.engine");
    private static final String FORMAT_VERSION = "2";

    private static final int MAX_ATTEMPTS = 8;
    private static final Duration MAINTENANCE_INTERVAL = Duration.ofMillis(500);

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
    private final Dictionary dictionary;
    private final Recovery.Outcome recovery;
    private final ReentrantLock maintenance = new ReentrantLock();
    private final Thread maintainer;
    private final List<Runnable> checkpointListeners = new CopyOnWriteArrayList<>();
    private long writtenAtCompaction;
    private volatile boolean closed;

    private StorageEngine(Path directory, EngineOptions options, FileChannel lockChannel, FileLock lock) {
        this.directory = directory;
        this.options = options;
        this.lockChannel = lockChannel;
        this.lock = lock;
        CatalogStore catalog = CatalogStore.open(directory.resolve("catalog"));
        CatalogImage checkpoint = Recovery.discover(catalog);
        this.pages = PageStore.open(directory.resolve("data").resolve("segments"), options.pageSize(), options.pagesPerSegment(), checkpoint.segments());
        this.wal = WriteAheadLog.open(directory.resolve("wal").resolve("segments"), options.walSegmentBytes());
        this.slots = new SlotRegistry(options.slots());
        FeedCodec codec = new FeedCodec(slots);
        this.feed = ChangeFeed.open(directory.resolve("feed"), codec, options.walSegmentBytes());
        this.recovery = Recovery.recover(checkpoint, wal, pages, feed, codec);
        this.cache = new NodeCache(options.cachedNodes());
        PagedNodeSource source = new PagedNodeSource(pages, cache);
        TransactionManager.Storage storage = new TransactionManager.Storage(pages, source, wal, feed, codec, slots,
                options.derivations(), options.durability(), options.walMode(), options.faults(), options.historyLimit());
        this.transactions = new TransactionManager(storage, recovery.image().current(), recovery.image().history());
        this.checkpointer = new Checkpointer(transactions, pages, wal, feed, catalog, options.historyLimit(), checkpoint.checkpointLsn());
        this.compactor = new Compactor(transactions, pages, slots);
        this.dictionary = new Dictionary(transactions);
        checkpointer.checkpoint();
        compactor.reclaim();
        LOG.log(System.Logger.Level.INFO, "database at {0} is ready: generation {1}, {2} data segments, page size {3}, {4} durability, {5} wal",
                directory, transactions.current().id(), pages.segments().size(), options.pageSize(), options.durability(), options.walMode());
        this.maintainer = Thread.ofVirtual().name("hstore-maintenance").start(this::maintain);
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
            return new StorageEngine(directory, options, channel, lock);
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
                        + "; this build reads format " + FORMAT_VERSION + " (packed segment extents); export and reload it");
            }
            int stored = Integer.parseInt(format.getProperty("page-size"));
            if (stored != options.pageSize()) {
                throw HStoreException.invalid("database uses " + stored + " byte pages, options request " + options.pageSize());
            }
            return;
        }
        format.setProperty("format", FORMAT_VERSION);
        format.setProperty("page-size", Integer.toString(options.pageSize()));
        try (var out = Files.newOutputStream(file)) {
            format.store(out, null);
        }
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
            Compactor.Report report = compactor.compact(options.compactionLiveRatio());
            timedCheckpoint();
            List<Integer> reclaimed = compactor.reclaim();
            LOG.log(System.Logger.Level.INFO, "compaction relocated segments {0} and reclaimed {1}", report.compacted(), reclaimed);
            return report;
        } finally {
            maintenance.unlock();
        }
    }

    public Map<Integer, Long> liveness() {
        return compactor.liveness();
    }

    public EngineStats stats() {
        TransactionManager.Statistics txn = transactions.statistics();
        return new EngineStats(transactions.current().id(), txn.commits(), txn.rebases(), txn.conflicts(),
                pages.pagesRead(), pages.pagesWritten(), pages.bytesWritten(), cache.hits(), cache.misses(),
                wal.bytesAppended(), wal.segmentCount(), feed.size(), pages.segments());
    }

    private void maintain() {
        while (!closed) {
            try {
                Thread.sleep(MAINTENANCE_INTERVAL);
                if (closed || !maintenance.tryLock()) {
                    continue;
                }
                try {
                    if (checkpointer.walSinceCheckpoint() > options.checkpointWalBytes()) {
                        timedCheckpoint();
                    }
                    if (pages.bytesWritten() - writtenAtCompaction > options.checkpointWalBytes()) {
                        writtenAtCompaction = pages.bytesWritten();
                        Compactor.Report report = compactor.compact(options.compactionLiveRatio());
                        if (!report.compacted().isEmpty()) {
                            timedCheckpoint();
                            LOG.log(System.Logger.Level.INFO, "background compaction relocated segments {0}", report.compacted());
                        }
                    }
                    List<Integer> reclaimed = compactor.reclaim();
                    if (!reclaimed.isEmpty()) {
                        LOG.log(System.Logger.Level.INFO, "reclaimed retired segments {0}", reclaimed);
                    }
                } finally {
                    maintenance.unlock();
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                if (!closed) {
                    System.getLogger("hstore").log(System.Logger.Level.WARNING, "background maintenance failed", e);
                }
            }
        }
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
        closed = true;
        transactions.shutdown();
        maintainer.interrupt();
        try {
            maintainer.join(MAINTENANCE_INTERVAL.multipliedBy(4));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
    }
}
