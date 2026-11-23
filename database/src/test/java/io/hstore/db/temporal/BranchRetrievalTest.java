// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.temporal;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.MemberSpec;
import io.hstore.db.hora.Budget;
import io.hstore.db.property.PropertySlots;
import io.hstore.db.query.Session;
import io.hstore.db.temporal.BranchRetrieval.AtomValue;
import io.hstore.db.temporal.BranchRetrieval.Compared;
import io.hstore.db.temporal.BranchRetrieval.Distance;
import io.hstore.db.temporal.BranchRetrieval.Key;
import io.hstore.db.temporal.BranchRetrieval.Net;
import io.hstore.db.temporal.BranchRetrieval.Route;
import io.hstore.db.temporal.BranchRetrieval.Scope;
import io.hstore.db.temporal.BranchRetrieval.Side;
import io.hstore.db.temporal.BranchRetrieval.Succession;
import io.hstore.db.value.Value;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.TxnOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchRetrievalTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;
    int b1;
    int b2;
    int b3;
    Map<Long, Set<Long>> touchedBy = new TreeMap<>();
    Map<Long, Integer> branchOf = new TreeMap<>();

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults()
                .withEngine(EngineOptions.defaults().withPageSize(4096).withHistoryLimit(100_000)));
        try (Session admin = new Session(database)) {
            admin.executeAll("""
                    CREATE NODE TYPE Person (score INT);
                    CREATE NODE TYPE Place;
                    CREATE SET EDGE TYPE Visit;
                    """);
        }
    }

    @AfterEach
    void close() {
        database.close();
    }

    private static Map<Key, Object> state(Snapshot view) {
        Map<Key, Object> state = new TreeMap<>(BranchRetrieval.KEY_ORDER);
        view.scan(EngineSlots.CATALOG).stream().forEach(entry -> {
            state.put(new Key.Atom(entry.key()), AtomValue.of(entry.value()));
            if (entry.value() instanceof AtomRecord.EdgeRecord) {
                view.edge(entry.key()).orElseThrow().members().stream()
                        .forEach(member -> state.put(new Key.Member(entry.key(), member.value().member()), member.value()));
            }
        });
        view.scan(PropertySlots.PROPERTIES).stream().forEach(entry -> entry.value().entries()
                .forEach((key, property) -> state.put(new Key.Property(entry.key(), key), property)));
        return state;
    }

    private static List<Net> difference(Map<Key, Object> before, Map<Key, Object> after) {
        Set<Key> keys = new TreeSet<>(BranchRetrieval.KEY_ORDER);
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        List<Net> nets = new ArrayList<>();
        for (Key key : keys) {
            Optional<Object> was = Optional.ofNullable(before.get(key));
            Optional<Object> now = Optional.ofNullable(after.get(key));
            if (!was.equals(now)) {
                nets.add(new Net(key, was, now));
            }
        }
        return nets;
    }

    private Map<Key, Object> stateAt(long generation, int branch) {
        try (Snapshot snapshot = database.engine().snapshotAt(generation, branch)) {
            return state(snapshot);
        }
    }

    private Map<Key, Object> head(int branch) {
        try (Snapshot snapshot = database.engine().snapshot(branch)) {
            return state(snapshot);
        }
    }

    private Branch branch(int id) {
        return database.engine().branches().stream().filter(found -> found.id() == id).findFirst().orElseThrow();
    }

    private void history(RandomGenerator random, int commits) {
        List<Integer> active = new ArrayList<>(List.of(Branch.MAIN));
        Map<Integer, List<Long>> nodes = new HashMap<>();
        Map<Integer, List<Long>> edges = new HashMap<>();
        Map<Integer, Map<Long, Set<Long>>> members = new HashMap<>();
        for (int c = 0; c < commits; c++) {
            if (c == commits / 5) {
                b1 = database.engine().createBranch("b1", Branch.MAIN).id();
                copy(Branch.MAIN, b1, nodes, edges, members);
                active.add(b1);
            }
            if (c == 2 * commits / 5) {
                b2 = database.engine().createBranch("b2", b1).id();
                copy(b1, b2, nodes, edges, members);
                active.add(b2);
            }
            if (c == 3 * commits / 5) {
                b3 = database.engine().createBranch("b3", Branch.MAIN).id();
                copy(Branch.MAIN, b3, nodes, edges, members);
                active.add(b3);
            }
            int on = active.get(random.nextInt(active.size()));
            List<Long> myNodes = nodes.computeIfAbsent(on, _ -> new ArrayList<>());
            List<Long> myEdges = edges.computeIfAbsent(on, _ -> new ArrayList<>());
            Map<Long, Set<Long>> myMembers = members.computeIfAbsent(on, _ -> new HashMap<>());
            int index = c;
            Set<Long> touched = new TreeSet<>();
            database.write(TxnOptions.defaults().onBranch(on), writer -> {
                int choice = myNodes.size() < 4 ? 0 : random.nextInt(14);
                if (choice < 2) {
                    long node = random.nextBoolean() ? writer.node("Person", "p" + index, Map.of("score", random.nextInt(5))) : writer.node("Place", "x" + index);
                    myNodes.add(node);
                    touched.add(node);
                } else if (choice < 4) {
                    long edge = writer.edge("Visit");
                    myEdges.add(edge);
                    myMembers.put(edge, new TreeSet<>());
                    touched.add(edge);
                } else if (choice < 8 && !myEdges.isEmpty()) {
                    long edge = myEdges.get(random.nextInt(myEdges.size()));
                    long node = myNodes.get(random.nextInt(myNodes.size()));
                    writer.add(edge, node, MemberSpec.PLAIN.withWeight(random.nextInt(3) / 2.0));
                    myMembers.get(edge).add(node);
                    touched.add(edge);
                    touched.add(node);
                } else if (choice < 10 && !myEdges.isEmpty()) {
                    long edge = myEdges.get(random.nextInt(myEdges.size()));
                    List<Long> current = List.copyOf(myMembers.get(edge));
                    if (!current.isEmpty()) {
                        long node = current.get(random.nextInt(current.size()));
                        writer.remove(edge, node);
                        myMembers.get(edge).remove(node);
                        touched.add(edge);
                        touched.add(node);
                    }
                } else if (choice < 12) {
                    long node = myNodes.get(random.nextInt(myNodes.size()));
                    if (writer.transaction().atom(node).orElseThrow().type() == database.read(reader -> reader.requireReadableType("Person").id())) {
                        writer.set(node, "score", new Value.Int(random.nextInt(5)));
                        touched.add(node);
                    }
                } else if (choice < 13 && !myEdges.isEmpty()) {
                    long edge = myEdges.remove(random.nextInt(myEdges.size()));
                    myMembers.remove(edge);
                    writer.delete(edge);
                    touched.add(edge);
                } else if (myNodes.size() > 4) {
                    long node = myNodes.remove(random.nextInt(myNodes.size()));
                    myMembers.values().forEach(set -> set.remove(node));
                    writer.delete(node);
                    touched.add(node);
                }
                return null;
            });
            long generation = database.engine().transactions().current().id();
            touchedBy.put(generation, touched);
            branchOf.put(generation, on);
        }
    }

    private static void copy(int from, int to, Map<Integer, List<Long>> nodes, Map<Integer, List<Long>> edges, Map<Integer, Map<Long, Set<Long>>> members) {
        nodes.put(to, new ArrayList<>(nodes.getOrDefault(from, List.of())));
        edges.put(to, new ArrayList<>(edges.getOrDefault(from, List.of())));
        Map<Long, Set<Long>> copied = new HashMap<>();
        members.getOrDefault(from, Map.of()).forEach((edge, set) -> copied.put(edge, new TreeSet<>(set)));
        members.put(to, copied);
    }

    private BranchRetrieval retrieval() {
        return BranchRetrieval.on(database, Budget.DEFAULT.start());
    }

    @Test
    void deltaAgreesWithSnapshotsOnBothRoutes() {
        history(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(21L), 500);
        for (int branch : List.of(b1, b2, b3)) {
            Branch found = branch(branch);
            List<Net> expected = difference(stateAt(found.baseGeneration(), found.parent()), head(branch));
            assertFalse(expected.isEmpty());
            for (Route route : List.of(Route.SNAPSHOT, Route.FEED)) {
                assertEquals(expected, retrieval().delta(branch, OptionalLong.empty(), route), "branch " + branch + " " + route);
            }
        }
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(22L);
        List<Long> generations = List.copyOf(touchedBy.keySet());
        for (int q = 0; q < 40; q++) {
            int branch = List.of(Branch.MAIN, b1, b2, b3).get(random.nextInt(4));
            long since = generations.get(random.nextInt(generations.size()));
            Lineage lineage = Lineage.of(database.engine().branches(), branch);
            if (branch != Branch.MAIN && since < branch(branch).baseGeneration() && lineage.branchAt(since) == branch) {
                continue;
            }
            List<Net> expected = difference(stateAt(since, lineage.branchAt(since)), head(branch));
            for (Route route : List.of(Route.SNAPSHOT, Route.FEED)) {
                assertEquals(expected, retrieval().delta(branch, OptionalLong.of(since), route), "since " + since + " on " + branch + " " + route);
            }
        }
    }

    @Test
    void compareClassifiesBothSidesFromTheCommonAncestor() {
        history(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(23L), 500);
        int[][] pairs = {{b1, b3}, {b2, b3}, {b2, b1}, {Branch.MAIN, b2}};
        for (int[] pair : pairs) {
            BranchRetrieval.AncestorPoint point = retrieval().ancestor(pair[0], pair[1]);
            Map<Key, Object> base = stateAt(point.generation() == Long.MAX_VALUE ? database.engine().transactions().current().id() : point.generation(), point.branch());
            Map<Key, Net> left = byKey(difference(base, head(pair[0])));
            Map<Key, Net> right = byKey(difference(base, head(pair[1])));
            Set<Key> keys = new TreeSet<>(BranchRetrieval.KEY_ORDER);
            keys.addAll(left.keySet());
            keys.addAll(right.keySet());
            List<String> expected = keys.stream().map(key -> {
                Side side = !left.containsKey(key) ? Side.ONLY_RIGHT : !right.containsKey(key) ? Side.ONLY_LEFT
                        : Objects.equals(left.get(key).after(), right.get(key).after()) ? Side.BOTH_SAME : Side.BOTH_DIFFERENT;
                return key + " " + side;
            }).toList();
            List<String> got = retrieval().compare(pair[0], pair[1], new Scope.Everything()).stream()
                    .map(compared -> compared.key() + " " + compared.side()).toList();
            assertEquals(expected, got, pair[0] + " vs " + pair[1]);
        }
    }

    private static Map<Key, Net> byKey(List<Net> nets) {
        Map<Key, Net> map = new TreeMap<>(BranchRetrieval.KEY_ORDER);
        nets.forEach(net -> map.put(net.key(), net));
        return map;
    }

    @Test
    void survivorsAreTheHeadsKeysUnchangedSinceTheFork() {
        history(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(24L), 400);
        for (int branch : List.of(b1, b2, b3)) {
            Branch found = branch(branch);
            Map<Key, Object> fork = stateAt(found.baseGeneration(), found.parent());
            Map<Key, Object> head = head(branch);
            List<Key> expected = head.entrySet().stream()
                    .filter(entry -> Objects.equals(fork.get(entry.getKey()), entry.getValue()))
                    .map(Map.Entry::getKey).toList();
            assertEquals(expected, retrieval().survivors(branch, new Scope.Everything()), "branch " + branch);
            List<Key> incidences = expected.stream().filter(key -> key instanceof Key.Member).toList();
            assertEquals(incidences, retrieval().survivors(branch, new Scope.Incidences()));
        }
    }

    @Test
    void nearestRanksBranchesByExactDistance() {
        history(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(25L), 400);
        List<Integer> all = List.of(Branch.MAIN, b1, b2, b3);
        for (Scope scope : List.of(new Scope.Everything(), new Scope.Incidences(),
                new Scope.OfType(0, database.read(reader -> reader.requireReadableType("Person").id())))) {
            for (int target : all) {
                Map<Key, Object> mine = head(target);
                List<Distance> expected = new ArrayList<>();
                for (int other : all) {
                    if (other == target) {
                        continue;
                    }
                    long distance = difference(mine, head(other)).stream().filter(net -> scoped(scope, net, mine, head(other)))
                            .mapToLong(net -> net.before().isPresent() && net.after().isPresent() ? 2 : 1).sum();
                    expected.add(new Distance(other, distance, 0));
                }
                expected.sort(Comparator.comparingLong(Distance::distance).thenComparingInt(Distance::branch));
                List<Distance> got = retrieval().nearest(target, all, scope, 2);
                assertEquals(expected.subList(0, 2).stream().map(d -> d.branch() + ":" + d.distance()).toList(),
                        got.stream().map(d -> d.branch() + ":" + d.distance()).toList(), scope + " target " + target);
                got.forEach(d -> assertTrue(d.lowerBound() <= d.distance()));
            }
        }
    }

    private static boolean scoped(Scope scope, Net net, Map<Key, Object> left, Map<Key, Object> right) {
        return switch (scope) {
            case Scope.Everything _ -> true;
            case Scope.Incidences _ -> net.key() instanceof Key.Member;
            case Scope.OfType(int tenant, int type) -> {
                Object record = left.getOrDefault(new Key.Atom(net.key().atom()), right.get(new Key.Atom(net.key().atom())));
                yield record instanceof AtomValue value && value.tenant() == tenant && value.type() == type;
            }
            case Scope.Around(long atom) -> net.key().atom() == atom;
        };
    }

    @Test
    void afterListsLaterCommitsOnTheBranchThatTouchTheSameAtoms() {
        history(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(26L), 300);
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(27L);
        List<Long> generations = List.copyOf(touchedBy.keySet());
        Set<Long> logged = new TreeSet<>();
        try (var events = database.engine().feed().replay(0)) {
            events.forEach(event -> logged.add(event.generation()));
        }
        int checked = 0;
        for (int q = 0; q < 60; q++) {
            long g = generations.get(random.nextInt(generations.size()));
            if (touchedBy.get(g).isEmpty() || !logged.contains(g)) {
                assertEquals(List.of(), logged.contains(g) ? List.of() : retrieval().after(branchOf.get(g), g, 50));
                continue;
            }
            int branch = branchOf.get(g);
            long window = 5 + random.nextInt(80);
            Lineage lineage = Lineage.of(database.engine().branches(), branch);
            Set<Long> atoms = touchedBy.get(g);
            List<Long> expected = touchedBy.entrySet().stream()
                    .filter(entry -> entry.getKey() > g && entry.getKey() <= g + window && logged.contains(entry.getKey()))
                    .filter(entry -> lineage.includes(branchOf.get(entry.getKey()), entry.getKey()))
                    .filter(entry -> entry.getValue().stream().anyMatch(atoms::contains))
                    .map(Map.Entry::getKey).toList();
            List<Long> got = retrieval().after(branch, g, window).stream().map(Succession::generation).toList();
            assertTrue(got.containsAll(expected), "generation " + g + " got " + got + " expected " + expected);
            checked++;
        }
        assertTrue(checked > 30);
    }

    @Test
    void deltaFailsClearlyWhenTheAncestorIsGone() {
        database.close();
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults()
                .withPageSize(4096).withHistoryLimit(2).withWalSegmentBytes(4096).withFeedRetention(5)));
        history(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(28L), 300);
        database.engine().checkpoint();
        var refused = assertThrows(io.hstore.engine.HStoreException.class, () -> retrieval().delta(b1, OptionalLong.empty(), Route.AUTO));
        assertTrue(refused.getMessage().contains("neither retained nor covered"), refused.getMessage());
    }

    @Test
    void statementsRunForGlobalAdmins() {
        try (Session admin = new Session(database)) {
            admin.executeAll("""
                    INSERT NODE Person 'a' {score: 1} AS $a;
                    INSERT EDGE Visit MEMBERS ($a) AS $v;
                    CREATE BRANCH side;
                    USE BRANCH side;
                    SET $a.score = 2;
                    INSERT NODE Place 'p' AS $p;
                    ADD $p TO $v;
                    USE MAIN;
                    CREATE BRANCH other;
                    """);
            assertEquals(3, admin.execute("BRANCH DELTA side").rows().size());
            assertEquals(List.of("key", "before", "after"), admin.execute("BRANCH DELTA side").columns());
            assertEquals(1, admin.execute("BRANCH DELTA side SCOPE INCIDENCES").rows().size());
            assertEquals(List.of("key", "side", "left", "right"), admin.execute("COMPARE BRANCH side AND other").columns());
            assertEquals(3, admin.execute("COMPARE BRANCH side AND other").rows().size());
            assertTrue(admin.execute("BRANCH SURVIVORS side").rows().size() >= 2);
            assertEquals(List.of("branch", "distance", "lower_bound"), admin.execute("NEAREST BRANCH TO side TOP 2").columns());
            assertEquals(2, admin.execute("NEAREST BRANCH TO side TOP 2").rows().size());
            assertTrue(admin.execute("BRANCH SURVIVORS side SCOPE AROUND $a LIMIT 1").rows().size() <= 1);
        }
    }
}
