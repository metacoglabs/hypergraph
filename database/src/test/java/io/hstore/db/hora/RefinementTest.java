// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Member;
import io.hstore.db.MemberSpec;
import io.hstore.db.Reader;
import io.hstore.db.hora.Refinement.LeafSpec;
import io.hstore.db.hora.Refinement.Level;
import io.hstore.db.hora.Refinement.Mode;
import io.hstore.db.hora.Refinement.Result;
import io.hstore.db.hora.Refinement.Spec;
import io.hstore.db.query.Session;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;
import io.hstore.engine.topology.Validity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefinementTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        try (Session admin = new Session(database)) {
            admin.executeAll("""
                    CREATE NODE TYPE Provider;
                    CREATE NODE TYPE Patient;
                    CREATE NODE TYPE Analyst;
                    CREATE SET EDGE TYPE Investigation ROLES (subject, lead);
                    CREATE SET EDGE TYPE Claim ROLES (provider, patient, subject);
                    """);
        }
    }

    @AfterEach
    void close() {
        database.close();
    }

    private int type(String name) {
        return database.read(reader -> reader.requireReadableType(name).id());
    }

    @Test
    void runningExampleFindsTheTwoBestProviders() {
        Map<String, Long> ids = new HashMap<>();
        database.write(writer -> {
            for (String p : List.of("p10", "p11", "p12", "p13")) {
                ids.put(p, writer.node("Provider", p));
            }
            ids.put("a20", writer.node("Patient", "a20"));
            ids.put("n40", writer.node("Analyst", "n40"));
            long i50 = writer.edge("Investigation");
            long c60 = writer.edge("Claim");
            long c61 = writer.edge("Claim");
            long c62 = writer.edge("Claim");
            ids.put("i50", i50);
            ids.put("c62", c62);
            writer.add(i50, c60, MemberSpec.roles("subject").withWeight(0.9));
            writer.add(i50, c61, MemberSpec.roles("subject").withWeight(0.4));
            writer.add(i50, c62, MemberSpec.roles("subject").withWeight(0.7));
            writer.add(i50, ids.get("n40"), MemberSpec.roles("lead").withWeight(1.0));
            writer.add(c60, ids.get("p10"), MemberSpec.roles("provider").withWeight(0.5));
            writer.add(c60, ids.get("p11"), MemberSpec.roles("provider").withWeight(0.8).withValidity(new Validity(0, 5)));
            writer.add(c60, ids.get("a20"), MemberSpec.roles("patient").withWeight(1.0));
            writer.add(c61, ids.get("p12"), MemberSpec.roles("provider").withWeight(1.0));
            writer.add(c62, ids.get("p13"), MemberSpec.roles("provider").withWeight(0.6));
            writer.add(c62, ids.get("p10"), MemberSpec.roles("provider").withWeight(0.9));
            return null;
        });
        Spec spec = new Spec(List.of(ids.get("i50")), OptionalInt.empty(), List.of(new Level(type("Claim"), List.of("subject"))),
                new LeafSpec(OptionalInt.of(type("Provider")), List.of("provider"), OptionalLong.empty()), OptionalLong.of(7), 0, 1, Mode.DISTINCT, 2);
        List<Result> results = database.read(reader -> Refinement.on(reader).run(spec, Budget.DEFAULT.start()).results());
        assertEquals(List.of(ids.get("p10"), ids.get("p13")), results.stream().map(Result::leaf).toList());
        assertEquals(0.63, results.get(0).score(), 1e-6);
        assertEquals(0.42, results.get(1).score(), 1e-6);
        assertEquals(List.of(ids.get("i50"), ids.get("c62")), results.get(0).path());
    }

    private record Graph(List<Long> roots, List<Long> claims) {
    }

    private Graph randomGraph(RandomGenerator random) {
        return database.write(writer -> {
            List<Long> providers = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                providers.add(writer.node("Provider", "p" + i));
            }
            List<Long> claims = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                claims.add(writer.edge("Claim"));
            }
            List<Long> roots = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                roots.add(writer.edge("Investigation"));
            }
            for (long claim : claims) {
                for (int m = 0; m < 1 + random.nextInt(5); m++) {
                    writer.add(claim, providers.get(random.nextInt(providers.size())), spec(random, random.nextBoolean() ? "provider" : "patient"));
                }
                for (int link = 0; link < random.nextInt(3); link++) {
                    long other = claims.get(random.nextInt(claims.size()));
                    if (other != claim) {
                        writer.add(claim, other, spec(random, "subject"));
                    }
                }
            }
            for (long root : roots) {
                for (int m = 0; m < 2 + random.nextInt(8); m++) {
                    writer.add(root, claims.get(random.nextInt(claims.size())), spec(random, random.nextInt(4) == 0 ? "lead" : "subject"));
                }
            }
            return new Graph(roots, claims);
        });
    }

    private static MemberSpec spec(RandomGenerator random, String role) {
        Validity validity = random.nextInt(3) == 0 ? new Validity(random.nextInt(10), 10 + random.nextInt(10)) : Validity.ALWAYS;
        return MemberSpec.roles(role).withWeight(random.nextInt(1001) / 1000.0).withValidity(validity);
    }

    private static boolean passes(Member member, List<String> roles, OptionalLong validAt, double low, double high) {
        return (roles.isEmpty() || member.roles().stream().anyMatch(roles::contains)) && member.weight() >= low && member.weight() <= high
                && validAt.stream().allMatch(t -> member.validity().from() <= t && t < member.validity().to());
    }

    private static List<Result> bruteForce(Reader reader, Spec spec) {
        List<Result> all = new ArrayList<>();
        for (long root : spec.roots()) {
            walk(reader, spec, root, 0, 1.0, new ArrayList<>(List.of(root)), all);
        }
        all.sort(Comparator.comparingDouble(Result::score).reversed());
        List<Result> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Result result : all) {
            if (spec.mode() == Mode.DISTINCT && !seen.add(result.leaf())) {
                continue;
            }
            out.add(result);
        }
        return out;
    }

    private static void walk(Reader reader, Spec spec, long edge, int level, double score, List<Long> path, List<Result> out) {
        reader.members(edge).forEach(member -> {
            if (level < spec.levels().size()) {
                Level next = spec.levels().get(level);
                if (path.contains(member.atom()) || !reader.edgeAtom(member.atom()) || reader.typeOf(member.atom()).orElse(-1) != next.edgeType()
                        || !passes(member, next.roles(), spec.validAt(), spec.weightLow(), spec.weightHigh())) {
                    return;
                }
                path.add(member.atom());
                walk(reader, spec, member.atom(), level + 1, score * member.weight(), path, out);
                path.removeLast();
            } else if (!path.contains(member.atom()) && passes(member, spec.leaf().roles(), spec.validAt(), spec.weightLow(), spec.weightHigh())
                    && spec.leaf().type().stream().allMatch(type -> type == reader.typeOf(member.atom()).orElse(-1))
                    && spec.leaf().anchor().stream().allMatch(anchor -> anchor == member.atom())) {
                out.add(new Result(List.copyOf(path), member.atom(), score * member.weight()));
            }
        });
    }

    private static void assertSameAnswer(List<Result> expected, List<Result> got, int k, String where) {
        List<Result> want = expected.subList(0, Math.min(k, expected.size()));
        assertEquals(want.size(), got.size(), where);
        for (int i = 0; i < want.size(); i++) {
            assertEquals(want.get(i).score(), got.get(i).score(), 1e-9, where + " rank " + i);
        }
        Set<Double> tied = new HashSet<>();
        for (int i = 0; i + 1 < expected.size(); i++) {
            if (Math.abs(expected.get(i).score() - expected.get(i + 1).score()) < 1e-9) {
                tied.add(expected.get(i).score());
            }
        }
        for (int i = 0; i < want.size(); i++) {
            if (!tied.contains(want.get(i).score())) {
                assertEquals(want.get(i).leaf(), got.get(i).leaf(), where + " rank " + i);
                assertEquals(want.get(i).path(), got.get(i).path(), where + " rank " + i);
            }
        }
    }

    @Test
    void topDownAgreesWithEnumeratingEveryPath() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(31L);
        Graph graph = randomGraph(random);
        int checked = 0;
        for (int q = 0; q < 200; q++) {
            int levels = random.nextInt(4);
            List<Level> spec = new ArrayList<>();
            for (int l = 0; l < levels; l++) {
                spec.add(new Level(type("Claim"), random.nextBoolean() ? List.of("subject") : List.of()));
            }
            List<Long> roots = levels == 0 ? graph.claims().subList(0, 10) : graph.roots();
            Spec query = new Spec(roots, OptionalInt.empty(), spec,
                    new LeafSpec(random.nextBoolean() ? OptionalInt.of(type("Provider")) : OptionalInt.empty(),
                            random.nextBoolean() ? List.of("provider") : List.of(), OptionalLong.empty()),
                    random.nextBoolean() ? OptionalLong.of(random.nextInt(20)) : OptionalLong.empty(),
                    random.nextInt(3) == 0 ? 0.2 : 0, random.nextInt(3) == 0 ? 0.8 : 1, random.nextBoolean() ? Mode.DISTINCT : Mode.ALL,
                    1 + random.nextInt(15));
            List<Result> expected = database.read(reader -> bruteForce(reader, query));
            List<Result> got = database.read(reader -> Refinement.on(reader).run(query, Budget.DEFAULT.start()).results());
            assertSameAnswer(expected, got, query.k(), query.toString());
            checked += expected.isEmpty() ? 0 : 1;
        }
        assertTrue(checked > 100, "only " + checked + " queries had results");
    }

    @Test
    void anchoredLeavesClimbBottomUp() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(32L);
        Graph graph = randomGraph(random);
        List<Long> providers = database.read(reader -> reader.atomsOfType(type("Provider")).boxed().toList());
        int checked = 0;
        for (int q = 0; q < 150; q++) {
            long anchor = providers.get(random.nextInt(providers.size()));
            Spec query = new Spec(graph.roots(), OptionalInt.of(type("Investigation")), List.of(new Level(type("Claim"), List.of("subject"))),
                    new LeafSpec(OptionalInt.empty(), random.nextBoolean() ? List.of("provider") : List.of(), OptionalLong.of(anchor)),
                    random.nextBoolean() ? OptionalLong.of(random.nextInt(20)) : OptionalLong.empty(), 0, 1,
                    random.nextBoolean() ? Mode.DISTINCT : Mode.ALL, 1 + random.nextInt(10));
            List<Result> expected = database.read(reader -> bruteForce(reader, query));
            List<Result> got = database.read(reader -> Refinement.on(reader).run(query, Budget.DEFAULT.start()).results());
            assertSameAnswer(expected, got, query.k(), query.toString());
            checked += expected.isEmpty() ? 0 : 1;
        }
        assertTrue(checked > 40, "only " + checked + " anchored queries had results");
    }

    @Test
    void pathsNeverRevisitAnEdge() {
        long[] ids = database.write(writer -> {
            long root = writer.edge("Investigation");
            long first = writer.edge("Claim");
            long second = writer.edge("Claim");
            long provider = writer.node("Provider", "p");
            writer.add(root, first, MemberSpec.roles("subject").withWeight(1.0));
            writer.add(first, second, MemberSpec.roles("subject").withWeight(1.0));
            writer.add(second, first, MemberSpec.roles("subject").withWeight(1.0));
            writer.add(first, provider, MemberSpec.roles("provider").withWeight(1.0));
            writer.add(second, provider, MemberSpec.roles("provider").withWeight(1.0));
            return new long[]{root, first, second, provider};
        });
        Spec spec = new Spec(List.of(ids[0]), OptionalInt.empty(), List.of(new Level(type("Claim"), List.of()), new Level(type("Claim"), List.of()),
                new Level(type("Claim"), List.of())), new LeafSpec(OptionalInt.empty(), List.of(), OptionalLong.empty()), OptionalLong.empty(),
                0, 1, Mode.ALL, 10);
        assertEquals(List.of(), database.read(reader -> Refinement.on(reader).run(spec, Budget.DEFAULT.start()).results()));
    }

    @Test
    void weightRangesOutsideTheUnitIntervalAreRefusedAndOddMembersIgnored() {
        assertThrows(HStoreException.class, () -> new Spec(List.of(), OptionalInt.empty(), List.of(),
                new LeafSpec(OptionalInt.empty(), List.of(), OptionalLong.empty()), OptionalLong.empty(), 0, 2, Mode.ALL, 5));
        long[] ids = database.write(writer -> {
            long root = writer.edge("Investigation");
            long claim = writer.edge("Claim");
            long provider = writer.node("Provider", "p");
            writer.add(root, claim, MemberSpec.roles("subject").withWeight(0.5));
            writer.add(root, writer.node("Analyst", "n"), MemberSpec.roles("lead").withWeight(1.5));
            writer.add(claim, provider, MemberSpec.roles("provider").withWeight(0.5));
            return new long[]{root, claim, provider};
        });
        Spec spec = new Spec(List.of(ids[0]), OptionalInt.empty(), List.of(new Level(type("Claim"), List.of())),
                new LeafSpec(OptionalInt.empty(), List.of(), OptionalLong.empty()), OptionalLong.empty(), 0, 1, Mode.ALL, 5);
        List<Result> results = database.read(reader -> Refinement.on(reader).run(spec, Budget.DEFAULT.start()).results());
        assertEquals(List.of(ids[2]), results.stream().map(Result::leaf).toList());
        assertEquals(0.25, results.getFirst().score(), 1e-9);
    }

    @Test
    void aLeafNeverRepeatsAnEdgeOnItsPathInEitherDirection() {
        long[] ids = database.write(writer -> {
            long root = writer.edge("Investigation");
            long claim = writer.edge("Claim");
            writer.add(root, claim, MemberSpec.roles("subject").withWeight(1.0));
            writer.add(claim, root, MemberSpec.roles("subject").withWeight(1.0));
            return new long[]{root, claim};
        });
        Spec down = new Spec(List.of(ids[0]), OptionalInt.empty(), List.of(new Level(type("Claim"), List.of())),
                new LeafSpec(OptionalInt.empty(), List.of(), OptionalLong.empty()), OptionalLong.empty(), 0, 1, Mode.ALL, 5);
        Spec up = new Spec(List.of(ids[0]), OptionalInt.empty(), List.of(new Level(type("Claim"), List.of())),
                new LeafSpec(OptionalInt.empty(), List.of(), OptionalLong.of(ids[0])), OptionalLong.empty(), 0, 1, Mode.ALL, 5);
        database.read(reader -> {
            assertEquals(List.of(), Refinement.on(reader).run(down, Budget.DEFAULT.start()).results());
            assertEquals(List.of(), Refinement.on(reader).run(up, Budget.DEFAULT.start()).results());
            return null;
        });
    }

    @Test
    void budgetsNeverReturnAWrongAnswer() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(33L);
        Graph graph = randomGraph(random);
        List<Long> providers = database.read(reader -> reader.atomsOfType(type("Provider")).boxed().toList());
        Spec anchored = new Spec(graph.roots(), OptionalInt.empty(), List.of(new Level(type("Claim"), List.of())),
                new LeafSpec(OptionalInt.empty(), List.of(), OptionalLong.of(providers.getFirst())), OptionalLong.empty(), 0, 1, Mode.ALL, 50);
        Spec byType = new Spec(List.of(), OptionalInt.of(type("Investigation")), List.of(new Level(type("Claim"), List.of())),
                new LeafSpec(OptionalInt.empty(), List.of(), OptionalLong.empty()), OptionalLong.empty(), 0, 1, Mode.ALL, 50);
        database.read(reader -> {
            assertThrows(HStoreException.class, () -> Refinement.on(reader).run(anchored, Budget.DEFAULT.withRows(2).start()));
            assertThrows(HStoreException.class, () -> Refinement.on(reader).run(byType, Budget.DEFAULT.withRows(3).start()));
            Refinement.Outcome all = Refinement.on(reader).run(byType, Budget.DEFAULT.start());
            for (int rows = 5; rows < 400; rows += 7) {
                Refinement.Outcome cut;
                try {
                    cut = Refinement.on(reader).run(byType, Budget.DEFAULT.withRows(rows).start());
                } catch (HStoreException refused) {
                    continue;
                }
                assertEquals(all.results().subList(0, cut.results().size()).stream().map(Result::score).toList(),
                        cut.results().stream().map(Result::score).toList(), rows + " rows");
            }
            return null;
        });
    }

    @Test
    void repeatedRootsAreCountedOnce() {
        long root = database.write(writer -> {
            long investigation = writer.edge("Investigation");
            writer.add(investigation, writer.node("Provider", "p"), MemberSpec.roles("lead").withWeight(0.5));
            return investigation;
        });
        Spec spec = new Spec(List.of(root, root), OptionalInt.empty(), List.of(),
                new LeafSpec(OptionalInt.empty(), List.of(), OptionalLong.empty()), OptionalLong.empty(), 0, 1, Mode.ALL, 5);
        assertEquals(1, (int) database.read(reader -> Refinement.on(reader).run(spec, Budget.DEFAULT.start()).results().size()));
    }

    @Test
    void refineStatementReturnsPathsLeavesAndScores() {
        try (Session session = new Session(database)) {
            session.executeAll("""
                    INSERT NODE Provider 'p' AS $p;
                    INSERT NODE Provider 'q' AS $q;
                    INSERT EDGE Claim MEMBERS ($p AS provider WEIGHT 0.5, $q AS provider WEIGHT 0.25) AS $c;
                    INSERT EDGE Investigation MEMBERS ($c AS subject WEIGHT 0.8) AS $i;
                    """);
            var rows = session.execute("REFINE FROM $i THROUGH Claim AS subject TO Provider AS provider DISTINCT TOP 5");
            assertEquals(List.of("path", "leaf", "score"), rows.columns());
            assertEquals(2, rows.rows().size());
            assertTrue(rows.rows().getFirst().get(1).render().contains("'p'"), rows.rows().toString());
            assertEquals(1, session.execute("REFINE FROM TYPE Investigation THROUGH Claim TO Provider = $q TOP 3").rows().size());
            assertEquals(0, session.execute("REFINE FROM $i THROUGH Claim TO Provider WEIGHT BETWEEN 0.9 AND 1 TOP 3").rows().size());
        }
    }
}
