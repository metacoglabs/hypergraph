// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.query;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimilarStatementTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;
    Session session;

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        session = new Session(database);
        StringBuilder script = new StringBuilder("""
                CREATE NODE TYPE Person;
                CREATE NODE TYPE Doc;
                CREATE SET EDGE TYPE Meeting;
                CREATE SET EDGE TYPE Call;
                CREATE SET EDGE TYPE Episode;
                """);
        for (int i = 1; i <= 12; i++) {
            script.append("INSERT NODE Person 'p").append(i).append("' AS $p").append(i).append(";\n");
        }
        script.append("""
                INSERT NODE Doc 'd1' AS $d1;
                INSERT NODE Doc 'd2' AS $d2;
                INSERT EDGE Meeting MEMBERS ($p1, $p2, $d1) AS $x1;
                INSERT EDGE Meeting MEMBERS ($p2, $p3, $p4) AS $x2;
                INSERT EDGE Episode MEMBERS ($x1, $x2) AS $x;
                INSERT EDGE Meeting MEMBERS ($p5, $d2) AS $y1;
                INSERT EDGE Call MEMBERS ($p6, $p7) AS $y2;
                INSERT EDGE Episode MEMBERS ($y1, $y2) AS $y;
                INSERT EDGE Call MEMBERS ($p9, $p10) AS $z1;
                INSERT EDGE Call MEMBERS ($p10, $p11) AS $z2;
                INSERT EDGE Call MEMBERS ($p11, $p12) AS $z3;
                INSERT EDGE Episode MEMBERS ($z1, $z2, $z3) AS $z;
                """);
        session.executeAll(script.toString());
    }

    @AfterEach
    void close() {
        session.close();
        database.close();
    }

    private String id(String variable) {
        return "@" + session.variables().get(variable);
    }

    private List<List<String>> table(QueryResult result) {
        return result.rows().stream().map(row -> List.of(row.get(0).render().split(" ")[0], row.get(2).render(), row.get(3).render())).toList();
    }

    private static final String PATTERN = "(q1:Meeting(a:Person, b:Person, d:Doc), q2:Meeting(b, c:Person))";

    @Test
    void theRunningExampleRanksAsDesigned() {
        assertEquals(List.of(List.of(id("x"), "2", "2"), List.of(id("y"), "2", "2")),
                table(session.execute("SIMILAR TO " + PATTERN + " IN CHILDREN OF Episode WITHIN 3")));
        assertEquals(List.of(List.of(id("x"), "2", "2")), table(session.execute("SIMILAR TO " + PATTERN + " IN CHILDREN OF Episode TOP 1")));
        assertEquals(List.of(id("x"), id("y"), id("z")), table(session.execute("SIMILAR TO " + PATTERN + " IN CHILDREN OF Episode TOP 3"))
                .stream().map(List::getFirst).toList());
        assertEquals("7", table(session.execute("SIMILAR TO " + PATTERN + " IN CHILDREN OF Episode TOP 3")).get(2).get(1));
        var copy = session.execute("SIMILAR TO CHILDREN OF $x IN CHILDREN OF Episode TOP 1");
        assertEquals(List.of(List.of(id("x"), "0", "0")), table(copy));
        assertEquals(id("x1") + ", " + id("x2"), copy.rows().getFirst().get(1).render());
    }

    @Test
    void identityAroundAndSetsWork() {
        assertEquals(id("x"), table(session.execute("SIMILAR TO EDGES ($x1) IDENTITY IN CHILDREN OF Episode TOP 1")).getFirst().getFirst());
        var around = session.execute("SIMILAR TO EDGES ($x1, $x2) IN AROUND $x1 SIZE 2 WITHIN 0");
        assertEquals(1, around.rows().size());
        assertEquals(id("x1") + ", " + id("x2"), around.rows().getFirst().get(1).render());
        var sets = session.execute("SIMILAR TO " + PATTERN + " IN SETS (($y1, $y2), ($z1, $z2, $z3)) TOP 2");
        assertEquals(List.of("2", "7"), table(sets).stream().map(row -> row.get(1)).toList());
        assertTrue(session.execute("SIMILAR TO " + PATTERN + " IN CHILDREN OF Episode WITHIN 1").rows().isEmpty());
    }

    @Test
    void badQueriesAreRefused() {
        assertThrows(HStoreException.class, () -> session.execute("SIMILAR TO " + PATTERN + " IDENTITY IN CHILDREN OF Episode TOP 1"));
        assertThrows(HStoreException.class, () -> session.execute("SIMILAR TO (q1:Meeting(a, b:Person)) IN CHILDREN OF Episode TOP 1"));
        assertThrows(HStoreException.class, () -> session.execute("SIMILAR TO " + PATTERN + " IN CHILDREN OF Episode WITHIN -1"));
        assertThrows(HStoreException.class, () -> session.execute("SIMILAR TO " + PATTERN + " IN AROUND $x1 SIZE 7 TOP 1"));
        assertThrows(HStoreException.class, () -> session.execute("SIMILAR TO " + PATTERN + " IN CHILDREN OF Person TOP 1"));
        assertThrows(HStoreException.class, () -> session.execute("SIMILAR TO EDGES ($p1) IN CHILDREN OF Episode TOP 1"));
        assertThrows(HStoreException.class, () -> session.execute("SIMILAR TO " + PATTERN + " IN SETS (($p1)) TOP 1"));
        assertThrows(HStoreException.class, () -> session.execute("SIMILAR TO " + PATTERN + " IN CHILDREN OF Episode TOP 0"));
        assertThrows(HStoreException.class, () -> session.execute("SIMILAR TO " + PATTERN + " IN AROUND $x1 THROUGH Person TOP 1"));
        assertThrows(HStoreException.class, () -> session.execute("SIMILAR TO CHILDREN OF $x1 IN CHILDREN OF Episode TOP 1"));
    }
}
