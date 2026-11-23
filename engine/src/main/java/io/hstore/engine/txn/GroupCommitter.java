// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

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
        void makeDurable(int batchSize, boolean wrotePages);
    }

    static final class Pending {
        private final Generation generation;
        private final Runnable publication;
        private final boolean wrotePages;
        private boolean published;

        Pending(Generation generation, Runnable publication) {
            this(generation, publication, true);
        }

        Pending(Generation generation, Runnable publication, boolean wrotePages) {
            this.generation = generation;
            this.publication = publication;
            this.wrotePages = wrotePages;
        }

        Generation generation() {
            return generation;
        }
    }

    private static final System.Logger LOG = System.getLogger("hstore.commit");

    private final Barrier barrier;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition progressed = lock.newCondition();
    private final Deque<Pending> queue = new ArrayDeque<>();
    private Throwable failure;
    private boolean closed;
    private boolean leading;
    private long batches;
    private long flushedCommits;

    GroupCommitter(Barrier barrier) {
        this.barrier = barrier;
    }

    void submit(Pending pending) {
        lock.lock();
        try {
            failIfBroken();
            queue.addLast(pending);
        } finally {
            lock.unlock();
        }
    }

    void ensureRunning() {
        lock.lock();
        try {
            failIfBroken();
        } finally {
            lock.unlock();
        }
    }

    Generation await(Pending pending) {
        lock.lock();
        try {
            while (!pending.published) {
                advance();
            }
            return pending.generation;
        } finally {
            lock.unlock();
        }
    }

    void awaitPublished(long generation) {
        lock.lock();
        try {
            while (queue.stream().anyMatch(pending -> pending.generation.id() <= generation)) {
                advance();
            }
            failIfFailed();
        } finally {
            lock.unlock();
        }
    }

    void drain() {
        lock.lock();
        try {
            while (!queue.isEmpty()) {
                advance();
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

    private void advance() {
        failIfFailed();
        if (leading || queue.isEmpty()) {
            progressed.awaitUninterruptibly();
            return;
        }
        List<Pending> batch = new ArrayList<>(queue);
        leading = true;
        Throwable broken = null;
        boolean interrupted = Thread.interrupted();
        lock.unlock();
        try {
            barrier.makeDurable(batch.size(), batch.stream().anyMatch(pending -> pending.wrotePages));
            for (Pending pending : batch) {
                pending.publication.run();
                interrupted |= Thread.interrupted();
            }
        } catch (Throwable thrown) {
            broken = thrown;
        } finally {
            lock.lock();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        leading = false;
        if (broken != null) {
            LOG.log(System.Logger.Level.ERROR, "commit pipeline failed; the engine must be restarted", broken);
            failure = broken;
        } else {
            batch.forEach(pending -> {
                pending.published = true;
                queue.removeFirst();
            });
            batches++;
            flushedCommits += batch.size();
        }
        progressed.signalAll();
        failIfFailed();
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

    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            while (!queue.isEmpty() && failure == null) {
                try {
                    advance();
                } catch (RuntimeException | Error stopped) {
                    return;
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
