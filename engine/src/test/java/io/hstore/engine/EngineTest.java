package io.hstore.engine;

import io.hstore.engine.catalog.Branch;
import io.hstore.engine.feed.ChangeFeed;
import io.hstore.engine.feed.CommitEvent;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Hyperedge;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.tree.TreeAlgebra;
import io.hstore.engine.txn.CommitResult;
import io.hstore.engine.txn.IncidentEdge;
import io.hstore.engine.txn.Isolation;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.TxnOptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineTest {

    static final int NODE = 1;
    static final int EDGE = 2;

    @TempDir
    Path directory;

    static EngineOptions small() {
        return EngineOptions.defaults().withPageSize(1024).withPagesPerSegment(256).withHistoryLimit(1000);
    }

    @Test
    void feedRetentionReleasesWholeSegmentsBehindHolds() {
        EngineOptions options = small().withWalSegmentBytes(4096).withFeedRetention(10);
        long held;
        long last;
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            long edge = engine.write(txn -> txn.createEdge(EDGE, EdgeKind.SET));
            for (int i = 0; i < 200; i++) {
                int index = i;
                engine.write(txn -> {
                    txn.insert(edge, txn.createNode(NODE, "f" + index));
                    return null;
                });
            }
            held = engine.transactions().current().id() - 60;
            try (ChangeFeed.Hold _ = engine.feed().hold(() -> held)) {
                int before = engine.feed().segmentCount();
                engine.checkpoint();
                assertTrue(engine.feed().segmentCount() < before);
                assertTrue(engine.feed().covers(held));
                assertFalse(engine.feed().covers(1));
                assertEquals(60, engine.feed().replay(held).count());
            }
            engine.checkpoint();
            last = engine.transactions().current().id();
            assertTrue(engine.feed().covers(last - 10));
            assertFalse(engine.feed().covers(held));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            assertEquals(last, engine.feed().lastGeneration());
            assertEquals(10, engine.feed().replay(last - 10).count());
        }
    }

    @Test
    void subscribersBehindRetentionAreToldAboutTheGap() throws InterruptedException {
        EngineOptions options = small().withWalSegmentBytes(4096).withFeedRetention(10);
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            long edge = engine.write(txn -> txn.createEdge(EDGE, EdgeKind.SET));
            for (int i = 0; i < 200; i++) {
                int index = i;
                engine.write(txn -> {
                    txn.insert(edge, txn.createNode(NODE, "g" + index));
                    return null;
                });
            }
            engine.checkpoint();
            long first = engine.feed().firstGeneration();
            assertTrue(first > 2);
            List<long[]> gaps = new CopyOnWriteArrayList<>();
            List<CommitEvent> seen = new CopyOnWriteArrayList<>();
            try (ChangeFeed.Subscription _ = engine.feed().subscribe(1, seen::add,
                    (acknowledged, firstRetained) -> gaps.add(new long[]{acknowledged, firstRetained}))) {
                long deadline = System.currentTimeMillis() + 5000;
                while (seen.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(1, gaps.size());
                assertEquals(1, gaps.getFirst()[0]);
                assertEquals(first, gaps.getFirst()[1]);
                assertEquals(first, seen.getFirst().generation());
            }
        }
    }

    @Test
    void randomTopologyMatchesOracleAcrossRestarts() {
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(42);
        Map<Long, List<Long>> ordered = new HashMap<>();
        Map<Long, Set<Long>> sets = new HashMap<>();
        List<Long> nodes = new ArrayList<>();
        StorageEngine engine = StorageEngine.open(directory, small());
        try {
            engine.write(txn -> {
                for (int i = 0; i < 300; i++) {
                    nodes.add(txn.createNode(NODE, "n" + i));
                }
                for (int i = 0; i < 12; i++) {
                    sets.put(txn.createEdge(EDGE, EdgeKind.SET), new TreeSet<>());
                    ordered.put(txn.createEdge(EDGE, EdgeKind.ORDERED), new ArrayList<>());
                }
                return null;
            });
            for (int round = 0; round < 40; round++) {
                try (Transaction txn = engine.begin()) {
                    for (int op = 0; op < 120; op++) {
                        long member = nodes.get(random.nextInt(nodes.size()));
                        if (random.nextBoolean()) {
                            long edge = List.copyOf(sets.keySet()).get(random.nextInt(sets.size()));
                            if (random.nextInt(3) > 0) {
                                txn.upsert(edge, member, incidence -> incidence.withWeight(2.5));
                                sets.get(edge).add(member);
                            } else {
                                txn.remove(edge, member);
                                sets.get(edge).remove(member);
                            }
                        } else {
                            long edge = List.copyOf(ordered.keySet()).get(random.nextInt(ordered.size()));
                            List<Long> sequence = ordered.get(edge);
                            if (random.nextInt(3) > 0 && !sequence.contains(member)) {
                                int at = random.nextInt(sequence.size() + 1);
                                txn.insertAt(edge, at, Incidence.of(member));
                                sequence.add(at, member);
                            } else if (!sequence.isEmpty()) {
                                int at = random.nextInt(sequence.size());
                                txn.removeAt(edge, at);
                                sequence.remove(at);
                            }
                        }
                    }
                    txn.commit();
                }
                if (round % 13 == 12) {
                    engine.close();
                    engine = StorageEngine.open(directory, small());
                }
            }
            StorageEngine verified = engine;
            verified.read(snapshot -> {
                sets.forEach((edge, members) -> assertEquals(List.copyOf(members),
                        snapshot.requireEdge(edge).stream().map(Incidence::member).toList()));
                ordered.forEach((edge, sequence) -> {
                    Hyperedge hyperedge = snapshot.requireEdge(edge);
                    assertEquals(sequence, hyperedge.stream().map(Incidence::member).toList());
                    for (int i = 0; i < sequence.size(); i++) {
                        assertEquals(i, hyperedge.indexOf(sequence.get(i)));
                    }
                });
                Map<Long, Set<Long>> expectedIncidence = new HashMap<>();
                sets.forEach((edge, members) -> members.forEach(m -> expectedIncidence.computeIfAbsent(m, _ -> new TreeSet<>()).add(edge)));
                ordered.forEach((edge, members) -> members.forEach(m -> expectedIncidence.computeIfAbsent(m, _ -> new TreeSet<>()).add(edge)));
                for (long node : nodes) {
                    Set<Long> actual = new TreeSet<>(snapshot.incident(node).map(IncidentEdge::edge).toList());
                    assertEquals(expectedIncidence.getOrDefault(node, Set.of()), actual, "reverse index of " + node);
                    assertEquals(actual.size(), snapshot.degree(node));
                }
                return null;
            });
        } finally {
            engine.close();
        }
    }

    @Test
    void orderedEdgesSurviveRelabelStorms() {
        try (StorageEngine engine = StorageEngine.open(directory, small())) {
            long edge = engine.write(txn -> txn.createEdge(EDGE, EdgeKind.ORDERED));
            List<Long> oracle = new ArrayList<>();
            engine.write(txn -> {
                for (int i = 0; i < 3000; i++) {
                    long node = txn.createNode(NODE, null);
                    int at = i % 2 == 0 ? 0 : oracle.size() / 2;
                    txn.insertAt(edge, at, Incidence.of(node));
                    oracle.add(at, node);
                }
                return null;
            });
            engine.read(snapshot -> {
                Hyperedge hyperedge = snapshot.requireEdge(edge);
                assertEquals(oracle, hyperedge.stream().map(Incidence::member).toList());
                assertEquals(oracle.get(1777), hyperedge.at(1777).member());
                assertEquals(1234, hyperedge.indexOf(oracle.get(1234)));
                assertEquals(1, snapshot.incident(oracle.getFirst()).count());
                return null;
            });
        }
    }

    @Test
    void snapshotsAreStableUnderLaterCommits() {
        try (StorageEngine engine = StorageEngine.open(directory, small())) {
            long[] ids = engine.write(txn -> {
                long edge = txn.createEdge(EDGE, EdgeKind.SET);
                long a = txn.createNode(NODE, "a");
                txn.insert(edge, a);
                return new long[]{edge, a};
            });
            try (Snapshot before = engine.snapshot()) {
                engine.write(txn -> {
                    long b = txn.createNode(NODE, "b");
                    txn.insert(ids[0], b);
                    txn.remove(ids[0], ids[1]);
                    return null;
                });
                assertEquals(List.of(ids[1]), before.requireEdge(ids[0]).stream().map(Incidence::member).toList());
                long previous = before.generation();
                try (Snapshot travel = engine.snapshotAt(previous, Branch.MAIN)) {
                    assertTrue(travel.contains(ids[0], ids[1]));
                }
            }
            engine.read(now -> {
                assertFalse(now.contains(ids[0], ids[1]));
                return null;
            });
        }
    }

    @Test
    void commutativeSetWritesRebaseAndConflictingWritesAbort() {
        try (StorageEngine engine = StorageEngine.open(directory, small())) {
            long[] ids = engine.write(txn -> new long[]{txn.createEdge(EDGE, EdgeKind.SET), txn.createNode(NODE, "x"),
                    txn.createNode(NODE, "y"), txn.createNode(NODE, "z"), txn.createEdge(EDGE, EdgeKind.ORDERED)});
            Transaction first = engine.begin();
            Transaction second = engine.begin();
            first.insert(ids[0], ids[1]);
            second.insert(ids[0], ids[2]);
            assertEquals(CommitResult.Outcome.COMMITTED, first.commit().outcome());
            assertEquals(CommitResult.Outcome.REBASED, second.commit().outcome());
            Transaction third = engine.begin();
            Transaction fourth = engine.begin();
            third.upsert(ids[0], ids[1], incidence -> incidence.withWeight(3.0));
            fourth.remove(ids[0], ids[1]);
            third.commit();
            assertThrows(HStoreException.Conflict.class, fourth::commit);
            Transaction fifth = engine.begin();
            Transaction sixth = engine.begin();
            fifth.insertAt(ids[4], 0, Incidence.of(ids[1]));
            sixth.insertAt(ids[4], 0, Incidence.of(ids[2]));
            fifth.commit();
            assertThrows(HStoreException.Conflict.class, sixth::commit);
            engine.read(snapshot -> {
                assertEquals(Set.of(ids[1], ids[2]), new HashSet<>(snapshot.requireEdge(ids[0]).stream().map(Incidence::member).toList()));
                assertEquals(3.0, snapshot.incidence(ids[0], ids[1]).orElseThrow().weightValue());
                return null;
            });
        }
    }

    @Test
    void serializableReadsDetectWriteSkew() {
        try (StorageEngine engine = StorageEngine.open(directory, small())) {
            long[] ids = engine.write(txn -> new long[]{txn.createEdge(EDGE, EdgeKind.SET), txn.createEdge(EDGE, EdgeKind.SET),
                    txn.createNode(NODE, "p")});
            TxnOptions serializable = TxnOptions.defaults().withIsolation(Isolation.SERIALIZABLE);
            Transaction left = engine.begin(serializable);
            Transaction right = engine.begin(serializable);
            if (!left.contains(ids[1], ids[2])) {
                left.insert(ids[0], ids[2]);
            }
            if (!right.contains(ids[0], ids[2])) {
                right.insert(ids[1], ids[2]);
            }
            left.commit();
            assertThrows(HStoreException.Conflict.class, right::commit);
        }
    }

    @Test
    void canonicalKeysDeletesAndRequestIdempotency() {
        try (StorageEngine engine = StorageEngine.open(directory, small())) {
            long alice = engine.write(txn -> txn.createNode(NODE, "alice"));
            assertThrows(HStoreException.InvalidSchema.class, () -> engine.write(txn -> txn.createNode(NODE, "alice")));
            long edge = engine.write(txn -> {
                long e = txn.createEdge(EDGE, EdgeKind.SET);
                txn.insert(e, alice);
                return e;
            });
            TxnOptions once = TxnOptions.defaults().withRequestId("req-1");
            CommitResult first;
            try (Transaction txn = engine.begin(once)) {
                txn.insert(edge, txn.createNode(NODE, "bob"));
                first = txn.commit();
            }
            try (Transaction retry = engine.begin(once)) {
                retry.insert(edge, retry.createNode(NODE, "carol"));
                CommitResult again = retry.commit();
                assertTrue(again.duplicate());
                assertEquals(first.generation(), again.generation());
            }
            engine.write(txn -> {
                txn.delete(alice);
                return null;
            });
            engine.read(snapshot -> {
                assertTrue(snapshot.resolve(0, NODE, "alice").isEmpty());
                assertTrue(snapshot.resolve(0, NODE, "carol").isEmpty());
                assertEquals(1, snapshot.cardinality(edge));
                assertEquals(1, snapshot.countOfType(0, NODE));
                return null;
            });
        }
    }

    @Test
    void branchesIsolateHypotheses() {
        try (StorageEngine engine = StorageEngine.open(directory, small())) {
            long[] ids = engine.write(txn -> {
                long edge = txn.createEdge(EDGE, EdgeKind.SET);
                long a = txn.createNode(NODE, "a");
                txn.insert(edge, a);
                return new long[]{edge, a};
            });
            Branch hypothesis = engine.createBranch("hypothesis", Branch.MAIN);
            long added = engine.write(TxnOptions.defaults().onBranch(hypothesis.id()), txn -> {
                long b = txn.createNode(NODE, "b");
                txn.insert(ids[0], b);
                return b;
            });
            try (Snapshot main = engine.snapshot(); Snapshot branch = engine.snapshot(hypothesis.id())) {
                assertEquals(1, main.cardinality(ids[0]));
                assertEquals(2, branch.cardinality(ids[0]));
                assertTrue(branch.resolve(0, NODE, "b").isPresent());
                assertTrue(main.resolve(0, NODE, "b").isEmpty());
                assertEquals(1, TreeAlgebra.countIntersect(main.requireEdge(ids[0]).membership(), branch.requireEdge(ids[0]).membership()));
                assertTrue(branch.contains(ids[0], added));
            }
            engine.dropBranch(hypothesis.id());
            assertThrows(HStoreException.InvalidSchema.class, () -> engine.snapshot(hypothesis.id()));
        }
    }

    @Test
    void changeFeedReplaysAndStreamsCommittedDeltas() throws InterruptedException {
        try (StorageEngine engine = StorageEngine.open(directory, small())) {
            List<CommitEvent> live = new CopyOnWriteArrayList<>();
            long start = engine.transactions().current().id();
            try (ChangeFeed.Subscription subscription = engine.feed().subscribe(start, live::add)) {
                long edge = engine.write(txn -> {
                    long e = txn.createEdge(EDGE, EdgeKind.SET);
                    txn.insert(e, txn.createNode(NODE, "m"));
                    return e;
                });
                long deadline = System.currentTimeMillis() + 5000;
                while (live.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                assertFalse(live.isEmpty());
                List<CommitEvent> replayed = engine.feed().replay(start).toList();
                assertEquals(live.getFirst(), replayed.getFirst());
                assertTrue(replayed.getFirst().members().stream().anyMatch(change ->
                        change instanceof MemberChange.Added added && added.edge() == edge));
                assertTrue(subscription.acknowledged() > start);
            }
        }
    }

    @Test
    void giantBulkLoadSpillsAndSupportsAlgebra() {
        EngineOptions options = small().withPagesPerSegment(4096);
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            long[] edges = engine.write(txn -> {
                long giant = txn.createEdge(EDGE, EdgeKind.SET);
                long small = txn.createEdge(EDGE, EdgeKind.SET);
                List<Incidence> members = LongStream.range(0, 60_000).mapToObj(i -> Incidence.of(1_000_000 + i)).toList();
                txn.load(giant, members);
                txn.load(small, List.of(Incidence.of(1_000_005), Incidence.of(1_059_999), Incidence.of(5)));
                return new long[]{giant, small};
            });
        }
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            engine.read(snapshot -> {
                long giant = snapshot.atomsOfType(0, EDGE).min().orElseThrow();
                Hyperedge big = snapshot.requireEdge(giant);
                Hyperedge little = snapshot.requireEdge(giant + 1);
                assertEquals(60_000, big.size());
                assertEquals(2, TreeAlgebra.countIntersect(big.membership(), little.membership()));
                assertEquals(1_030_000, big.at(30_000).member());
                assertEquals(2, snapshot.degree(1_000_005));
                assertEquals(1, snapshot.degree(1_000_006));
                return null;
            });
        }
    }

    @Test
    void compactionReclaimsChurnedSegments() {
        EngineOptions options = small().withPagesPerSegment(64).withHistoryLimit(2);
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            long edge = engine.write(txn -> txn.createEdge(EDGE, EdgeKind.SET));
            LinkedHashSet<Long> oracle = new LinkedHashSet<>();
            for (int round = 0; round < 60; round++) {
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
                for (int i = 0; i < 50; i++) {
                    oracle.add(10_000L + base + i);
                    if (base >= 100) {
                        oracle.remove(10_000L + base - 100 + i);
                    }
                }
            }
            int before = engine.stats().segments().size();
            engine.compact();
            for (int i = 0; i < 3; i++) {
                engine.write(txn -> txn.createNode(NODE, "after-compaction-" + txn.id()));
            }
            engine.compact();
            int after = engine.stats().segments().size();
            assertTrue(after < before, "segments " + before + " -> " + after);
            engine.read(snapshot -> {
                assertEquals(List.copyOf(new TreeSet<>(oracle)), snapshot.requireEdge(edge).stream().map(Incidence::member).toList());
                return null;
            });
        }
        try (StorageEngine engine = StorageEngine.open(directory, options)) {
            assertEquals(100L, (long) engine.read(snapshot -> snapshot.atomsOfType(0, EDGE).mapToObj(snapshot::requireEdge).mapToLong(Hyperedge::size).sum()));
        }
    }

    @Test
    void dictionaryInternsRolesAcrossRestarts() {
        int roleSet;
        try (StorageEngine engine = StorageEngine.open(directory, small())) {
            roleSet = engine.dictionary().roleSet(List.of("buyer", "approver"));
            assertEquals(roleSet, engine.dictionary().roleSet(List.of("approver", "buyer")));
        }
        try (StorageEngine engine = StorageEngine.open(directory, small())) {
            assertEquals(Set.of("buyer", "approver"), new HashSet<>(engine.dictionary().roles(roleSet)));
            assertEquals(List.of(roleSet), engine.dictionary().roleSetsContaining("buyer"));
        }
    }
}
