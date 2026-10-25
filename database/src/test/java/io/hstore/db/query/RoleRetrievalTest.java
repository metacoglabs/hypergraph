// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.query;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Member;
import io.hstore.engine.EngineOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleRetrievalTest {

    private static final List<String> ROLES = List.of("actor", "reviewer", "target");

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
                CREATE SET EDGE TYPE Review ROLES (actor, reviewer, target);
                """);
    }

    @AfterEach
    void close() {
        session.close();
        database.close();
    }

    private record Condition(String text, Predicate<Map<Long, List<String>>> holds) {
    }

    private static List<Long> ids(QueryResult result) {
        return result.rows().stream().map(row -> Long.parseLong(row.getFirst().render().substring(1).split(" ")[0])).sorted().toList();
    }

    private Condition has(long atom, String role) {
        return role == null
                ? new Condition("e HAS @" + atom, members -> members.containsKey(atom))
                : new Condition("e HAS @" + atom + " AS " + role,
                members -> members.containsKey(atom) && members.get(atom).contains(role));
    }

    private Condition random(RandomGenerator random, List<Long> people) {
        long a = people.get(random.nextInt(people.size()));
        long b = people.get(random.nextInt(people.size()));
        String role = ROLES.get(random.nextInt(ROLES.size()));
        String other = ROLES.get(random.nextInt(ROLES.size()));
        return switch (random.nextInt(6)) {
            case 0 -> new Condition("e CONTAINS (@" + a + ", @" + b + ")", members -> members.containsKey(a) && members.containsKey(b));
            case 1 -> has(a, null);
            case 2, 3 -> has(a, role);
            case 4 -> {
                Condition left = has(a, role);
                Condition right = has(b, other);
                yield new Condition("(" + left.text() + " OR " + right.text() + ")", members -> left.holds().test(members) || right.holds().test(members));
            }
            default -> {
                Condition inner = has(a, role);
                yield new Condition("NOT " + inner.text(), members -> !inner.holds().test(members));
            }
        };
    }

    @Test
    void roleFilteredMatchesAgreeWithMembers() {
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(11);
        List<Long> people = new ArrayList<>();
        database.write(writer -> {
            for (int i = 0; i < 30; i++) {
                people.add(writer.node("Person", "p" + i));
            }
            for (int e = 0; e < 600; e++) {
                long edge = writer.edge("Review");
                int size = 1 + random.nextInt(4);
                for (int k = 0; k < size; k++) {
                    long person = k == 0 && random.nextInt(3) > 0 ? people.get(random.nextInt(3)) : people.get(random.nextInt(people.size()));
                    List<String> roles = ROLES.stream().filter(_ -> random.nextInt(3) == 0).toList();
                    writer.add(edge, person, roles.toArray(String[]::new));
                }
            }
            return null;
        });
        Map<Long, Map<Long, List<String>>> edges = database.read(reader -> reader.atoms("Review").boxed()
                .collect(Collectors.toMap(edge -> edge, edge -> reader.members(edge)
                        .collect(Collectors.toMap(Member::atom, Member::roles)))));
        for (int round = 0; round < 250; round++) {
            List<Condition> conditions = new ArrayList<>();
            for (int i = 0, n = 1 + random.nextInt(3); i < n; i++) {
                conditions.add(random(random, people));
            }
            String where = conditions.stream().map(Condition::text).collect(Collectors.joining(" AND "));
            List<Long> expected = edges.entrySet().stream()
                    .filter(entry -> conditions.stream().allMatch(condition -> condition.holds().test(entry.getValue())))
                    .map(Map.Entry::getKey).sorted().toList();
            assertEquals(expected, ids(session.execute("MATCH EDGE e:Review WHERE " + where + " RETURN e")), where);
            assertEquals(String.valueOf(expected.size()),
                    session.execute("MATCH EDGE e:Review WHERE " + where + " RETURN count(*)").rows().getFirst().getFirst().render(), where);
        }
    }

    @Test
    void roleRequirementsOnTheSameAnchorShareOneExactLeg() {
        session.executeAll("""
                INSERT NODE Person 'alice' AS $alice;
                INSERT NODE Person 'bob' AS $bob;
                INSERT NODE Person 'doc' AS $doc;
                INSERT EDGE Review MEMBERS ($alice AS actor, $bob AS reviewer, $doc AS target) AS $e10;
                INSERT EDGE Review MEMBERS ($alice AS actor|reviewer, $bob AS actor, $doc AS target) AS $e11;
                INSERT EDGE Review MEMBERS ($alice AS reviewer, $bob AS reviewer) AS $e13;
                """);
        long alice = session.variables().get("alice");
        long bob = session.variables().get("bob");
        String query = "MATCH EDGE e:Review WHERE e HAS $alice AS actor AND e HAS $bob AS reviewer";
        String plan = session.execute("EXPLAIN " + query).message();
        assertTrue(plan.startsWith("IncidentIntersection[@" + alice + " AS actor, @" + bob + " AS reviewer]"), plan);
        assertFalse(plan.contains("Verify"), plan);
        assertEquals(List.of(session.variables().get("e10")), ids(session.execute(query + " RETURN e")));
        String both = "MATCH EDGE e:Review WHERE e HAS $alice AS actor AND e HAS $alice AS reviewer AND e CONTAINS ($alice)";
        String merged = session.execute("EXPLAIN " + both).message();
        assertTrue(merged.startsWith("IncidentIntersection[@" + alice + " AS actor&reviewer]"), merged);
        assertEquals(List.of(session.variables().get("e11")), ids(session.execute(both + " RETURN e")));
        assertEquals(List.of(), ids(session.execute("MATCH EDGE e:Review WHERE e HAS $alice AS approver RETURN e")));
    }

    @Test
    void transactionsMatchTheirOwnRoles() {
        session.executeAll("""
                INSERT NODE Person 'alice' AS $alice;
                INSERT NODE Person 'bob' AS $bob;
                INSERT EDGE Review MEMBERS ($alice AS actor) AS $committed;
                BEGIN;
                INSERT EDGE Review MEMBERS ($alice AS actor, $bob AS target) AS $pending;
                """);
        List<Long> inside = ids(session.execute("MATCH EDGE e:Review WHERE e HAS $alice AS actor RETURN e"));
        assertEquals(List.of(session.variables().get("committed"), session.variables().get("pending")), inside);
        session.execute("ROLLBACK");
        assertEquals(List.of(session.variables().get("committed")),
                ids(session.execute("MATCH EDGE e:Review WHERE e HAS $alice AS actor RETURN e")));
    }

    @Test
    void olderGenerationsAndRestartsKeepRoleMatches() {
        session.executeAll("""
                INSERT NODE Person 'alice' AS $alice;
                INSERT EDGE Review MEMBERS ($alice AS actor) AS $first;
                """);
        long alice = session.variables().get("alice");
        long first = session.variables().get("first");
        long generation = database.read(reader -> reader.generation());
        session.execute("INSERT EDGE Review MEMBERS ($alice AS actor|target) AS $second");
        long second = session.variables().get("second");
        assertEquals(List.of(first), ids(session.execute("AT GENERATION " + generation + " MATCH EDGE e:Review WHERE e HAS $alice AS actor RETURN e")));
        assertEquals(List.of(), ids(session.execute("AT GENERATION " + generation + " MATCH EDGE e:Review WHERE e HAS $alice AS target RETURN e")));
        assertEquals(List.of(first, second), ids(session.execute("MATCH EDGE e:Review WHERE e HAS $alice AS actor RETURN e")));
        session.close();
        database.close();
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        session = new Session(database);
        assertEquals(List.of(second), ids(session.execute("MATCH EDGE e:Review WHERE e HAS @" + alice + " AS target RETURN e")));
        assertEquals(List.of(second), ids(session.execute("MATCH EDGE e:Review WHERE e HAS @" + alice + " AS actor AND e HAS @" + alice + " AS target RETURN e")));
    }
}
