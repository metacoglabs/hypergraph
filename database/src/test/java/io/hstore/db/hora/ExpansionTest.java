// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Member;
import io.hstore.db.MemberSpec;
import io.hstore.db.Reader;
import io.hstore.db.hora.Expansion.Accepted;
import io.hstore.db.hora.Expansion.Model;
import io.hstore.db.hora.Expansion.Relevance;
import io.hstore.db.hora.Expansion.Result;
import io.hstore.db.query.Session;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.topology.Validity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpansionTest {

    private static final List<String> ROLES = List.of("actor", "participant", "decision");

    @TempDir
    Path directory;

    HypergraphDatabase database;
    List<Long> people = new ArrayList<>();

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        try (Session admin = new Session(database)) {
            admin.executeAll("""
                    CREATE NODE TYPE Person;
                    CREATE SET EDGE TYPE Meeting ROLES (actor, participant, decision);
                    CREATE SET EDGE TYPE Project ROLES (actor, participant, decision);
                    """);
        }
    }

    @AfterEach
    void close() {
        database.close();
    }

    private void randomGraph(RandomGenerator random) {
        database.write(writer -> {
            for (int i = 0; i < 50; i++) {
                people.add(writer.node("Person", "p" + i));
            }
            long hub = writer.node("Person", "hub");
            people.add(hub);
            for (int e = 0; e < 120; e++) {
                long edge = writer.edge(random.nextInt(3) == 0 ? "Project" : "Meeting");
                if (e % 3 == 0) {
                    writer.add(edge, hub, MemberSpec.roles(ROLES.get(random.nextInt(3))).withWeight(0.1 + random.nextInt(10) / 10.0));
                }
                for (int m = 0; m < 1 + random.nextInt(6); m++) {
                    Validity validity = random.nextInt(4) == 0 ? new Validity(random.nextInt(10), 10 + random.nextInt(10)) : Validity.ALWAYS;
                    writer.add(edge, people.get(random.nextInt(people.size())), MemberSpec.roles(ROLES.get(random.nextInt(3)))
                            .withWeight(random.nextInt(4) == 0 ? 0 : 0.1 + random.nextInt(10) / 10.0).withValidity(validity));
                }
            }
            return null;
        });
    }

    private static double factor(Map<String, Double> factors, List<String> roles) {
        if (factors.isEmpty() || roles.isEmpty()) {
            return 1.0;
        }
        return roles.stream().mapToDouble(role -> factors.getOrDefault(role, 1.0)).max().orElse(1.0);
    }

    private static boolean valid(Member member, Model model) {
        return model.validAt().stream().allMatch(t -> member.validity().from() <= t && t < member.validity().to());
    }

    private static Map<Long, Double> exactValues(Reader reader, Map<Long, Double> seeds, Model model, Relevance relevance) {
        Map<Long, List<Member>> members = new TreeMap<>();
        Map<Long, Integer> types = new HashMap<>();
        Map<Long, List<long[]>> incident = new HashMap<>();
        for (String name : List.of("Meeting", "Project")) {
            int type = reader.requireReadableType(name).id();
            reader.atomsOfType(type).forEach(edge -> {
                types.put(edge, type);
                members.put(edge, reader.members(edge).toList());
            });
        }
        Map<Long, Double> atoms = new HashMap<>(seeds);
        Map<Long, Double> edges = new TreeMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var edge : members.entrySet()) {
                long e = edge.getKey();
                int type = types.get(e);
                if (!model.typeFactors().isEmpty() && !model.typeFactors().containsKey(type)) {
                    continue;
                }
                OptionalDouble r = relevance.score(e);
                double gate = model.gate() + (1 - model.gate()) * (r.isEmpty() ? 0 : Math.max(0, Math.min(1, r.getAsDouble())));
                double best = edges.getOrDefault(e, 0.0);
                for (Member member : edge.getValue()) {
                    Double value = atoms.get(member.atom());
                    if (value == null || !valid(member, model)) {
                        continue;
                    }
                    double hub = Math.pow(Math.max(1, reader.degree(member.atom())), -model.hubDamping());
                    double candidate = value * hub * factor(model.exit(), member.roles()) * model.typeFactors().getOrDefault(type, 1.0) * gate;
                    if (candidate > best + 1e-15) {
                        best = candidate;
                    }
                }
                if (best > edges.getOrDefault(e, 0.0) + 1e-15) {
                    edges.put(e, best);
                    changed = true;
                }
            }
            for (var edge : edges.entrySet()) {
                List<Member> list = members.get(edge.getKey());
                double weightMax = list.stream().mapToDouble(Member::weight).max().orElse(0);
                if (weightMax <= 0) {
                    continue;
                }
                double damping = Math.pow(Math.max(1, list.size()), -model.edgeDamping());
                for (Member member : list) {
                    if (!valid(member, model)) {
                        continue;
                    }
                    double candidate = edge.getValue() * factor(model.entry(), member.roles()) * Math.max(0, member.weight()) / weightMax * damping;
                    if (candidate > atoms.getOrDefault(member.atom(), 0.0) + 1e-15) {
                        atoms.put(member.atom(), candidate);
                        changed = true;
                    }
                }
            }
        }
        edges.values().removeIf(value -> value <= 0);
        return edges;
    }

    @Test
    void returnsTheExactTopEdgesConnectedToTheSeeds() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(51L);
        randomGraph(random);
        int meeting = database.read(reader -> reader.requireReadableType("Meeting").id());
        int nonEmpty = 0;
        for (int q = 0; q < 80; q++) {
            Map<Long, Double> seeds = new TreeMap<>();
            seeds.put(people.get(random.nextInt(people.size())), 1.0);
            if (random.nextBoolean()) {
                seeds.put(people.get(random.nextInt(people.size())), 0.5);
            }
            Map<String, Double> exit = random.nextBoolean() ? Map.of(ROLES.get(random.nextInt(3)), 0.3) : Map.of();
            Map<String, Double> entry = random.nextBoolean() ? Map.of(ROLES.get(random.nextInt(3)), 0.5) : Map.of();
            Model model = new Model(random.nextInt(3) == 0 ? Map.of(meeting, 0.8) : Map.of(), exit, entry,
                    random.nextBoolean() ? OptionalLong.of(random.nextInt(20)) : OptionalLong.empty(),
                    random.nextInt(3) * 0.5, random.nextInt(3) * 0.5, random.nextBoolean() ? 1.0 : 0.2);
            int modulus = 2 + random.nextInt(7);
            Relevance relevance = new Relevance() {
                public double bound(long edge) {
                    return 1;
                }

                public OptionalDouble score(long edge) {
                    return edge % 11 == 0 ? OptionalDouble.empty() : OptionalDouble.of((edge % modulus) / (double) (modulus - 1));
                }
            };
            int k = 1 + random.nextInt(25);
            Map<Long, Double> exact = database.read(reader -> exactValues(reader, seeds, model, relevance));
            Result result = database.read(reader -> Expansion.on(reader).run(seeds, model, relevance, k, 0, 1_000_000, Budget.UNBOUNDED.start()));
            List<Map.Entry<Long, Double>> ranked = new ArrayList<>(exact.entrySet());
            ranked.sort(Map.Entry.<Long, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
            List<Map.Entry<Long, Double>> want = ranked.subList(0, Math.min(k, ranked.size()));
            assertEquals(want.size(), result.edges().size(), "count");
            for (int i = 0; i < want.size(); i++) {
                assertEquals(want.get(i).getValue(), result.edges().get(i).value(), 1e-12, "value at " + i);
            }
            Set<Long> returned = new HashSet<>();
            Set<Long> reachable = new HashSet<>(seeds.keySet());
            for (Accepted accepted : result.edges()) {
                assertTrue(reachable.contains(accepted.via()), "connected through " + accepted.via());
                assertTrue(accepted.parent() < 0 || returned.contains(accepted.parent()), "parent returned first");
                returned.add(accepted.edge());
                database.read(reader -> {
                    reader.members(accepted.edge()).forEach(member -> reachable.add(member.atom()));
                    return null;
                });
            }
            for (var value : exact.entrySet()) {
                if (!returned.contains(value.getKey())) {
                    assertTrue(value.getValue() <= result.bound() + 1e-12, "bound covers " + value.getKey());
                }
            }
            nonEmpty += want.isEmpty() ? 0 : 1;
        }
        assertTrue(nonEmpty > 60);
    }

    @Test
    void budgetsStillReturnAnExactPrefixWithAValidBound() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(52L);
        randomGraph(random);
        Map<Long, Double> seeds = Map.of(people.get(0), 1.0);
        Map<Long, Double> exact = database.read(reader -> exactValues(reader, seeds, Model.plain(), Relevance.NONE));
        List<Double> ranked = exact.values().stream().sorted(java.util.Comparator.reverseOrder()).toList();
        for (int rows = 3; rows < 300; rows += 11) {
            int budget = rows;
            Result cut = database.read(reader -> Expansion.on(reader).run(seeds, Model.plain(), Relevance.NONE, 50, 0, 1_000_000,
                    Budget.UNBOUNDED.withRows(budget).start()));
            for (int i = 0; i < cut.edges().size(); i++) {
                assertEquals(ranked.get(i), cut.edges().get(i).value(), 1e-12);
            }
            Set<Long> returned = new HashSet<>(cut.edges().stream().map(Accepted::edge).toList());
            exact.forEach((edge, value) -> {
                if (!returned.contains(edge)) {
                    assertTrue(value <= cut.bound() + 1e-12, budget + " rows: bound " + cut.bound() + " below " + value);
                }
            });
        }
    }

    @Test
    void aBudgetCutJustAfterAcceptingAnEdgeStillBoundsWhatLiesBeyondIt() {
        long[] ids = database.write(writer -> {
            long a = writer.node("Person", "a");
            long b = writer.node("Person", "b");
            long c = writer.node("Person", "c");
            long first = writer.edge("Meeting");
            writer.add(first, a, MemberSpec.PLAIN);
            writer.add(first, b, MemberSpec.PLAIN);
            long second = writer.edge("Meeting");
            writer.add(second, b, MemberSpec.PLAIN);
            writer.add(second, c, MemberSpec.PLAIN);
            return new long[]{a, first, second};
        });
        Map<Long, Double> seeds = Map.of(ids[0], 1.0);
        Map<Long, Double> exact = database.read(reader -> exactValues(reader, seeds, Model.plain(), Relevance.NONE));
        for (int rows = 1; rows <= 30; rows++) {
            int budget = rows;
            Result cut = database.read(reader -> Expansion.on(reader).run(seeds, Model.plain(), Relevance.NONE, 10, 0, 1_000_000,
                    Budget.UNBOUNDED.withRows(budget).start()));
            Set<Long> returned = new HashSet<>(cut.edges().stream().map(Accepted::edge).toList());
            exact.forEach((edge, value) -> {
                if (!returned.contains(edge)) {
                    assertTrue(value <= cut.bound() + 1e-12, budget + " rows: bound " + cut.bound() + " below " + value);
                }
            });
        }
    }

    @Test
    void tiesAtTheCutGoToTheSmallerId() {
        long[] ids = database.write(writer -> {
            long a = writer.node("Person", "tie-a");
            long b = writer.node("Person", "tie-b");
            long small = writer.edge("Meeting");
            writer.add(small, b, MemberSpec.PLAIN);
            long large = writer.edge("Meeting");
            writer.add(large, a, MemberSpec.PLAIN);
            return new long[]{a, b, small, large};
        });
        Result result = database.read(reader -> Expansion.on(reader).run(Map.of(ids[0], 1.0, ids[1], 1.0), Model.plain(), Relevance.NONE, 1, 0, 1_000,
                Budget.UNBOUNDED.start()));
        assertEquals(List.of(ids[2]), result.edges().stream().map(Accepted::edge).toList());
    }

    @Test
    void truncatedFrontiersStayExactAboveTheDroppedBound() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(53L);
        randomGraph(random);
        Map<Long, Double> seeds = Map.of(people.getLast(), 1.0);
        Map<Long, Double> exact = database.read(reader -> exactValues(reader, seeds, Model.plain(), Relevance.NONE));
        List<Double> ranked = exact.values().stream().sorted(java.util.Comparator.reverseOrder()).toList();
        Result cut = database.read(reader -> Expansion.on(reader).run(seeds, Model.plain(), Relevance.NONE, 60, 0, 8, Budget.UNBOUNDED.start()));
        assertTrue(cut.droppedBound() > 0, cut.toString());
        for (int i = 0; i < cut.edges().size() && cut.edges().get(i).value() > cut.droppedBound(); i++) {
            assertEquals(ranked.get(i), cut.edges().get(i).value(), 1e-12);
        }
    }

    @Test
    void aRelevanceScorerOutOfBudgetEndsTheRunCleanly() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(54L);
        randomGraph(random);
        int[] calls = {0};
        Relevance tired = new Relevance() {
            public double bound(long edge) {
                if (++calls[0] > 20) {
                    throw io.hstore.engine.HStoreException.limit("out of rows");
                }
                return 1;
            }

            public OptionalDouble score(long edge) {
                return OptionalDouble.of(0.5);
            }
        };
        Result result = database.read(reader -> Expansion.on(reader).run(Map.of(people.getLast(), 1.0), Model.plain(), tired, 50, 0, 1_000_000,
                Budget.UNBOUNDED.start()));
        assertEquals(Expansion.Stop.BUDGET, result.stop());
    }

    @Test
    void aHyperedgeSeedIsReturnedAndExpanded() {
        long[] ids = database.write(writer -> {
            long a = writer.node("Person", "a");
            long b = writer.node("Person", "b");
            long meeting = writer.edge("Meeting");
            writer.add(meeting, a, MemberSpec.PLAIN);
            writer.add(meeting, b, MemberSpec.PLAIN);
            long project = writer.edge("Project");
            writer.add(project, b, MemberSpec.PLAIN);
            return new long[]{meeting, project};
        });
        Result result = database.read(reader -> Expansion.on(reader).run(Map.of(ids[0], 1.0), Model.plain(), Relevance.NONE, 5, 0, 1_000,
                Budget.UNBOUNDED.start()));
        assertEquals(List.of(ids[0], ids[1]), result.edges().stream().map(Accepted::edge).toList());
    }

    @Test
    void expandFromStatementReturnsAConnectedEvidenceTree() {
        try (Session session = new Session(database)) {
            session.executeAll("""
                    INSERT NODE Person 'alice' AS $a;
                    INSERT NODE Person 'bob' AS $b;
                    INSERT NODE Person 'carol' AS $c;
                    INSERT EDGE Meeting MEMBERS ($a AS actor, $b AS participant) AS $m;
                    INSERT EDGE Project MEMBERS ($b AS actor, $c AS decision) AS $p;
                    """);
            var rows = session.execute("EXPAND FROM $a TOP 5");
            assertEquals(List.of("edge", "value", "relevance", "via", "parent"), rows.columns());
            assertEquals(2, rows.rows().size());
            assertTrue(rows.rows().get(1).get(4).render().startsWith("@" + session.variables().get("m")), rows.rows().toString());
            var blocked = session.execute("EXPAND FROM $a TOWARDS (RANK EDGE e:Project LIKE ($c AS decision REQUIRED) TOP 1) GATE 0 TOP 5");
            assertEquals(0, blocked.rows().size(), blocked.rows().toString());
            var towards = session.execute("EXPAND FROM $a TOWARDS (RANK EDGE e:Project LIKE ($c AS decision REQUIRED) TOP 1) GATE 0.5 TOP 5");
            assertEquals(2, towards.rows().size(), towards.rows().toString());
            assertEquals("1", towards.rows().get(1).get(2).render(), towards.rows().toString());
            assertTrue(towards.message().contains("value at most"), towards.message());
        }
    }
}
