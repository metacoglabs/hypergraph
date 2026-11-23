// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.query;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.MemberSpec;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.topology.Validity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidityTransitionTest {

    private static final Pattern ID = Pattern.compile("@(\\d+)");

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
                CREATE SET EDGE TYPE Collaboration;
                CREATE SET EDGE TYPE Meeting;
                """);
    }

    @AfterEach
    void close() {
        session.close();
        database.close();
    }

    private List<Long> ids(String query) {
        TreeSet<Long> found = new TreeSet<>();
        session.execute(query).rows().forEach(row -> {
            Matcher matcher = ID.matcher(row.getFirst().render());
            assertTrue(matcher.find(), row.getFirst().render());
            found.add(Long.parseLong(matcher.group(1)));
        });
        return List.copyOf(found);
    }

    private String explain(String where) {
        return session.execute("EXPLAIN MATCH EDGE c:Collaboration WHERE " + where + " RETURN c").message();
    }

    private static Validity randomInterval(RandomGenerator random) {
        long from = random.nextInt(100);
        return switch (random.nextInt(5)) {
            case 0 -> Validity.ALWAYS;
            case 1 -> new Validity(from, Long.MAX_VALUE);
            case 2 -> new Validity(Long.MIN_VALUE, from + 1);
            default -> new Validity(from, from + 1 + random.nextInt(40));
        };
    }

    @Test
    void transitionsAgreeWithBruteForce() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(20261123L);
        Map<Long, List<Validity>> intervals = new java.util.HashMap<>();
        database.write(writer -> {
            List<Long> people = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                people.add(writer.node("Person", "p" + i));
            }
            for (int i = 0; i < 1500; i++) {
                long edge = writer.edge(i % 4 == 0 ? "Meeting" : "Collaboration");
                List<Validity> mine = new ArrayList<>();
                for (long person : new TreeSet<>(List.of(people.get(random.nextInt(60)), people.get(random.nextInt(60)), people.get(random.nextInt(60))))) {
                    Validity validity = randomInterval(random);
                    writer.add(edge, person, MemberSpec.PLAIN.withValidity(validity));
                    mine.add(validity);
                }
                if (i % 4 != 0) {
                    intervals.put(edge, mine);
                }
            }
            return null;
        });
        for (int q = 0; q < 150; q++) {
            long t1 = random.nextInt(140) - 20;
            long t2 = t1 + 1 + random.nextInt(50);
            List<Long> disappeared = intervals.entrySet().stream()
                    .filter(entry -> active(entry.getValue(), t1) && !active(entry.getValue(), t2))
                    .map(Map.Entry::getKey).sorted().toList();
            List<Long> appeared = intervals.entrySet().stream()
                    .filter(entry -> !active(entry.getValue(), t1) && active(entry.getValue(), t2))
                    .map(Map.Entry::getKey).sorted().toList();
            String gone = "c VALID AT " + t1 + " AND NOT c VALID AT " + t2;
            String came = "NOT c VALID AT " + t1 + " AND c VALID AT " + t2;
            assertEquals(disappeared, ids("MATCH EDGE c:Collaboration WHERE " + gone + " RETURN c"), gone);
            assertEquals(appeared, ids("MATCH EDGE c:Collaboration WHERE " + came + " RETURN c"), came);
            String goneReversed = "NOT c VALID AT " + t2 + " AND c VALID AT " + t1;
            String cameReversed = "c VALID AT " + t2 + " AND NOT c VALID AT " + t1;
            assertEquals(disappeared, ids("MATCH EDGE c:Collaboration WHERE " + goneReversed + " RETURN c"), goneReversed);
            assertEquals(appeared, ids("MATCH EDGE c:Collaboration WHERE " + cameReversed + " RETURN c"), cameReversed);
            for (String where : List.of(gone, came, goneReversed, cameReversed)) {
                assertTrue(explain(where).startsWith("ValidityTransition"), explain(where));
            }
        }
    }

    private static boolean active(List<Validity> intervals, long instant) {
        return intervals.stream().anyMatch(validity -> validity.from() <= instant && instant < validity.to());
    }

    @Test
    void runningExampleFromTheTemporalPage() {
        session.executeAll("""
                INSERT NODE Person 'a' AS $a;
                INSERT NODE Person 'b' AS $b;
                INSERT NODE Person 'c' AS $c;
                INSERT NODE Person 'd' AS $d;
                INSERT EDGE Collaboration MEMBERS ($a VALID [1, 10), $b VALID [1, 10)) AS $e20;
                INSERT EDGE Collaboration MEMBERS ($a VALID [1, *), $c VALID [3, 6)) AS $e21;
                INSERT EDGE Collaboration MEMBERS ($b VALID [2, 5), $c VALID [2, 6)) AS $e22;
                INSERT EDGE Collaboration MEMBERS ($d VALID [7, 9)) AS $e23;
                INSERT EDGE Collaboration MEMBERS ($a, $b) AS $e24;
                """);
        long e22 = session.variables().get("e22");
        long e23 = session.variables().get("e23");
        assertEquals(List.of(e22), ids("MATCH EDGE c:Collaboration WHERE c VALID AT 4 AND NOT c VALID AT 6 RETURN c"));
        assertEquals(List.of(), ids("MATCH EDGE c:Collaboration WHERE NOT c VALID AT 4 AND c VALID AT 6 RETURN c"));
        assertEquals(List.of(e23), ids("MATCH EDGE c:Collaboration WHERE NOT c VALID AT 6 AND c VALID AT 8 RETURN c"));
    }

    @Test
    void conjunctsInEitherTimeOrderUseTheIndexAndEqualInstantsDoNot() {
        session.executeAll("""
                INSERT NODE Person 'a' AS $a;
                INSERT EDGE Collaboration MEMBERS ($a VALID [1, 5)) AS $early;
                INSERT EDGE Collaboration MEMBERS ($a VALID [5, 9)) AS $late;
                """);
        long early = session.variables().get("early");
        long late = session.variables().get("late");
        assertTrue(explain("NOT c VALID AT 6 AND c VALID AT 2").contains("ValidityTransition"));
        assertEquals(List.of(early), ids("MATCH EDGE c:Collaboration WHERE NOT c VALID AT 6 AND c VALID AT 2 RETURN c"));
        assertTrue(explain("c VALID AT 6 AND NOT c VALID AT 2").contains("ValidityTransition"));
        assertEquals(List.of(late), ids("MATCH EDGE c:Collaboration WHERE c VALID AT 6 AND NOT c VALID AT 2 RETURN c"));
        assertFalse(explain("c VALID AT 4 AND NOT c VALID AT 4").contains("ValidityTransition"));
        assertEquals(List.of(), ids("MATCH EDGE c:Collaboration WHERE c VALID AT 4 AND NOT c VALID AT 4 RETURN c"));
    }

    @Test
    void untypedMatchKeepsItsScan() {
        String plan = session.execute("EXPLAIN MATCH EDGE c WHERE c VALID AT 4 AND NOT c VALID AT 6 RETURN c").message();
        assertFalse(plan.contains("ValidityTransition"), plan);
    }
}
