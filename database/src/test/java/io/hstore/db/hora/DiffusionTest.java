// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Member;
import io.hstore.db.MemberSpec;
import io.hstore.db.Reader;
import io.hstore.db.evidence.EvidencePolicy;
import io.hstore.db.hora.Diffusion.Model;
import io.hstore.db.hora.Diffusion.Params;
import io.hstore.db.hora.Diffusion.Result;
import io.hstore.db.hora.Diffusion.RoleFactors;
import io.hstore.db.hora.Diffusion.Scored;
import io.hstore.db.query.Session;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.topology.Validity;

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
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffusionTest {

    private static final List<String> ROLES = List.of("actor", "participant", "decision", "evidence");

    @TempDir
    Path directory;

    HypergraphDatabase database;
    List<Long> people = new ArrayList<>();
    List<Long> edges = new ArrayList<>();

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        try (Session admin = new Session(database)) {
            admin.executeAll("""
                    CREATE NODE TYPE Person;
                    CREATE SET EDGE TYPE Meeting ROLES (actor, participant, decision, evidence);
                    CREATE SET EDGE TYPE Project ROLES (actor, participant, decision, evidence);
                    """);
        }
    }

    @AfterEach
    void close() {
        database.close();
    }

    private void randomGraph(RandomGenerator random) {
        database.write(writer -> {
            for (int i = 0; i < 40; i++) {
                people.add(writer.node("Person", "p" + i));
            }
            for (int e = 0; e < 60; e++) {
                long edge = writer.edge(random.nextInt(3) == 0 ? "Project" : "Meeting");
                edges.add(edge);
                int size = 1 + random.nextInt(5);
                for (int m = 0; m < size; m++) {
                    long member = random.nextInt(8) == 0 && e > 0 ? edges.get(random.nextInt(e)) : people.get(random.nextInt(people.size()));
                    Validity validity = random.nextInt(4) == 0 ? new Validity(random.nextInt(10), 10 + random.nextInt(10)) : Validity.ALWAYS;
                    writer.add(edge, member, MemberSpec.roles(ROLES.get(random.nextInt(ROLES.size())))
                            .withWeight(random.nextInt(5) == 0 ? 0 : 0.1 + random.nextInt(10) / 10.0).withValidity(validity));
                }
            }
            return null;
        });
    }

    private record Exact(Map<Long, Double> atoms, Map<Long, Double> edges) {
    }

    private static double factor(Map<String, Double> factors, List<String> roles) {
        if (factors.isEmpty() || roles.isEmpty()) {
            return 1.0;
        }
        return roles.stream().mapToDouble(role -> factors.getOrDefault(role, 1.0)).max().orElse(1.0);
    }

    private static Exact exact(Reader reader, Map<Long, Double> seeds, Model model, double alpha) {
        Map<Long, List<Member>> members = new TreeMap<>();
        Map<Long, Integer> types = new HashMap<>();
        for (String name : List.of("Meeting", "Project")) {
            int type = reader.requireReadableType(name).id();
            reader.atomsOfType(type).forEach(edge -> {
                types.put(edge, type);
                members.put(edge, reader.members(edge).toList());
            });
        }
        Map<Long, List<long[]>> incident = new HashMap<>();
        Map<Long, List<Double>> incidentWeight = new HashMap<>();
        members.forEach((edge, list) -> {
            if (!model.edgeTypes().isEmpty() && !model.edgeTypes().contains(types.get(edge))) {
                return;
            }
            for (Member member : list) {
                if (model.validAt().isPresent() && !(member.validity().from() <= model.validAt().getAsLong() && model.validAt().getAsLong() < member.validity().to())) {
                    continue;
                }
                double a = model.typeWeight().getOrDefault(types.get(edge), 1.0) * factor(model.roles().exit(), member.roles());
                if (a > 0) {
                    incident.computeIfAbsent(member.atom(), _ -> new ArrayList<>()).add(new long[]{edge});
                    incidentWeight.computeIfAbsent(member.atom(), _ -> new ArrayList<>()).add(a);
                }
            }
        });
        double total = seeds.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<Long, Double> seedAtoms = new TreeMap<>();
        Map<Long, Double> seedEdges = new TreeMap<>();
        seeds.forEach((id, weight) -> (members.containsKey(id) ? seedEdges : seedAtoms).put(id, weight / total));
        Map<Long, Double> pi = new TreeMap<>();
        Map<Long, Double> piEdges = new TreeMap<>();
        Map<Long, Double> atoms = new TreeMap<>(seedAtoms);
        Map<Long, Double> atEdges = new TreeMap<>(seedEdges);
        for (int step = 0; step < 5000; step++) {
            double remaining = atoms.values().stream().mapToDouble(Double::doubleValue).sum() + atEdges.values().stream().mapToDouble(Double::doubleValue).sum();
            if (remaining < 1e-15) {
                break;
            }
            Map<Long, Double> nextAtoms = new TreeMap<>();
            Map<Long, Double> nextEdges = new TreeMap<>();
            double restart = 0;
            for (var entry : atoms.entrySet()) {
                long v = entry.getKey();
                double m = entry.getValue();
                pi.merge(v, alpha * m, Double::sum);
                double onward = (1 - alpha) * m;
                List<long[]> out = incident.getOrDefault(v, List.of());
                List<Double> weights = incidentWeight.getOrDefault(v, List.of());
                double sum = weights.stream().mapToDouble(Double::doubleValue).sum();
                if (sum <= 0) {
                    restart += onward;
                    continue;
                }
                for (int i = 0; i < out.size(); i++) {
                    double share = onward * weights.get(i) / sum;
                    nextEdges.merge(out.get(i)[0], share, Double::sum);
                    piEdges.merge(out.get(i)[0], share, Double::sum);
                }
            }
            for (var entry : atEdges.entrySet()) {
                long e = entry.getKey();
                double m = entry.getValue();
                double sum = 0;
                List<double[]> outs = new ArrayList<>();
                for (Member member : members.getOrDefault(e, List.of())) {
                    if (model.validAt().isPresent() && !(member.validity().from() <= model.validAt().getAsLong() && model.validAt().getAsLong() < member.validity().to())) {
                        continue;
                    }
                    double b = Math.max(member.weight(), 0) * factor(model.roles().entry(), member.roles());
                    if (b > 0) {
                        outs.add(new double[]{member.atom(), b});
                        sum += b;
                    }
                }
                if (sum <= 0) {
                    restart += m;
                    continue;
                }
                for (double[] out : outs) {
                    nextAtoms.merge((long) out[0], m * out[1] / sum, Double::sum);
                }
            }
            double r = restart;
            seedAtoms.forEach((id, w) -> nextAtoms.merge(id, r * w, Double::sum));
            seedEdges.forEach((id, w) -> nextEdges.merge(id, r * w, Double::sum));
            atoms = nextAtoms;
            atEdges = nextEdges;
        }
        Map<Long, Double> edgeScores = new TreeMap<>();
        piEdges.forEach((edge, mass) -> edgeScores.put(edge, alpha * mass));
        return new Exact(pi, edgeScores);
    }

    private Model randomModel(RandomGenerator random, int meeting, int project) {
        Map<String, Double> exit = new HashMap<>();
        Map<String, Double> entry = new HashMap<>();
        if (random.nextBoolean()) {
            exit.put(ROLES.get(random.nextInt(4)), random.nextInt(4) * 0.5);
            entry.put(ROLES.get(random.nextInt(4)), random.nextInt(4) * 0.5);
        }
        Set<Integer> types = random.nextInt(3) == 0 ? Set.of(meeting) : Set.of();
        Map<Integer, Double> weights = random.nextInt(3) == 0 ? Map.of(project, 3.0) : Map.of();
        OptionalLong validAt = random.nextBoolean() ? OptionalLong.of(random.nextInt(20)) : OptionalLong.empty();
        return new Model(types, weights, new RoleFactors(exit, entry), validAt, EvidencePolicy.ANY);
    }

    @Test
    void estimatesStayWithinTheCertificateOfTheExactWalk() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(41L);
        randomGraph(random);
        int meeting = database.read(reader -> reader.requireReadableType("Meeting").id());
        int project = database.read(reader -> reader.requireReadableType("Project").id());
        for (int q = 0; q < 60; q++) {
            Map<Long, Double> seeds = new TreeMap<>();
            seeds.put(people.get(random.nextInt(people.size())), 1.0);
            if (random.nextBoolean()) {
                seeds.put(edges.get(random.nextInt(edges.size())), 0.5);
            }
            Model model = randomModel(random, meeting, project);
            double alpha = List.of(0.15, 0.3, 0.5).get(random.nextInt(3));
            double epsilon = List.of(1e-3, 1e-5, 1e-8).get(random.nextInt(3));
            Result result = database.read(reader -> Diffusion.on(reader).run(seeds, model, new Params(alpha, epsilon, 10_000, 10_000), Budget.UNBOUNDED.start()));
            Exact exact = database.read(reader -> exact(reader, seeds, model, alpha));
            Map<Long, Double> estimates = new HashMap<>();
            result.atoms().forEach(scored -> estimates.put(scored.id(), scored.estimate()));
            double l1 = 0;
            for (var entry : exact.atoms().entrySet()) {
                double p = estimates.getOrDefault(entry.getKey(), 0.0);
                assertTrue(p <= entry.getValue() + 1e-12, "estimate above the exact value at " + entry.getKey());
                assertTrue(entry.getValue() <= p + result.residual() + 1e-12, "exact value above the upper bound at " + entry.getKey());
                l1 += entry.getValue() - p;
            }
            assertEquals(result.residual(), l1, 1e-9, "L1 error equals the residual");
            double edgeGap = 0;
            Map<Long, Double> edgeEstimates = new HashMap<>();
            result.edges().forEach(scored -> edgeEstimates.put(scored.id(), scored.estimate()));
            for (var entry : exact.edges().entrySet()) {
                double q2 = edgeEstimates.getOrDefault(entry.getKey(), 0.0);
                assertTrue(q2 <= entry.getValue() + 1e-12);
                edgeGap += entry.getValue() - q2;
            }
            assertTrue(edgeGap <= (1 - alpha) * result.residual() + 1e-9, "hyperedge L1 error " + edgeGap + " above its bound");
            if (epsilon == 1e-8) {
                List<Long> exactTop = exact.atoms().entrySet().stream()
                        .sorted(Map.Entry.<Long, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                        .limit(5).map(Map.Entry::getKey).toList();
                List<Long> top = result.atoms().stream().limit(5).map(Scored::id).toList();
                double gap = exact.atoms().values().stream().sorted(Comparator.reverseOrder()).limit(6).reduce((a, b) -> b).orElse(0.0);
                if (result.residual() < 1e-6 && exact.atoms().values().stream().filter(v -> Math.abs(v - gap) < 1e-6).count() <= 1) {
                    assertEquals(exactTop, top, "top atoms");
                }
            }
        }
    }

    @Test
    void runsAreDeterministicAndBudgetsKeepTheCertificate() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(42L);
        randomGraph(random);
        Map<Long, Double> seeds = Map.of(people.get(3), 1.0);
        Result first = database.read(reader -> Diffusion.on(reader).run(seeds, Model.plain(), new Params(0.2, 1e-6, 100, 100), Budget.UNBOUNDED.start()));
        Result second = database.read(reader -> Diffusion.on(reader).run(seeds, Model.plain(), new Params(0.2, 1e-6, 100, 100), Budget.UNBOUNDED.start()));
        assertEquals(first, second);
        Result cut = database.read(reader -> Diffusion.on(reader).run(seeds, Model.plain(), new Params(0.2, 1e-6, 10_000, 100),
                Budget.UNBOUNDED.withRows(30).start()));
        assertTrue(cut.exhausted());
        assertTrue(cut.reads() <= 30);
        Exact exact = database.read(reader -> exact(reader, seeds, Model.plain(), 0.2));
        double l1 = 0;
        Map<Long, Double> estimates = new HashMap<>();
        cut.atoms().forEach(scored -> estimates.put(scored.id(), scored.estimate()));
        for (var entry : exact.atoms().entrySet()) {
            l1 += entry.getValue() - estimates.getOrDefault(entry.getKey(), 0.0);
        }
        assertEquals(cut.residual(), l1, 1e-9);
    }

    @Test
    void hyperedgeSeedsThatReachNoAtomAreRefused() {
        long meeting = database.write(writer -> {
            long edge = writer.edge("Meeting");
            writer.add(edge, writer.node("Person", "late"), MemberSpec.PLAIN.withValidity(new Validity(10, 20)));
            return edge;
        });
        Model early = new Model(Set.of(), Map.of(), RoleFactors.NONE, OptionalLong.of(5), EvidencePolicy.ANY);
        database.read(reader -> {
            var refused = assertThrows(io.hstore.engine.HStoreException.class,
                    () -> Diffusion.on(reader).run(Map.of(meeting, 1.0), early, new Params(0.2, 1e-4, 10, 10), Budget.UNBOUNDED.start()));
            assertTrue(refused.getMessage().contains("reach no atom"), refused.getMessage());
            return null;
        });
    }

    @Test
    void aHubSeedIsPushedEvenBelowItsThreshold() {
        long hub = database.write(writer -> {
            long person = writer.node("Person", "hub");
            for (int i = 0; i < 300; i++) {
                long edge = writer.edge("Meeting");
                writer.add(edge, person, MemberSpec.PLAIN);
                writer.add(edge, writer.node("Person", "q" + i), MemberSpec.PLAIN);
            }
            return person;
        });
        Result result = database.read(reader -> Diffusion.on(reader).run(Map.of(hub, 1.0), Model.plain(), new Params(0.2, 0.01, 50, 50),
                Budget.UNBOUNDED.start()));
        assertEquals(0.2, result.atoms().getFirst().estimate(), 1e-12, result.toString());
        assertEquals(50, result.edges().size());
        assertEquals(0.8, result.residual(), 1e-12);
    }

    @Test
    void seedWeightsThatOverflowAreRefused() {
        long a = database.write(writer -> writer.node("Person", "a"));
        long b = database.write(writer -> writer.node("Person", "b"));
        database.read(reader -> {
            assertThrows(io.hstore.engine.HStoreException.class, () -> Diffusion.on(reader).run(Map.of(a, 1e308, b, 1e308), Model.plain(),
                    new Params(0.2, 1e-4, 5, 5), Budget.DEFAULT.start()));
            return null;
        });
        try (Session session = new Session(database)) {
            assertThrows(io.hstore.engine.HStoreException.class, () -> session.execute("DIFFUSE FROM @" + a + " THROUGH Meeting, Meeting TOP 3"));
            assertThrows(io.hstore.engine.HStoreException.class, () -> session.execute("DIFFUSE FROM @" + a + " EXIT (actor 1, actor 2) TOP 3"));
        }
    }

    @Test
    void invalidParametersAreRefused() {
        assertThrows(io.hstore.engine.HStoreException.class, () -> new Params(0, 1e-4, 1, 1));
        assertThrows(io.hstore.engine.HStoreException.class, () -> new Params(0.2, 0, 1, 1));
        assertThrows(io.hstore.engine.HStoreException.class, () -> new RoleFactors(Map.of("actor", -1.0), Map.of()));
        database.read(reader -> {
            assertThrows(io.hstore.engine.HStoreException.class,
                    () -> Diffusion.on(reader).run(Map.of(), Model.plain(), new Params(0.2, 1e-4, 1, 1), Budget.DEFAULT.start()));
            return null;
        });
    }

    @Test
    void diffuseStatementRanksAtomsAndHyperedges() {
        try (Session session = new Session(database)) {
            session.executeAll("""
                    INSERT NODE Person 'alice' AS $a;
                    INSERT NODE Person 'bob' AS $b;
                    INSERT NODE Person 'carol' AS $c;
                    INSERT EDGE Meeting MEMBERS ($a AS actor, $b AS participant) AS $m;
                    INSERT EDGE Project MEMBERS ($b AS actor, $c AS decision) AS $p;
                    """);
            var atoms = session.execute("DIFFUSE FROM $a ALPHA 0.2 EPSILON 0.000001 TOP 3");
            assertEquals(List.of("kind", "id", "score", "upper"), atoms.columns());
            assertEquals(3, atoms.rows().size());
            assertTrue(atoms.rows().getFirst().get(1).render().contains("'alice'"), atoms.rows().toString());
            assertTrue(atoms.message().contains("residual"), atoms.message());
            var blocked = session.execute("DIFFUSE FROM $a THROUGH Meeting ENTER (participant 0) TOP 5");
            assertEquals(1, blocked.rows().size());
            var withEdges = session.execute("DIFFUSE FROM $a EXIT (actor 2, participant 1) TOP 2 EDGES 2");
            assertTrue(withEdges.rows().stream().anyMatch(row -> row.getFirst().render().equals("edge")));
        }
    }
}
