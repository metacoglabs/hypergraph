// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.index.IndexValues;
import io.hstore.engine.index.PostingCursor;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.Weight;
import io.hstore.engine.txn.IncidentEdge;
import io.hstore.engine.txn.View;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostingCursorTest {

    static final int NODE = 1;
    static final int EDGE = 2;

    @TempDir
    Path directory;

    private static Incidence member(long atom, int roleSet) {
        return new Incidence(atom, roleSet, Weight.ONE, Long.MIN_VALUE, Long.MAX_VALUE, 0, 0);
    }

    private static List<Long> naive(View view, List<Long> anchors, List<Predicate<IndexValues.Incident>> filters) {
        List<Set<Long>> sets = IntStream.range(0, anchors.size())
                .mapToObj(i -> Set.copyOf(view.incident(anchors.get(i))
                        .filter(edge -> filters.get(i).test(new IndexValues.Incident(edge.roleSet(), edge.locator())))
                        .map(IncidentEdge::edge).toList()))
                .toList();
        return sets.getFirst().stream().filter(edge -> sets.stream().allMatch(set -> set.contains(edge))).sorted().toList();
    }

    @Test
    void filteredLeapfrogMatchesNaiveIntersection() {
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(7);
        try (StorageEngine engine = StorageEngine.open(directory, EngineTest.small())) {
            Dictionary dictionary = engine.dictionary();
            int[] roleSets = {
                    Dictionary.NO_ROLES,
                    dictionary.roleSet(List.of("actor")),
                    dictionary.roleSet(List.of("reviewer")),
                    dictionary.roleSet(List.of("actor", "reviewer")),
                    dictionary.roleSet(List.of("target"))
            };
            List<Long> nodes = new ArrayList<>();
            engine.write(txn -> {
                for (int i = 0; i < 40; i++) {
                    nodes.add(txn.createNode(NODE, "n" + i));
                }
                for (int e = 0; e < 400; e++) {
                    long edge = txn.createEdge(EDGE, EdgeKind.SET);
                    txn.upsert(edge, nodes.getFirst(), _ -> member(nodes.getFirst(), roleSets[random.nextInt(roleSets.length)]));
                    if (e % 2 == 0) {
                        txn.upsert(edge, nodes.get(1), _ -> member(nodes.get(1), roleSets[random.nextInt(roleSets.length)]));
                    }
                    for (int k = 0; k < 3; k++) {
                        long node = nodes.get(2 + random.nextInt(nodes.size() - 2));
                        txn.upsert(edge, node, _ -> member(node, roleSets[random.nextInt(roleSets.length)]));
                    }
                }
                return null;
            });
            engine.read(snapshot -> {
                assertTrue(snapshot.degree(nodes.getFirst()) > 48);
                assertTrue(snapshot.degree(nodes.get(5)) <= 48);
                for (int round = 0; round < 300; round++) {
                    int legs = 1 + random.nextInt(3);
                    List<Long> anchors = new ArrayList<>();
                    List<Predicate<IndexValues.Incident>> filters = new ArrayList<>();
                    for (int i = 0; i < legs; i++) {
                        anchors.add(random.nextInt(4) == 0 ? nodes.get(random.nextInt(nodes.size())) : nodes.get(random.nextInt(2)));
                        BitSet accepted = new BitSet();
                        IntStream.of(roleSets).filter(_ -> random.nextBoolean()).forEach(accepted::set);
                        filters.add(random.nextInt(3) == 0 ? _ -> true : incident -> accepted.get(incident.roleSet()));
                    }
                    List<PostingCursor<IndexValues.Incident>> cursors = IntStream.range(0, legs)
                            .mapToObj(i -> snapshot.incidentCursor(anchors.get(i), filters.get(i))).toList();
                    assertEquals(naive(snapshot, anchors, filters), PostingCursor.intersect(cursors).boxed().toList(),
                            "anchors " + anchors);
                }
                return null;
            });
        }
    }

    @Test
    void cursorsOnlyMoveForward() {
        try (StorageEngine engine = StorageEngine.open(directory, EngineTest.small())) {
            int actor = engine.dictionary().roleSet(List.of("actor"));
            int reviewer = engine.dictionary().roleSet(List.of("reviewer"));
            List<Long> edges = new ArrayList<>();
            long node = engine.write(txn -> {
                long created = txn.createNode(NODE, "hub");
                for (int i = 0; i < 120; i++) {
                    long edge = txn.createEdge(EDGE, EdgeKind.SET);
                    edges.add(edge);
                    txn.upsert(edge, created, _ -> member(created, edges.size() % 3 == 0 ? actor : reviewer));
                }
                return created;
            });
            engine.read(snapshot -> {
                PostingCursor<IndexValues.Incident> cursor = snapshot.incidentCursor(node, incident -> incident.roleSet() == actor);
                assertTrue(cursor.seek(Long.MIN_VALUE));
                assertEquals(edges.getFirst(), cursor.key());
                assertFalse(cursor.accepted());
                assertTrue(cursor.seek(edges.get(2)));
                assertTrue(cursor.accepted());
                assertTrue(cursor.seek(edges.get(50)));
                assertEquals(edges.get(50), cursor.key());
                assertTrue(cursor.seek(edges.get(3)));
                assertEquals(edges.get(50), cursor.key());
                assertTrue(cursor.seek(edges.get(50) + 1));
                assertEquals(edges.get(51), cursor.key());
                assertEquals(reviewer, cursor.value().roleSet());
                assertFalse(cursor.seek(edges.getLast() + 1));
                assertThrows(RuntimeException.class, cursor::key);
                List<Long> actors = IntStream.range(0, edges.size()).filter(i -> (i + 1) % 3 == 0).mapToObj(edges::get).toList();
                assertEquals(actors, PostingCursor.intersect(List.of(snapshot.incidentCursor(node, incident -> incident.roleSet() == actor)))
                        .boxed().toList());
                PostingCursor<IndexValues.Incident> missing = snapshot.incidentCursor(node + 1_000_000, _ -> true);
                assertFalse(missing.seek(Long.MIN_VALUE));
                assertEquals(0, PostingCursor.intersect(List.of(missing, snapshot.incidentCursor(node))).count());
                return null;
            });
        }
    }

    @Test
    void rolesAreOnlyCheckedWhereEveryLegAgrees() {
        try (StorageEngine engine = StorageEngine.open(directory, EngineTest.small())) {
            int common = engine.dictionary().roleSet(List.of("author"));
            int rare = engine.dictionary().roleSet(List.of("editor"));
            long[] ids = engine.write(txn -> {
                long hub = txn.createNode(NODE, "hub");
                long leaf = txn.createNode(NODE, "leaf");
                long last = 0;
                for (int i = 0; i < 3_000; i++) {
                    last = txn.createEdge(EDGE, EdgeKind.SET);
                    long edge = last;
                    int roleSet = i == 2_999 ? rare : common;
                    txn.upsert(edge, hub, _ -> member(hub, roleSet));
                    if (i < 2) {
                        txn.upsert(edge, leaf, _ -> member(leaf, Dictionary.NO_ROLES));
                    }
                }
                return new long[]{hub, leaf, last};
            });
            engine.read(snapshot -> {
                AtomicInteger checked = new AtomicInteger();
                Predicate<IndexValues.Incident> editor = incident -> {
                    checked.incrementAndGet();
                    return incident.roleSet() == rare;
                };
                assertEquals(0, PostingCursor.intersect(List.of(snapshot.incidentCursor(ids[0], editor), snapshot.incidentCursor(ids[1]))).count());
                assertTrue(checked.get() <= 2, "role checks " + checked.get());
                checked.set(0);
                assertEquals(List.of(ids[2]), PostingCursor.intersect(List.of(snapshot.incidentCursor(ids[0], editor))).boxed().toList());
                assertEquals(3_000, checked.get());
                return null;
            });
        }
    }

    @Test
    void transactionsSeeTheirOwnPostings() {
        try (StorageEngine engine = StorageEngine.open(directory, EngineTest.small())) {
            int actor = engine.dictionary().roleSet(List.of("actor"));
            engine.write(txn -> {
                long node = txn.createNode(NODE, "n");
                long edge = txn.createEdge(EDGE, EdgeKind.SET);
                txn.upsert(edge, node, _ -> member(node, actor));
                PostingCursor<IndexValues.Incident> cursor = txn.incidentCursor(node, incident -> incident.roleSet() == actor);
                assertTrue(cursor.seek(Long.MIN_VALUE));
                assertEquals(edge, cursor.key());
                return null;
            });
        }
    }

    @Test
    void roleSetIndexFollowsNewSymbols() {
        try (StorageEngine engine = StorageEngine.open(directory, EngineTest.small())) {
            Dictionary dictionary = engine.dictionary();
            assertTrue(dictionary.roleSetsWithAll(List.of("actor")).isEmpty());
            int actor = dictionary.roleSet(List.of("actor"));
            int both = dictionary.roleSet(List.of("actor", "reviewer"));
            dictionary.roleSet(List.of("reviewer"));
            BitSet actorSets = new BitSet();
            actorSets.set(actor);
            actorSets.set(both);
            assertEquals(actorSets, dictionary.roleSetsWithAll(List.of("actor")));
            int later = dictionary.roleSet(List.of("actor", "target"));
            actorSets.set(later);
            assertEquals(actorSets, dictionary.roleSetsWithAll(List.of("actor")));
            BitSet bothSets = new BitSet();
            bothSets.set(both);
            assertEquals(bothSets, dictionary.roleSetsWithAll(List.of("reviewer", "actor")));
            assertTrue(dictionary.roleSetsWithAll(List.of("actor", "unknown")).isEmpty());
            assertEquals(actorSets.stream().boxed().toList(), dictionary.roleSetsContaining("actor"));
            assertThrows(IllegalArgumentException.class, () -> dictionary.roleSetsWithAll(List.of()));
        }
    }
}
