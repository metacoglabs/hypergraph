// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.query;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.MemberSpec;
import io.hstore.db.hora.Budget;
import io.hstore.db.retrieve.RankQuery;
import io.hstore.db.retrieve.Ranking;
import io.hstore.db.retrieve.RoleTimeScorer;
import io.hstore.db.schema.TypeDef;
import io.hstore.db.value.Value;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankTest {

    private static final List<String> ROLES = List.of("actor", "reviewer", "target", "observer");

    @TempDir
    Path directory;

    HypergraphDatabase database;
    Session session;

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults()
                .withEngine(EngineOptions.defaults().withPageSize(4096)));
        session = new Session(database);
        session.executeAll("""
                CREATE NODE TYPE Person;
                CREATE NODE TYPE Document;
                CREATE SET EDGE TYPE Review (status STRING, amount FLOAT) ROLES (actor, reviewer, target, observer);
                """);
    }

    @AfterEach
    void close() {
        session.close();
        database.close();
    }

    private static List<String> column(QueryResult result, int index) {
        return result.rows().stream().map(row -> row.get(index).render()).toList();
    }

    @Test
    void workedExampleRanksByTheFiveComponents() {
        session.executeAll("""
                INSERT NODE Person 'alice' AS $alice;
                INSERT NODE Person 'bob' AS $bob;
                INSERT NODE Person 'carol' AS $carol;
                INSERT NODE Person 'dave' AS $dave;
                INSERT NODE Document 'd1' AS $d1;
                INSERT NODE Document 'd2' AS $d2;
                INSERT NODE Document 'd3' AS $d3;
                INSERT EDGE Review {status: 'approved'} MEMBERS ($alice AS actor, $bob AS reviewer VALID [0, 50), $d1 AS target) AS $e1;
                INSERT EDGE Review {status: 'draft'} MEMBERS ($alice AS actor, $bob AS actor, $d2 AS target) AS $e2;
                INSERT EDGE Review {status: 'approved'} MEMBERS ($alice AS actor, $carol AS reviewer, $d3 AS target, $dave AS observer) AS $e3;
                """);
        String query = """
                RANK EDGE e:Review LIKE ($alice AS actor REQUIRED, $bob AS reviewer, ANY Document AS target)
                  VALID AT 100 TAU 100 PREFER e.status = 'approved' SIZE 3 TOP %d""";
        QueryResult all = session.execute(query.formatted(3));
        List<Long> order = all.rows().stream().map(row -> Long.parseLong(row.getFirst().render().substring(1).split(" ")[0])).toList();
        assertEquals(List.of(session.variables().get("e1"), session.variables().get("e3"), session.variables().get("e2")), order);
        List<Double> scores = all.rows().stream().map(row -> Double.parseDouble(row.get(1).render())).toList();
        double tau = Math.exp(-51.0 / 100);
        assertEquals(0.3 + 0.2 + 0.2 + 0.2 * (2 + tau) / 3 + 0.1, scores.get(0), 1e-9);
        assertEquals(0.3 * 2 / 3 + 0.2 * 2 / 3 + 0.2 + 0.2 + 0.1 * 0.75, scores.get(1), 1e-9);
        assertEquals(0.3 + 0.2 * 2 / 3 + 0.2 + 0.1, scores.get(2), 1e-9);
        assertEquals(List.of(session.variables().get("e1")), session.execute(query.formatted(1)).rows().stream()
                .map(row -> Long.parseLong(row.getFirst().render().substring(1).split(" ")[0])).toList());
        QueryResult plain = session.execute("RANK EDGE e:Review LIKE ($bob AS reviewer) TOP 5");
        assertEquals(List.of("NULL", "NULL", "NULL"), List.of(plain.rows().getFirst().get(4).render().toUpperCase(),
                plain.rows().getFirst().get(5).render().toUpperCase(), plain.rows().getFirst().get(6).render().toUpperCase()));
        assertEquals("1", plain.rows().getFirst().get(1).render());
        assertEquals(3, plain.rows().size());
        assertTrue(all.trace().orElseThrow().plan().startsWith("IncidentIntersection[@" + session.variables().get("alice") + " AS actor]"));
    }

    @Test
    void earlyStoppingAgreesWithScoringEverything() {
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(5);
        List<Long> people = new ArrayList<>();
        List<Long> documents = new ArrayList<>();
        database.write(writer -> {
            for (int i = 0; i < 25; i++) {
                people.add(writer.node("Person", "p" + i));
                documents.add(writer.node("Document", "d" + i));
            }
            for (int e = 0; e < 500; e++) {
                long edge = writer.edge("Review", Map.of("status", random.nextBoolean() ? "approved" : "draft",
                        "amount", random.nextInt(200) * 1.0));
                for (int k = 0, size = 1 + random.nextInt(5); k < size; k++) {
                    long member = random.nextInt(3) == 0 ? documents.get(random.nextInt(documents.size()))
                            : people.get(random.nextInt(k == 0 ? 3 : people.size()));
                    long from = random.nextInt(100);
                    Validity validity = random.nextBoolean() ? Validity.ALWAYS : new Validity(from, from + 1 + random.nextInt(60));
                    writer.add(edge, member, MemberSpec.roles(ROLES.stream().filter(_ -> random.nextInt(3) == 0).toArray(String[]::new))
                            .withValidity(validity));
                }
            }
            return null;
        });
        database.read(reader -> {
            TypeDef review = reader.requireType("Review");
            TypeDef document = reader.requireType("Document");
            for (int round = 0; round < 300; round++) {
                List<RankQuery.Slot> slots = new ArrayList<>();
                for (int i = 0, n = 1 + random.nextInt(4); i < n; i++) {
                    RankQuery.Target target = random.nextInt(4) == 0
                            ? new RankQuery.Target.OfType(document)
                            : new RankQuery.Target.Atom(people.get(random.nextInt(i == 0 ? 3 : people.size())));
                    Set<String> roles = Set.copyOf(ROLES.stream().filter(_ -> random.nextInt(3) == 0).toList());
                    slots.add(new RankQuery.Slot(target, roles, 0.5 + random.nextInt(3), random.nextInt(4) == 0));
                }
                Optional<RankQuery.TimeSpec> time = switch (random.nextInt(3)) {
                    case 0 -> Optional.empty();
                    case 1 -> Optional.of(new RankQuery.TimeSpec.At(random.nextInt(150), random.nextInt(3) * 25.0));
                    default -> {
                        long from = random.nextInt(120);
                        yield Optional.of(new RankQuery.TimeSpec.During(from, from + 1 + random.nextInt(80)));
                    }
                };
                List<RankQuery.Preference> preferences = random.nextBoolean() ? List.of()
                        : List.of(new RankQuery.Preference.Equals("status", new Value.Text("approved"), 1),
                        new RankQuery.Preference.Near("amount", 100, 50, 2));
                OptionalLong size = random.nextBoolean() ? OptionalLong.empty() : OptionalLong.of(1 + random.nextInt(4));
                int top = 1 + random.nextInt(12);
                RankQuery.Weights weights = random.nextBoolean() ? RankQuery.Weights.DEFAULT : new RankQuery.Weights(0.05 + random.nextDouble(),
                        random.nextDouble(), random.nextDouble(), random.nextDouble(), random.nextDouble());
                RankQuery query = new RankQuery(Optional.of(review), slots, time, preferences, size, weights, top);
                RoleTimeScorer scorer = new RoleTimeScorer(reader, query, Budget.UNBOUNDED.start());
                List<RoleTimeScorer.EdgeScore> everything = reader.atomsOfType(review.id()).boxed()
                        .flatMap(edge -> {
                            Optional<RoleTimeScorer.EdgeScore> scored = scorer.score(edge);
                            scored.ifPresent(found -> assertTrue(scorer.bound(edge) + 1e-9 >= found.score(),
                                    "bound below score for " + edge + " in " + query));
                            return scored.stream();
                        })
                        .sorted(Comparator.comparingDouble(RoleTimeScorer.EdgeScore::score).reversed()
                                .thenComparingLong(RoleTimeScorer.EdgeScore::edge))
                        .limit(top)
                        .toList();
                List<RoleTimeScorer.EdgeScore> ranked = Ranking.of(reader, query).top(Budget.DEFAULT.start());
                assertEquals(everything.stream().map(RoleTimeScorer.EdgeScore::edge).toList(),
                        ranked.stream().map(RoleTimeScorer.EdgeScore::edge).toList(), query.toString());
                IntStream.range(0, ranked.size()).forEach(i -> assertEquals(everything.get(i).score(), ranked.get(i).score(), 1e-12));
            }
            return null;
        });
    }

    @Test
    void requiredTypedSlotsAreHardConstraints() {
        session.executeAll("""
                INSERT NODE Person 'alice' AS $alice;
                INSERT NODE Document 'd1' AS $d1;
                INSERT EDGE Review MEMBERS ($alice AS actor, $d1 AS target) AS $with;
                INSERT EDGE Review MEMBERS ($alice AS actor, $d1 AS observer) AS $wrongRole;
                INSERT EDGE Review MEMBERS ($alice AS actor) AS $without;
                """);
        QueryResult result = session.execute("RANK EDGE e:Review LIKE ($alice, ANY Document AS target REQUIRED) TOP 10");
        assertEquals(1, result.rows().size());
        assertTrue(result.rows().getFirst().getFirst().render().startsWith("@" + session.variables().get("with")));
        assertTrue(result.trace().orElseThrow().plan().startsWith("TypeScan(Review)"));
        assertEquals(2, session.execute("RANK EDGE e:Review LIKE ($alice, ANY Document AS target OR observer REQUIRED) TOP 10").rows().size());
    }

    @Test
    void invalidRankStatementsFailCleanly() {
        session.execute("INSERT NODE Person 'alice' AS $alice");
        List<String> statements = List.of(
                "RANK EDGE e:Review LIKE ($alice) TOP 0",
                "RANK EDGE e:Review LIKE ($alice) TOP 10001",
                "RANK EDGE e:Review LIKE ($alice) TOP 99999999999",
                "RANK EDGE e:Review LIKE ($alice) VALID AT 5 TAU -1 TOP 3",
                "RANK EDGE e:Review LIKE ($alice) VALID DURING [5, 5) TOP 3",
                "RANK EDGE e:Review LIKE ($alice) VALID DURING [*, 5) TOP 3",
                "RANK EDGE e:Review LIKE ($alice) WEIGHTS (bogus 1) TOP 3",
                "RANK EDGE e:Review LIKE ($alice) WEIGHTS (members 0, roles 0) TOP 3",
                "RANK EDGE e:Review LIKE ($alice) WEIGHTS (members -1) TOP 3",
                "RANK EDGE e:Review LIKE ($alice WEIGHT 0) TOP 3",
                "RANK EDGE e:Review LIKE ($alice) PREFER x.status = 'a' TOP 3",
                "RANK EDGE e:Review LIKE ($alice) PREFER e.amount NEAR 5 SCALE 0 TOP 3",
                "RANK EDGE e:Review LIKE ($alice) SIZE 0 TOP 3",
                "RANK EDGE e:Person LIKE ($alice) TOP 3",
                "RANK EDGE e:Review LIKE (@999999) TOP 3",
                "RANK EDGE e:Review LIKE (ANY Missing) TOP 3",
                "RANK EDGE e:Review LIKE (" + "$alice, ".repeat(16) + "$alice) TOP 3",
                "RANK EDGE e:Review LIKE ($alice) PREFER " + "e.amount NEAR 1 SCALE 1, ".repeat(16) + "e.amount NEAR 1 SCALE 1 TOP 3",
                "RANK EDGE e:Review LIKE ($alice) VALID DURING [5, *) TOP 3",
                "RANK EDGE e:Review LIKE () TOP 3",
                "RANK EDGE e:Review ($alice) TOP 3",
                "RANK EDGE e:Review LIKE ($alice)",
                "RANK NODE e:Review LIKE ($alice) TOP 3",
                "RANK EDGE e:Review LIKE ($alice) WEIGHTS (members 1, members 2) TOP 3");
        for (String statement : statements) {
            assertThrows(HStoreException.class, () -> session.execute(statement), statement);
        }
    }

    @Test
    void untypedRequiredAndRepeatedSlots() {
        session.executeAll("""
                CREATE SET EDGE TYPE Note ROLES (author);
                INSERT NODE Person 'alice' AS $alice;
                INSERT NODE Person 'bob' AS $bob;
                INSERT EDGE Review MEMBERS ($alice AS actor, $bob AS reviewer) AS $review;
                INSERT EDGE Note MEMBERS ($alice AS author) AS $note;
                INSERT EDGE Note MEMBERS ($bob AS author) AS $other;
                """);
        QueryResult untyped = session.execute("RANK EDGE e LIKE ($alice) TOP 10");
        assertTrue(untyped.trace().orElseThrow().plan().startsWith("CatalogScan(edges)"));
        assertEquals(List.of("1", "1", "0"), column(untyped, 1));
        QueryResult required = session.execute("RANK EDGE e LIKE ($alice REQUIRED, $bob) TOP 10");
        assertEquals(2, required.rows().size());
        assertTrue(required.rows().getFirst().getFirst().render().startsWith("@" + session.variables().get("review")));
        QueryResult twice = session.execute("RANK EDGE e:Review LIKE ($alice AS actor, $alice AS reviewer) TOP 10");
        assertEquals(List.of("1"), column(twice, 2));
        assertEquals(List.of("0.5"), column(twice, 3));
    }

    @Test
    void budgetsStopRankingInsteadOfTruncatingIt() {
        database.write(writer -> {
            long alice = writer.node("Person", "alice");
            for (int i = 0; i < 20; i++) {
                writer.add(writer.edge("Review"), alice, "actor");
            }
            return null;
        });
        database.read(reader -> {
            RankQuery query = new RankQuery(Optional.of(reader.requireType("Review")),
                    List.of(new RankQuery.Slot(new RankQuery.Target.OfType(reader.requireType("Person")), Set.of("actor"), 1, false)),
                    Optional.empty(), List.of(), OptionalLong.empty(), RankQuery.Weights.DEFAULT, 5);
            HStoreException failure = assertThrows(HStoreException.class, () -> Ranking.of(reader, query).top(Budget.DEFAULT.withRows(10).start()));
            assertTrue(failure.getMessage().contains("budget"), failure.getMessage());
            assertEquals(5, Ranking.of(reader, query).top(Budget.DEFAULT.start()).size());
            return null;
        });
    }
}
