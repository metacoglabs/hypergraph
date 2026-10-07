// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.catalog.Branch;
import io.hstore.engine.maintenance.Compactor;
import io.hstore.engine.page.PageHeader;
import io.hstore.engine.page.PageId;
import io.hstore.engine.page.SegmentInfo;
import io.hstore.engine.page.SegmentState;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.tree.Leaf;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.TreeWalker;
import io.hstore.engine.txn.CrashPoint;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.TxnOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionTest {

    private static final int EDGES = 40;
    private static final int MEMBERS = 300;

    @TempDir
    Path directory;

    private static EngineOptions options() {
        return EngineTest.small().withHistoryLimit(4);
    }

    private static Map<Long, TreeSet<Long>> load(StorageEngine engine) {
        Map<Long, TreeSet<Long>> oracle = new HashMap<>();
        for (int e = 0; e < EDGES; e++) {
            int index = e;
            engine.write(txn -> {
                long edge = txn.createEdge(EngineTest.EDGE, EdgeKind.SET);
                TreeSet<Long> members = new TreeSet<>();
                for (int m = 0; m < MEMBERS; m++) {
                    long node = txn.createNode(EngineTest.NODE, "n-" + index + "-" + m);
                    txn.insert(edge, node);
                    members.add(node);
                }
                oracle.put(edge, members);
                return null;
            });
        }
        oracle.forEach((edge, members) -> engine.write(txn -> {
            List<Long> doomed = members.stream().filter(member -> member % 3 != 0).toList();
            doomed.forEach(member -> txn.remove(edge, member));
            doomed.forEach(members::remove);
            return null;
        }));
        return oracle;
    }

    private static void churn(StorageEngine engine, int branch, Map<Long, TreeSet<Long>> oracle, RandomGenerator random, int commits) {
        List<Long> edges = new ArrayList<>(oracle.keySet());
        for (int i = 0; i < commits; i++) {
            long edge = edges.get(random.nextInt(edges.size()));
            TreeSet<Long> members = oracle.get(edge);
            boolean remove = !members.isEmpty() && random.nextBoolean();
            engine.write(TxnOptions.defaults().onBranch(branch), txn -> {
                if (remove) {
                    long member = members.first();
                    txn.remove(edge, member);
                    members.remove(member);
                } else {
                    long node = txn.createNode(EngineTest.NODE, "churn-" + branch + "-" + random.nextLong());
                    txn.insert(edge, node);
                    members.add(node);
                }
                return null;
            });
        }
    }

    private static void verify(StorageEngine engine, int branch, Map<Long, TreeSet<Long>> oracle) {
        try (Snapshot snapshot = engine.snapshot(branch)) {
            oracle.forEach((edge, members) -> assertEquals(List.copyOf(members),
                    snapshot.requireEdge(edge).stream().map(Incidence::member).toList(), "edge " + edge + " on branch " + branch));
        }
    }

    private static Map<Long, TreeSet<Long>> copy(Map<Long, TreeSet<Long>> oracle) {
        return oracle.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> new TreeSet<>(entry.getValue())));
    }

    @Test
    void livenessCountsEveryPageOfEveryRetainedRootOnce() {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            Map<Long, TreeSet<Long>> oracle = load(engine);
            churn(engine, Branch.MAIN, oracle, RandomGeneratorFactory.of("L64X128MixRandom").create(6), 30);
            Map<Long, Integer> pages = new HashMap<>();
            TreeWalker walker = new TreeWalker(engine.transactions().source());
            Stream.concat(engine.transactions().history().stream(), Stream.of(engine.transactions().current()))
                    .flatMap(generation -> generation.branches().values().stream())
                    .forEach(branch -> branch.roots().roots().forEach((slot, ref) -> walker.visit(ref, engine.slots().slot(slot).schema(), pages)));
            Map<Integer, Long> expected = new TreeMap<>();
            pages.forEach((pageId, units) -> expected.merge(PageId.segmentOf(pageId), (long) units * PageId.UNIT_BYTES, Long::sum));
            assertEquals(expected, engine.liveness());
        }
    }

    @Test
    void commitsGoThroughWhileCompactionCopiesPages() throws Exception {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            Map<Long, TreeSet<Long>> oracle = load(engine);
            CountDownLatch committed = new CountDownLatch(1);
            AtomicBoolean waited = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread compactor = Thread.ofPlatform().start(() -> {
                try {
                    engine.transactions().relocate(pageId -> {
                        if (waited.compareAndSet(false, true)) {
                            try {
                                assertTrue(committed.await(10, TimeUnit.SECONDS), "a commit was blocked while compaction copied pages");
                            } catch (InterruptedException e) {
                                throw new IllegalStateException(e);
                            }
                        }
                        return false;
                    });
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            while (!waited.get()) {
                Thread.onSpinWait();
            }
            churn(engine, Branch.MAIN, oracle, RandomGeneratorFactory.of("L64X128MixRandom").create(1), 1);
            committed.countDown();
            compactor.join(TimeUnit.SECONDS.toMillis(20));
            assertFalse(compactor.isAlive());
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
            verify(engine, Branch.MAIN, oracle);
        }
    }

    @Test
    void commitsBetweenSnapshotAndSwapArePointedAtTheCopies() throws Exception {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            Map<Long, TreeSet<Long>> oracle = load(engine);
            Set<Integer> victims = engine.stats().segments().stream().filter(segment -> segment.state() == SegmentState.SEALED)
                    .map(SegmentInfo::id).collect(Collectors.toSet());
            assertTrue(victims.size() > 2, "only " + victims.size() + " sealed segments");
            AtomicBoolean started = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(4);
            engine.transactions().relocate(pageId -> {
                if (started.compareAndSet(false, true)) {
                    Thread writer = Thread.ofPlatform().start(() -> {
                        try {
                            churn(engine, Branch.MAIN, oracle, random, 50);
                        } catch (Throwable e) {
                            failure.set(e);
                        }
                    });
                    try {
                        writer.join();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return victims.contains(PageId.segmentOf(pageId));
            });
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
            Map<Long, Integer> reachable = new HashMap<>();
            TreeWalker walker = new TreeWalker(engine.transactions().source());
            engine.transactions().current().branches().values().forEach(branch -> branch.roots().roots().forEach((slot, ref) ->
                    walker.visit(ref, engine.slots().slot(slot).schema(), reachable)));
            List<Long> stale = reachable.keySet().stream().filter(pageId -> victims.contains(PageId.segmentOf(pageId))).toList();
            assertEquals(List.of(), stale, stale.size() + " pages in the current trees still point into compacted segments");
            verify(engine, Branch.MAIN, oracle);
        }
    }

    @Test
    void commitsOnTwoBranchesDuringCompactionAreKeptAndOldSegmentsGoAway() throws Exception {
        Map<Long, TreeSet<Long>> main;
        Map<Long, TreeSet<Long>> side;
        int branchId;
        List<Integer> compacted;
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            main = load(engine);
            Branch branch = engine.createBranch("side", Branch.MAIN);
            branchId = branch.id();
            side = copy(main);
            AtomicReference<Compactor.Report> report = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread compactor = Thread.ofPlatform().start(() -> {
                try {
                    report.set(engine.compact());
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(2);
            int rounds = 0;
            int duringCompaction = 0;
            while (compactor.isAlive() || rounds < 20) {
                churn(engine, Branch.MAIN, main, random, 1);
                churn(engine, branchId, side, random, 1);
                rounds++;
                if (compactor.isAlive()) {
                    duringCompaction++;
                }
            }
            compactor.join();
            assertTrue(duringCompaction > 0, "no commit landed while compaction ran");
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
            compacted = report.get().compacted();
            assertFalse(compacted.isEmpty(), "nothing was compacted");
            verify(engine, Branch.MAIN, main);
            verify(engine, branchId, side);
            churn(engine, Branch.MAIN, main, random, 10);
            engine.compact();
            Set<Integer> remaining = engine.stats().segments().stream().map(SegmentInfo::id).collect(Collectors.toSet());
            compacted.forEach(id -> assertFalse(remaining.contains(id), "segment " + id + " was not reclaimed"));
            verify(engine, Branch.MAIN, main);
            verify(engine, branchId, side);
        }
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            verify(engine, Branch.MAIN, main);
            verify(engine, branchId, side);
        }
    }

    @Test
    void aCorruptPageIsNotCopiedWithAFreshChecksum() throws Exception {
        try (StorageEngine engine = StorageEngine.open(directory, options())) {
            Map<Long, TreeSet<Long>> oracle = load(engine);
            Set<Integer> sealed = engine.stats().segments().stream().filter(segment -> segment.state() == SegmentState.SEALED)
                    .map(SegmentInfo::id).collect(Collectors.toSet());
            long victim;
            try (Snapshot snapshot = engine.snapshot(Branch.MAIN)) {
                victim = oracle.keySet().stream()
                        .map(edge -> snapshot.requireEdge(edge).membership().root())
                        .filter(root -> root instanceof Ref.Stored(long pageId, int _, var _) && sealed.contains(PageId.segmentOf(pageId)))
                        .mapToLong(root -> ((Ref.Stored) root).pageId())
                        .filter(pageId -> engine.transactions().source().load(pageId, snapshot.requireEdge(oracle.keySet().iterator().next()).membership().schema()) instanceof Leaf)
                        .findFirst().orElse(0L);
            }
            assertTrue(victim != 0, "no single-leaf membership tree in a sealed segment");
            Path segment = directory.resolve("data").resolve("segments").resolve("%08x.seg".formatted(PageId.segmentOf(victim)));
            try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                long at = (long) PageId.offsetOf(victim) * PageId.UNIT_BYTES + PageHeader.SIZE + 1;
                ByteBuffer one = ByteBuffer.allocate(1);
                channel.read(one, at);
                channel.write(ByteBuffer.wrap(new byte[]{(byte) (one.get(0) ^ 0x5A)}), at);
            }
            int corrupted = PageId.segmentOf(victim);
            HStoreException failure = assertThrows(HStoreException.class,
                    () -> engine.transactions().relocate(pageId -> PageId.segmentOf(pageId) == corrupted));
            assertTrue(failure.getMessage().toLowerCase().contains("checksum"), failure.getMessage());
        }
    }

    @ParameterizedTest
    @EnumSource(value = CrashPoint.class, names = {"PAGE", "WAL_APPEND", "DATA_WRITE", "COMMIT_APPEND", "DATA_SYNC", "WAL_SYNC", "CATALOG_PUBLISH"})
    void aCrashWhileSwappingInTheCopiesLosesNothing(CrashPoint point) {
        AtomicBoolean armed = new AtomicBoolean();
        EngineOptions options = options().withFaults(reached -> {
            if (reached == point && armed.compareAndSet(true, false)) {
                throw new CrashPoint.SimulatedCrash(point);
            }
        });
        Map<Long, TreeSet<Long>> oracle;
        StorageEngine engine = StorageEngine.open(directory, options);
        oracle = load(engine);
        armed.set(true);
        assertThrows(CrashPoint.SimulatedCrash.class, engine::compact);
        engine.halt();
        try (StorageEngine reopened = StorageEngine.open(directory, options())) {
            verify(reopened, Branch.MAIN, oracle);
            churn(reopened, Branch.MAIN, oracle, RandomGeneratorFactory.of("L64X128MixRandom").create(3), 5);
            reopened.compact();
            verify(reopened, Branch.MAIN, oracle);
        }
    }
}
