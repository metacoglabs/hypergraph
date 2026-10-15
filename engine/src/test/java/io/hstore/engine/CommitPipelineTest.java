// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.txn.CommitResult;
import io.hstore.engine.txn.CrashPoint;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.TxnOptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitPipelineTest {

    @TempDir
    Path directory;

    private static CommitResult commit(StorageEngine engine, TxnOptions options, String key) {
        Transaction txn = engine.begin(options);
        txn.createNode(EngineTest.NODE, key);
        return txn.commit();
    }

    private static EngineOptions failingOnce(AtomicBoolean armed) {
        return EngineTest.small().withFaults(reached -> {
            if (reached == CrashPoint.WAL_SYNC && armed.compareAndSet(true, false)) {
                throw new IllegalStateException("simulated sync failure");
            }
        });
    }

    @Test
    void aRetryWaitsForTheOriginalCommitToBePublished() throws Exception {
        CountDownLatch syncing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean(true);
        EngineOptions options = EngineTest.small().withFaults(reached -> {
            if (reached == CrashPoint.WAL_SYNC && armed.compareAndSet(true, false)) {
                syncing.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        ExecutorService pool = Executors.newCachedThreadPool();
        StorageEngine engine = StorageEngine.open(directory, options);
        try {
            TxnOptions request = TxnOptions.defaults().withRequestId("order-1");
            Future<CommitResult> original = pool.submit(() -> commit(engine, request, "first"));
            syncing.await();
            Future<CommitResult> retry = pool.submit(() -> commit(engine, request, "again"));

            assertThrows(TimeoutException.class, () -> retry.get(500, TimeUnit.MILLISECONDS));
            assertFalse(original.isDone());

            release.countDown();
            CommitResult first = original.get(10, TimeUnit.SECONDS);
            CommitResult second = retry.get(10, TimeUnit.SECONDS);
            assertEquals(CommitResult.Outcome.COMMITTED, first.outcome());
            assertEquals(CommitResult.Outcome.DUPLICATE, second.outcome());
            assertEquals(first.generation(), second.generation());
            assertTrue(engine.stats().generation() >= second.generation());
        } finally {
            release.countDown();
            pool.shutdownNow();
            engine.close();
        }
    }

    @Test
    void aRetryOfACommitThatFailedToSyncGetsTheError() {
        AtomicBoolean armed = new AtomicBoolean(false);
        StorageEngine engine = StorageEngine.open(directory, failingOnce(armed));
        TxnOptions request = TxnOptions.defaults().withRequestId("order-2");
        armed.set(true);
        assertThrows(HStoreException.class, () -> commit(engine, request, "lost"));

        assertThrows(HStoreException.class, () -> commit(engine, request, "lost-again"));
        engine.halt();
    }

    @Test
    void aRetryOfACommitPublishedBeforeTheFailureStillSucceeds() {
        AtomicBoolean armed = new AtomicBoolean(false);
        StorageEngine engine = StorageEngine.open(directory, failingOnce(armed));
        TxnOptions request = TxnOptions.defaults().withRequestId("order-3");
        CommitResult first = commit(engine, request, "kept");
        armed.set(true);
        assertThrows(HStoreException.class, () -> commit(engine, TxnOptions.defaults(), "doomed"));

        CommitResult retry = commit(engine, request, "kept-again");
        assertEquals(CommitResult.Outcome.DUPLICATE, retry.outcome());
        assertEquals(first.generation(), retry.generation());
        engine.halt();
    }

    @Test
    void nothingIsLoggedOnceTheCommitPipelineHasFailed() {
        AtomicBoolean armed = new AtomicBoolean(false);
        StorageEngine engine = StorageEngine.open(directory, failingOnce(armed));
        engine.write(txn -> txn.createNode(EngineTest.NODE, "before"));
        armed.set(true);
        assertThrows(HStoreException.class, () -> commit(engine, TxnOptions.defaults(), "failed-sync"));

        assertThrows(HStoreException.class, () -> commit(engine, TxnOptions.defaults(), "after-failure"));
        engine.halt();

        try (StorageEngine reopened = StorageEngine.open(directory, EngineTest.small())) {
            reopened.read(snapshot -> {
                assertTrue(snapshot.resolve(0, EngineTest.NODE, "before").isPresent());
                assertTrue(snapshot.resolve(0, EngineTest.NODE, "after-failure").isEmpty());
                return null;
            });
        }
    }
}
