package io.hstore.engine.txn;

import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.Generation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

final class GroupCommitter implements AutoCloseable {

    interface Barrier {
        void makeDurable(int batchSize);
    }

    static final class Pending {
        private final Generation generation;
        private final Runnable publication;
        private boolean published;

        Pending(Generation generation, Runnable publication) {
            this.generation = generation;
            this.publication = publication;
        }

        Generation generation() {
            return generation;
        }
    }

    private static final System.Logger LOG = System.getLogger("hstore.commit");

    private final Barrier barrier;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition submitted = lock.newCondition();
    private final Condition progressed = lock.newCondition();
    private final Deque<Pending> queue = new ArrayDeque<>();
    private final Thread flusher;
    private Throwable failure;
    private boolean closed;
    private long batches;
    private long flushedCommits;

    GroupCommitter(Barrier barrier) {
        this.barrier = barrier;
        this.flusher = Thread.ofPlatform().name("hstore-group-commit").daemon().start(this::flushLoop);
    }

    void submit(Pending pending) {
        lock.lock();
        try {
            failIfBroken();
            queue.addLast(pending);
            submitted.signal();
        } finally {
            lock.unlock();
        }
    }

    Generation await(Pending pending) {
        lock.lock();
        try {
            while (!pending.published) {
                failIfFailed();
                progressed.awaitUninterruptibly();
            }
            return pending.generation;
        } finally {
            lock.unlock();
        }
    }

    void drain() {
        lock.lock();
        try {
            while (!queue.isEmpty()) {
                failIfFailed();
                progressed.awaitUninterruptibly();
            }
            failIfFailed();
        } finally {
            lock.unlock();
        }
    }

    double averageBatch() {
        lock.lock();
        try {
            return batches == 0 ? 0 : (double) flushedCommits / batches;
        } finally {
            lock.unlock();
        }
    }

    private void failIfBroken() {
        failIfFailed();
        if (closed) {
            throw new IllegalStateException("the commit pipeline is closed");
        }
    }

    private void failIfFailed() {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw HStoreException.io("the commit pipeline stopped after a durability failure; restart the engine", failure);
        }
    }

    private void flushLoop() {
        while (true) {
            List<Pending> batch;
            lock.lock();
            try {
                while (queue.isEmpty() && !closed) {
                    submitted.awaitUninterruptibly();
                }
                if (queue.isEmpty()) {
                    return;
                }
                batch = new ArrayList<>(queue);
            } finally {
                lock.unlock();
            }
            try {
                barrier.makeDurable(batch.size());
                batch.forEach(pending -> pending.publication.run());
            } catch (Throwable broken) {
                LOG.log(System.Logger.Level.ERROR, "commit pipeline failed; the engine must be restarted", broken);
                lock.lock();
                try {
                    failure = broken;
                    progressed.signalAll();
                } finally {
                    lock.unlock();
                }
                return;
            }
            lock.lock();
            try {
                batch.forEach(pending -> {
                    pending.published = true;
                    queue.removeFirst();
                });
                batches++;
                flushedCommits += batch.size();
                progressed.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            submitted.signalAll();
        } finally {
            lock.unlock();
        }
        try {
            flusher.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
