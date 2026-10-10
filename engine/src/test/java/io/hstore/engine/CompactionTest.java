package io.hstore.engine;

import io.hstore.engine.maintenance.Compactor;
import io.hstore.engine.page.SegmentInfo;
import io.hstore.engine.page.SegmentState;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.txn.CrashPoint;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.Transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionTest {

    private static final int ROUNDS = 60;

    @TempDir
    Path directory;

    private static EngineOptions options() {
        return EngineTest.small().withPagesPerSegment(64).withHistoryLimit(2).withCheckpointWalBytes(Long.MAX_VALUE);
    }

    @Test
    void compactionMovesPagesWithoutWritingAGeneration() {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            long edge = churn(engine);
            long generation = engine.stats().generation();
            Set<Integer> before = segmentIds(engine);
            Compactor.Report report = engine.compact();
            assertFalse(report.compacted().isEmpty());
            assertEquals(generation, engine.stats().generation(), "compaction must not commit");
            Set<Integer> after = segmentIds(engine);
            report.compacted().forEach(victim -> assertFalse(after.contains(victim), "segment " + victim + " still exists"));
            assertTrue(after.size() < before.size(), "segments " + before + " -> " + after);
            assertEquals(expected(), members(engine, edge));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            assertTrue(engine.liveness().keySet().stream().allMatch(segmentIds(engine)::contains));
            assertEquals(expected(), members(engine, onlyEdge(engine)));
        }
    }

    @Test
    void retainedHistoryStaysReadableOnceItsSegmentsAreDeleted() {
        EngineOptions options = options().withHistoryLimit(20);
        List<Long> written = Stream.iterate(500_000L, i -> i + 1).limit(2_000).toList();
        long large;
        long edge;
        long earlier;
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            large = engine.write(txn -> {
                long created = txn.createEdge(EngineTest.EDGE, EdgeKind.SET);
                written.forEach(member -> txn.insert(created, member));
                return created;
            });
            Set<Integer> early = segmentIds(engine);
            edge = churn(engine);
            earlier = engine.stats().generation() - 10;
            Compactor.Report report = engine.compact();
            assertTrue(report.compacted().stream().anyMatch(early::contains), "no early segment was compacted: " + report.compacted());
            report.compacted().forEach(victim -> assertFalse(segmentIds(engine).contains(victim)));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options);
             Snapshot old = engine.snapshotAt(earlier, 0)) {
            assertEquals(written, old.requireEdge(large).stream().map(Incidence::member).toList());
            assertEquals(afterRound(ROUNDS - 11), old.requireEdge(edge).stream().map(Incidence::member).toList());
        }
    }

    @Test
    void aReaderFromBeforeTheMoveKeepsTheOldSegmentAround() {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            long edge = churn(engine);
            Compactor.Report report;
            try (Snapshot reader = engine.snapshot()) {
                report = engine.compact();
                assertFalse(report.compacted().isEmpty());
                for (int victim : report.compacted()) {
                    SegmentInfo info = engine.stats().segments().stream().filter(segment -> segment.id() == victim).findFirst().orElseThrow();
                    assertEquals(SegmentState.RETIRED, info.state());
                }
                assertEquals(expected(), reader.requireEdge(edge).stream().map(Incidence::member).toList());
            }
            engine.compact();
            report.compacted().forEach(victim -> assertFalse(segmentIds(engine).contains(victim)));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            assertEquals(expected(), members(engine, onlyEdge(engine)));
        }
    }

    @Test
    void commitsCarryOnWhilePagesMove() throws Exception {
        List<Long> all;
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            long edge = churn(engine);
            long conflicts = engine.stats().conflicts();
            AtomicBoolean compacting = new AtomicBoolean(true);
            List<Long> written = new ArrayList<>();
            try (ExecutorService writer = Executors.newSingleThreadExecutor()) {
                Future<?> writes = writer.submit(() -> {
                    long next = 1_000_000;
                    while (compacting.get() || written.size() < 50) {
                        long member = next++;
                        engine.write(txn -> {
                            txn.insert(edge, member);
                            return null;
                        });
                        written.add(member);
                    }
                    return null;
                });
                for (int i = 0; i < 3; i++) {
                    engine.compact();
                }
                compacting.set(false);
                writes.get();
            }
            assertEquals(conflicts, engine.stats().conflicts());
            TreeSet<Long> expected = new TreeSet<>(expected());
            expected.addAll(written);
            all = List.copyOf(expected);
            assertEquals(all, members(engine, edge));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            assertEquals(all, members(engine, onlyEdge(engine)));
        }
    }

    @Test
    void aBulkLoadStartedBeforeCompactionHasToRetry() {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            long edge = churn(engine);
            long target = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
            List<Incidence> members = Stream.iterate(2_000_000L, i -> i + 1).limit(5_000).map(Incidence::of).toList();
            Transaction early = engine.begin();
            early.load(target, members);
            assertFalse(engine.compact().compacted().isEmpty());
            assertThrows(HStoreException.Conflict.class, early::commit);
            engine.write(txn -> {
                txn.load(target, members);
                return null;
            });
            assertEquals(5_000L, (long) engine.read(snapshot -> snapshot.requireEdge(target).size()));
            assertEquals(expected(), members(engine, edge));
        }
    }

    static Stream<Arguments> crashes() {
        return Stream.of(CrashPoint.COMPACTION_MOVE, CrashPoint.COMPACTION_RETIRE)
                .flatMap(point -> Stream.of(Arguments.of(point, false), Arguments.of(point, true)));
    }

    @ParameterizedTest
    @MethodSource("crashes")
    void aCrashDuringCompactionLosesNothing(CrashPoint point, boolean directoryWritesLost) throws Exception {
        AtomicBoolean armed = new AtomicBoolean(false);
        EngineOptions options = options().withFaults(reached -> {
            if (reached == point && armed.compareAndSet(true, false)) {
                throw new CrashPoint.SimulatedCrash(point);
            }
        });
        Path addresses = directory.resolve("data").resolve("segments").resolve("pages.dir");
        long edge;
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            edge = churn(engine);
        }
        byte[] checkpointed = Files.readAllBytes(addresses);
        StorageEngine engine = StorageEngine.open(directory, options);
        armed.set(true);
        assertThrows(CrashPoint.SimulatedCrash.class, engine::compact);
        engine.halt();
        if (directoryWritesLost) {
            Files.write(addresses, checkpointed);
        }
        try (StorageEngine recovered = StorageEngine.open(directory, options())) {
            assertEquals(expected(), members(recovered, edge));
            recovered.compact();
            assertEquals(expected(), members(recovered, edge));
        }
        try (StorageEngine reopened = StorageEngine.open(directory, options())) {
            assertEquals(expected(), members(reopened, edge));
        }
    }

    private static long churn(StorageEngine engine) {
        long edge = engine.write(txn -> txn.createEdge(EngineTest.EDGE, EdgeKind.SET));
        churn(engine, edge);
        return edge;
    }

    private static void churn(StorageEngine engine, long edge) {
        for (int round = 0; round < ROUNDS; round++) {
            int base = round * 50;
            engine.write(txn -> {
                for (int i = 0; i < 50; i++) {
                    txn.insert(edge, 10_000 + base + i);
                }
                if (base >= 100) {
                    for (int i = 0; i < 50; i++) {
                        txn.remove(edge, 10_000 + base - 100 + i);
                    }
                }
                return null;
            });
        }
    }

    private static List<Long> expected() {
        return afterRound(ROUNDS - 1);
    }

    private static List<Long> afterRound(int round) {
        return Stream.iterate(10_000L + (round - 1) * 50L, i -> i + 1).limit(100).toList();
    }

    private static List<Long> members(StorageEngine engine, long edge) {
        return engine.read(snapshot -> snapshot.requireEdge(edge).stream().map(Incidence::member).toList());
    }

    private static long onlyEdge(StorageEngine engine) {
        return engine.read(snapshot -> snapshot.atomsOfType(0, EngineTest.EDGE).findFirst().orElseThrow());
    }

    private static Set<Integer> segmentIds(StorageEngine engine) {
        return engine.stats().segments().stream().map(SegmentInfo::id).collect(Collectors.toSet());
    }
}
