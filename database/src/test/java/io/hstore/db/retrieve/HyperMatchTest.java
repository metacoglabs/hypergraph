// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.MemberSpec;
import io.hstore.db.Reader;
import io.hstore.db.hora.Budget;
import io.hstore.db.query.Session;
import io.hstore.db.retrieve.HyperMatch.Mode;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HyperMatchTest {

    private static final Comparator<long[]> TUPLES = Arrays::compare;

    @TempDir
    Path directory;

    HypergraphDatabase database;

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        try (Session admin = new Session(database)) {
            admin.executeAll("""
                    CREATE NODE TYPE A;
                    CREATE NODE TYPE B;
                    CREATE NODE TYPE C;
                    CREATE SET EDGE TYPE R;
                    CREATE SET EDGE TYPE S;
                    """);
        }
    }

    @AfterEach
    void close() {
        database.close();
    }

    private record Data(Map<Long, Integer> labels, Map<Long, Integer> types, Map<Long, Set<Long>> members) {
    }

    private void runningExample(Map<String, Long> ids) {
        database.write(writer -> {
            String[] labels = {"A", "C", "A", "A", "B", "C", "A"};
            for (int i = 0; i < labels.length; i++) {
                ids.put("v" + i, writer.node(labels[i], "v" + i));
            }
            int[][] edges = {{2, 4}, {4, 6}, {0, 1, 2}, {3, 5, 6}, {0, 1, 4, 6}, {2, 3, 4, 5}};
            for (int e = 0; e < edges.length; e++) {
                long edge = writer.edge("R");
                ids.put("e" + (e + 1), edge);
                for (int v : edges[e]) {
                    writer.add(edge, ids.get("v" + v), MemberSpec.PLAIN);
                }
            }
            return null;
        });
    }

    private Data snapshot() {
        return database.read(reader -> {
            Map<Long, Integer> labels = new HashMap<>();
            Map<Long, Integer> types = new HashMap<>();
            Map<Long, Set<Long>> members = new HashMap<>();
            for (String type : List.of("A", "B", "C")) {
                reader.atomsOfType(reader.requireReadableType(type).id()).forEach(atom -> labels.put(atom, reader.requireReadableType(type).id()));
            }
            for (String type : List.of("R", "S")) {
                int id = reader.requireReadableType(type).id();
                reader.atomsOfType(id).forEach(edge -> {
                    types.put(edge, id);
                    Set<Long> set = new TreeSet<>();
                    reader.forEachMemberId(edge, set::add);
                    members.put(edge, set);
                });
            }
            return new Data(labels, types, members);
        });
    }

    private int type(String name) {
        return database.read(reader -> reader.requireReadableType(name).id());
    }

    @Test
    void runningExampleHasTwoEmbeddings() {
        Map<String, Long> ids = new HashMap<>();
        runningExample(ids);
        HyperQuery query = HyperQuery.builder()
                .vertex("u0", type("A")).vertex("u1", type("C")).vertex("u2", type("A")).vertex("u3", type("A")).vertex("u4", type("B"))
                .edge("q0", type("R"), "u2", "u4")
                .edge("q1", type("R"), "u0", "u1", "u2")
                .edge("q2", type("R"), "u0", "u1", "u3", "u4")
                .build();
        for (Mode mode : Mode.values()) {
            List<long[]> tuples = database.read(reader -> HyperMatch.on(reader).withMode(mode).tuples(query, Budget.DEFAULT.start(), Long.MAX_VALUE));
            assertEquals(List.of(List.of(ids.get("e1"), ids.get("e3"), ids.get("e5")), List.of(ids.get("e2"), ids.get("e4"), ids.get("e6"))),
                    tuples.stream().map(tuple -> Arrays.stream(tuple).boxed().toList()).toList(), mode.name());
            assertEquals(2L, (long) database.read(reader -> HyperMatch.on(reader).withMode(mode).count(query, Budget.DEFAULT.start())));
        }
    }

    private static List<long[]> bruteForce(Data data, HyperQuery query) {
        long[] image = new long[query.vertices().size()];
        Set<Long> used = new HashSet<>();
        TreeSet<long[]> tuples = new TreeSet<>(TUPLES);
        Map<Set<Long>, List<Long>> byMembers = new HashMap<>();
        data.members().forEach((edge, members) -> byMembers.computeIfAbsent(members, _ -> new ArrayList<>()).add(edge));
        List<Long> vertices = new ArrayList<>(data.labels().keySet());
        vertices.sort(Long::compare);
        int[] order = query.edges().stream().flatMapToInt(edge -> Arrays.stream(edge.vertices())).distinct().toArray();
        assign(0, order, image, used, vertices, data, query, byMembers, tuples);
        return List.copyOf(tuples);
    }

    private static void assign(int step, int[] order, long[] image, Set<Long> used, List<Long> vertices, Data data, HyperQuery query,
                               Map<Set<Long>, List<Long>> byMembers, TreeSet<long[]> tuples) {
        if (step == order.length) {
            List<List<Long>> choices = new ArrayList<>();
            for (HyperQuery.Edge edge : query.edges()) {
                Set<Long> members = new TreeSet<>();
                for (int v : edge.vertices()) {
                    members.add(image[v]);
                }
                choices.add(byMembers.getOrDefault(members, List.of()).stream().filter(e -> data.types().get(e) == edge.edgeType()).toList());
            }
            product(choices, 0, new long[choices.size()], tuples);
            return;
        }
        int u = order[step];
        Long anchor = query.anchors().get(u);
        for (long v : vertices) {
            if (used.contains(v) || data.labels().get(v) != query.vertices().get(u).label() || (anchor != null && anchor != v)) {
                continue;
            }
            image[u] = v;
            used.add(v);
            if (edgesClosedSoFarExist(step, order, image, data, query, byMembers)) {
                assign(step + 1, order, image, used, vertices, data, query, byMembers, tuples);
            }
            used.remove(v);
        }
    }

    private static boolean edgesClosedSoFarExist(int step, int[] order, long[] image, Data data, HyperQuery query,
                                                 Map<Set<Long>, List<Long>> byMembers) {
        int[] position = new int[image.length];
        for (int i = 0; i < order.length; i++) {
            position[order[i]] = i;
        }
        for (HyperQuery.Edge edge : query.edges()) {
            if (Arrays.stream(edge.vertices()).map(v -> position[v]).max().orElseThrow() != step) {
                continue;
            }
            Set<Long> members = new TreeSet<>();
            for (int v : edge.vertices()) {
                members.add(image[v]);
            }
            if (byMembers.getOrDefault(members, List.of()).stream().noneMatch(e -> data.types().get(e) == edge.edgeType())) {
                return false;
            }
        }
        return true;
    }

    private static void product(List<List<Long>> choices, int i, long[] tuple, TreeSet<long[]> tuples) {
        if (i == choices.size()) {
            if (Arrays.stream(tuple).distinct().count() == tuple.length) {
                tuples.add(tuple.clone());
            }
            return;
        }
        for (long edge : choices.get(i)) {
            tuple[i] = edge;
            product(choices, i + 1, tuple, tuples);
        }
    }

    private Data randomData(RandomGenerator random) {
        database.write(writer -> {
            List<Long> nodes = new ArrayList<>();
            String[] labels = {"A", "A", "B", "C"};
            for (int i = 0; i < 26; i++) {
                nodes.add(writer.node(labels[random.nextInt(labels.length)], "n" + i));
            }
            for (int e = 0; e < 70; e++) {
                long edge = writer.edge(random.nextInt(4) == 0 ? "S" : "R");
                int arity = 1 + random.nextInt(4);
                Set<Long> chosen = new TreeSet<>();
                while (chosen.size() < arity) {
                    chosen.add(nodes.get(random.nextInt(nodes.size())));
                }
                for (long member : chosen) {
                    writer.add(edge, member, MemberSpec.PLAIN);
                }
            }
            long duplicate = writer.edge("R");
            writer.add(duplicate, nodes.get(0), MemberSpec.PLAIN);
            writer.add(duplicate, nodes.get(1), MemberSpec.PLAIN);
            long twin = writer.edge("R");
            writer.add(twin, nodes.get(0), MemberSpec.PLAIN);
            writer.add(twin, nodes.get(1), MemberSpec.PLAIN);
            return null;
        });
        return snapshot();
    }

    private static HyperQuery sampleQuery(RandomGenerator random, Data data, boolean anchored) {
        List<Long> edges = new ArrayList<>(data.members().keySet());
        edges.sort(Long::compare);
        List<Long> picked = new ArrayList<>();
        Set<Long> touched = new TreeSet<>();
        long first = edges.get(random.nextInt(edges.size()));
        picked.add(first);
        touched.addAll(data.members().get(first));
        int size = 1 + random.nextInt(4);
        for (int tries = 0; picked.size() < size && tries < 200; tries++) {
            long next = edges.get(random.nextInt(edges.size()));
            if (!picked.contains(next) && data.members().get(next).stream().anyMatch(touched::contains)) {
                picked.add(next);
                touched.addAll(data.members().get(next));
            }
        }
        Map<Long, String> names = new LinkedHashMap<>();
        HyperQuery.Builder builder = HyperQuery.builder();
        for (long vertex : touched) {
            String name = "u" + names.size();
            names.put(vertex, name);
            builder.vertex(name, data.labels().get(vertex));
        }
        for (int i = 0; i < picked.size(); i++) {
            long edge = picked.get(i);
            builder.edge("q" + i, data.types().get(edge), data.members().get(edge).stream().map(names::get).toArray(String[]::new));
        }
        if (anchored) {
            long vertex = List.copyOf(touched).get(random.nextInt(touched.size()));
            builder.anchor(names.get(vertex), vertex);
        }
        return builder.build();
    }

    private void compareWithBruteForce(Mode mode, int threads, boolean anchored, long seed) {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(seed);
        Data data = randomData(random);
        int nonEmpty = 0;
        for (int q = 0; q < 120; q++) {
            HyperQuery query = sampleQuery(random, data, anchored);
            List<long[]> want = bruteForce(data, query);
            nonEmpty += want.isEmpty() ? 0 : 1;
            List<long[]> got = database.read(reader -> HyperMatch.on(reader).withMode(mode).withThreads(threads)
                    .tuples(query, Budget.DEFAULT.start(), Long.MAX_VALUE));
            assertEquals(want.stream().map(Arrays::toString).toList(), got.stream().map(Arrays::toString).toList(), query.toString());
            assertEquals(want.size(), (long) database.read(reader -> HyperMatch.on(reader).withMode(mode).withThreads(threads)
                    .count(query, Budget.DEFAULT.start())), query.toString());
        }
        assertTrue(nonEmpty > 60, "only " + nonEmpty + " queries had matches");
    }

    @Test
    void hgmatchAgreesWithBruteForce() {
        compareWithBruteForce(Mode.HGMATCH, 1, false, 1L);
    }

    @Test
    void machAgreesWithBruteForce() {
        compareWithBruteForce(Mode.MACH, 1, false, 2L);
    }

    @Test
    void parallelSearchAgreesWithBruteForce() {
        compareWithBruteForce(Mode.AUTO, 4, false, 3L);
    }

    @Test
    void parallelSearchSplitsLargeFirstSteps() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(6L);
        database.write(writer -> {
            List<Long> as = new ArrayList<>();
            List<Long> bs = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                as.add(writer.node("A", "a" + i));
                bs.add(writer.node("B", "b" + i));
            }
            for (int e = 0; e < 400; e++) {
                long edge = writer.edge("R");
                writer.add(edge, as.get(random.nextInt(40)), MemberSpec.PLAIN);
                writer.add(edge, bs.get(random.nextInt(40)), MemberSpec.PLAIN);
            }
            return null;
        });
        Data data = snapshot();
        HyperQuery query = HyperQuery.builder().vertex("a", type("A")).vertex("b", type("B")).vertex("c", type("A"))
                .edge("q0", type("R"), "a", "b").edge("q1", type("R"), "c", "b").build();
        List<String> want = bruteForce(data, query).stream().map(Arrays::toString).toList();
        assertTrue(want.size() > 500, "only " + want.size() + " results");
        for (int threads : List.of(1, 4)) {
            List<String> got = database.read(reader -> HyperMatch.on(reader).withThreads(threads)
                    .tuples(query, Budget.DEFAULT.start(), Long.MAX_VALUE)).stream().map(Arrays::toString).toList();
            assertEquals(want, got, threads + " threads");
            assertEquals(want.size(), (long) database.read(reader -> HyperMatch.on(reader).withThreads(threads).count(query, Budget.DEFAULT.start())));
        }
    }

    @Test
    void anchoredQueriesAgreeWithBruteForce() {
        compareWithBruteForce(Mode.AUTO, 1, true, 4L);
    }

    @Test
    void interchangeableVerticesMultiplyBindings() {
        long edge = database.write(writer -> {
            long created = writer.edge("R");
            writer.add(created, writer.node("A", "x"), MemberSpec.PLAIN);
            writer.add(created, writer.node("A", "y"), MemberSpec.PLAIN);
            return created;
        });
        HyperQuery query = HyperQuery.builder().vertex("a", type("A")).vertex("b", type("A")).edge("q", type("R"), "a", "b").build();
        database.read(reader -> {
            HyperMatch match = HyperMatch.on(reader);
            List<long[]> tuples = match.tuples(query, Budget.DEFAULT.start(), Long.MAX_VALUE);
            assertEquals(1, tuples.size());
            assertEquals(edge, tuples.getFirst()[0]);
            List<long[]> bindings = match.bindings(query, tuples.getFirst());
            assertEquals(2, bindings.size());
            assertTrue(bindings.get(0)[0] != bindings.get(0)[1]);
            assertEquals(bindings.get(0)[0], bindings.get(1)[1]);
            return null;
        });
    }

    @Test
    void invalidQueriesAreRejected() {
        int a = type("A");
        int r = type("R");
        assertThrows(HStoreException.class, () -> HyperQuery.builder().vertex("a", a).vertex("b", a).vertex("c", a).vertex("d", a)
                .edge("q0", r, "a", "b").edge("q1", r, "c", "d").build());
        assertThrows(HStoreException.class, () -> HyperQuery.builder().vertex("a", a).vertex("b", a).edge("q0", r, "a").build());
        assertThrows(HStoreException.class, () -> HyperQuery.builder().vertex("a", a).edge("q0", r, "a", "a").build());
        HyperQuery.Builder big = HyperQuery.builder();
        for (int i = 0; i <= 65; i++) {
            big.vertex("v" + i, a);
        }
        for (int i = 0; i < 65; i++) {
            big.edge("q" + i, r, "v" + i, "v" + (i + 1));
        }
        assertThrows(HStoreException.class, big::build);
    }

    @Test
    void overBudgetFailsWholeNotPartial() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(5L);
        Data data = randomData(random);
        HyperQuery query = sampleQuery(random, data, false);
        database.read(reader -> {
            assertThrows(HStoreException.class, () -> HyperMatch.on(reader).count(query, Budget.DEFAULT.withRows(1).start()));
            return null;
        });
    }

    @Test
    void hypermatchStatementCountsAndReturnsTuples() {
        Map<String, Long> ids = new HashMap<>();
        runningExample(ids);
        try (Session session = new Session(database)) {
            String query = "HYPERMATCH q0:R(u2:A, u4:B), q1:R(u0:A, u1:C, u2), q2:R(u0, u1, u3:A, u4)";
            assertEquals("2", session.execute(query + " COUNT").rows().getFirst().getFirst().render());
            var rows = session.execute(query);
            assertEquals(List.of("q0", "q1", "q2"), rows.columns());
            assertEquals(2, rows.rows().size());
            var bound = session.execute(query + " BINDINGS LIMIT 1");
            assertEquals(List.of("q0", "q1", "q2", "u2", "u4", "u0", "u1", "u3"), bound.columns());
            assertEquals(1, bound.rows().size());
            var anchored = session.execute(query + " WHERE u0 = @A:'v3'");
            assertEquals(1, anchored.rows().size());
            assertTrue(anchored.rows().getFirst().getFirst().render().startsWith("@" + ids.get("e2")), anchored.rows().toString());
            assertTrue(session.execute("EXPLAIN " + query).message().contains("HyperMatch"));
        }
    }
}
