package io.hstore.engine;

import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.txn.CrashPoint;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.WalMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashRecoveryTest {

    @TempDir
    Path directory;

    static Stream<Arguments> crashMatrix() {
        return Stream.of(WalMode.values()).flatMap(mode -> Stream.of(CrashPoint.values()).map(point -> Arguments.of(mode, point)));
    }

    @ParameterizedTest
    @MethodSource("crashMatrix")
    void recoveryYieldsPriorOrNewStateNeverAMixture(WalMode mode, CrashPoint point) {
        AtomicBoolean armed = new AtomicBoolean(false);
        EngineOptions options = EngineTest.small().withWalMode(mode).withFaults(reached -> {
            if (reached == point && armed.compareAndSet(true, false)) {
                throw new CrashPoint.SimulatedCrash(point);
            }
        });
        StorageEngine engine = StorageEngine.open(directory, options);
        long edge = engine.write(txn -> {
            long e = txn.createEdge(EngineTest.EDGE, EdgeKind.ORDERED);
            for (int i = 0; i < 200; i++) {
                txn.append(e, Incidence.of(txn.createNode(EngineTest.NODE, "base-" + i)));
            }
            return e;
        });
        armed.set(true);
        Transaction doomed = engine.begin();
        for (int i = 0; i < 300; i++) {
            doomed.insertAt(edge, 0, Incidence.of(doomed.createNode(EngineTest.NODE, "doomed-" + i)));
        }
        assertThrows(CrashPoint.SimulatedCrash.class, doomed::commit);
        engine.halt();

        try (StorageEngine recovered = StorageEngine.open(directory, EngineTest.small().withWalMode(mode))) {
            recovered.read(snapshot -> {
                long size = snapshot.requireEdge(edge).size();
                boolean committed = point.ordinal() >= CrashPoint.COMMIT_APPEND.ordinal();
                assertTrue(size == 200 || size == 500, "partial state of " + size + " members");
                assertEquals(committed ? 500 : 200, size, "commit visibility after crash at " + point);
                assertEquals(size, snapshot.countOfType(0, EngineTest.NODE));
                List<Long> members = snapshot.requireEdge(edge).stream().map(Incidence::member).toList();
                members.forEach(member -> assertEquals(1, snapshot.degree(member)));
                return null;
            });
            recovered.write(txn -> {
                txn.append(edge, Incidence.of(txn.createNode(EngineTest.NODE, "after-crash")));
                return null;
            });
        }
        try (StorageEngine reopened = StorageEngine.open(directory, EngineTest.small().withWalMode(mode))) {
            assertTrue(reopened.read(snapshot -> snapshot.resolve(0, EngineTest.NODE, "after-crash")).isPresent());
        }
    }

    @Test
    void recoveryStopsAtTheFirstCommitWhosePagesAreMissing() throws Exception {
        EngineOptions options = EngineTest.small().withWalMode(WalMode.PAGE_REFERENCES).withPagesPerSegment(100_000);
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            engine.write(txn -> txn.createNode(EngineTest.NODE, "durable"));
        }
        StorageEngine engine = StorageEngine.open(directory, options);
        engine.write(txn -> txn.createNode(EngineTest.NODE, "lost-1"));
        long beforeSecond = Files.size(lastSegment());
        engine.write(txn -> txn.createNode(EngineTest.NODE, "lost-2"));
        engine.halt();
        try (FileChannel segment = FileChannel.open(lastSegment(), StandardOpenOption.WRITE)) {
            segment.truncate(beforeSecond - 1);
        }
        try (StorageEngine recovered = StorageEngine.open(directory, options)) {
            assertTrue(recovered.recovery().discardedCommits() >= 1);
            recovered.read(snapshot -> {
                assertTrue(snapshot.resolve(0, EngineTest.NODE, "durable").isPresent());
                assertTrue(snapshot.resolve(0, EngineTest.NODE, "lost-2").isEmpty());
                return null;
            });
            recovered.write(txn -> txn.createNode(EngineTest.NODE, "afterwards"));
        }
        try (StorageEngine reopened = StorageEngine.open(directory, options)) {
            assertTrue(reopened.read(snapshot -> snapshot.resolve(0, EngineTest.NODE, "afterwards")).isPresent());
        }
    }

    @ParameterizedTest
    @EnumSource(WalMode.class)
    void directoryEntriesLostInACrashAreRebuiltFromTheLog(WalMode mode) throws Exception {
        EngineOptions options = EngineTest.small().withWalMode(mode).withCheckpointWalBytes(Long.MAX_VALUE);
        Path addresses = directory.resolve("data").resolve("segments").resolve("pages.dir");
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            engine.write(txn -> txn.createNode(EngineTest.NODE, "checkpointed"));
        }
        byte[] checkpointed = Files.readAllBytes(addresses);
        StorageEngine engine = StorageEngine.open(directory, options);
        for (int i = 0; i < 50; i++) {
            String name = "logged-" + i;
            engine.write(txn -> txn.createNode(EngineTest.NODE, name));
        }
        engine.halt();
        Files.write(addresses, checkpointed);
        try (StorageEngine recovered = StorageEngine.open(directory, options)) {
            assertEquals(51L, (long) recovered.read(snapshot -> snapshot.countOfType(0, EngineTest.NODE)));
            recovered.write(txn -> txn.createNode(EngineTest.NODE, "after-crash"));
        }
        try (StorageEngine reopened = StorageEngine.open(directory, options)) {
            reopened.read(snapshot -> {
                assertEquals(52L, snapshot.countOfType(0, EngineTest.NODE));
                for (String name : Stream.concat(Stream.of("checkpointed", "after-crash"), IntStream.range(0, 50).mapToObj(i -> "logged-" + i)).toList()) {
                    assertTrue(snapshot.resolve(0, EngineTest.NODE, name).isPresent(), name);
                }
                return null;
            });
        }
    }

    @Test
    void concurrentCommitsShareDurabilityBarriers() throws Exception {
        try (StorageEngine engine = StorageEngine.open(directory, EngineTest.small())) {
            try (ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 400; i++) {
                    int n = i;
                    writers.submit(() -> engine.write(txn -> txn.createNode(EngineTest.NODE, "n" + n)));
                }
            }
            assertEquals(400L, (long) engine.read(snapshot -> snapshot.countOfType(0, EngineTest.NODE)));
            assertTrue(engine.transactions().statistics().averageGroupCommit() >= 1.0);
        }
    }

    private Path lastSegment() throws Exception {
        try (Stream<Path> files = Files.list(directory.resolve("data").resolve("segments"))) {
            return files.filter(file -> file.getFileName().toString().endsWith(".seg")).max(Comparator.naturalOrder()).orElseThrow();
        }
    }
}
