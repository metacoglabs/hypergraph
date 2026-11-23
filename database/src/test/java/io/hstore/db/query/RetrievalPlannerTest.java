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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalPlannerTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;
    Session session;

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        session = new Session(database);
        session.executeAll("""
                CREATE NODE TYPE Person;
                CREATE NODE TYPE Topic;
                CREATE SET EDGE TYPE Meeting ROLES (host, guest);
                CREATE SET EDGE TYPE Episode;
                INSERT NODE Person 'a' AS $a;
                INSERT NODE Person 'b' AS $b;
                INSERT NODE Person 'c' AS $c;
                INSERT NODE Topic 't' AS $t;
                INSERT EDGE Meeting MEMBERS ($a AS host, $b AS guest, $t) AS $m1;
                INSERT EDGE Meeting MEMBERS ($b AS host, $c AS guest, $t) AS $m2;
                INSERT EDGE Meeting MEMBERS ($a AS host, $c AS guest) AS $m3;
                INSERT EDGE Episode MEMBERS ($m1, $m2) AS $x;
                CREATE BRANCH side FROM main;
                """);
    }

    @AfterEach
    void close() {
        session.close();
        database.close();
    }

    private String explain(String statement) {
        return session.execute("EXPLAIN " + statement).message();
    }

    @Test
    void everyRetrievalStatementExplainsItsFamilyAndPlan() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("RANK EDGE e:Meeting LIKE ($a AS host) TOP 5", "family ranked-edge-retrieval  exact top 5");
        expected.put("HYPERMATCH q:Meeting(u:Person, v:Person)", "family structural-matching  exact");
        expected.put("TRANSITIONS EDGE e:Meeting FROM GENERATION 1 TO GENERATION 2 APPEARED", "family generation-time");
        expected.put("BRANCH DELTA side", "family branches");
        expected.put("COMPARE BRANCH side AND main", "family branches");
        expected.put("NEAREST BRANCH TO side TOP 1", "family branches  exact top 1");
        expected.put("REFINE FROM TYPE Episode THROUGH Meeting TO Person TOP 3", "top-down best-first");
        expected.put("REFINE FROM TYPE Episode THROUGH Meeting TO Person = $a TOP 3", "bottom-up from the leaf anchor");
        expected.put("DIFFUSE FROM $a TOP 3", "at most 90000 incidence reads");
        expected.put("DIFFUSE FROM $a READS 25000 TOP 3", "epsilon 0.000360");
        expected.put("EXPAND FROM $a TOP 3", "family associative  exact best-first");
        expected.put("SELECT EVIDENCE FOR (RANK EDGE e:Meeting LIKE ($a) TOP 5) TOP 2", "within 1 - 1/e");
        expected.put("SELECT EVIDENCE FOR (RANK EDGE e:Meeting LIKE ($a) TOP 5) BUDGET 4", "within (1 - 1/e) / 2");
        expected.put("MOTIFS", "population 4 hyperedges");
        expected.put("MOTIFS OF Meeting EXACT", "family motifs  exact, or fails");
        expected.put("MOTIF PROFILE $m1", "family motifs  exact");
        expected.put("MOTIF 94 AROUND $m1", "triples containing the anchor");
        expected.put("SIMILAR TO CHILDREN OF $x IN CHILDREN OF Episode TOP 1", "children of every Episode (1 containers)");
        expected.put("FUSE (RANK EDGE e:Meeting LIKE ($a) TOP 5), (DIFFUSE FROM $a TOP 5)", "reciprocal rank fusion");
        expected.forEach((statement, text) -> {
            String plan = explain(statement);
            assertTrue(plan.contains(text), statement + " explained as:\n" + plan);
        });
        assertThrows(HStoreException.class, () -> session.execute("EXPLAIN INSERT NODE Person 'z'"));
        assertEquals(3, session.execute("MATCH EDGE e:Meeting RETURN e").rows().size());
    }

    @Test
    void fusionAddsReciprocalRanksAndReportsCutExactResults() {
        QueryResult rank = session.execute("RANK EDGE e:Meeting LIKE ($a AS host) TOP 5");
        QueryResult evidence = session.execute("SELECT EVIDENCE FOR (RANK EDGE e:Meeting LIKE ($c) TOP 5) TOP 2");
        Map<String, Double> expected = new HashMap<>();
        for (QueryResult part : List.of(rank, evidence)) {
            for (int i = 0; i < part.rows().size(); i++) {
                expected.merge(part.rows().get(i).getFirst().render().split(" ")[0], 1.0 / (60 + i + 1), Double::sum);
            }
        }
        QueryResult fused = session.execute("FUSE (RANK EDGE e:Meeting LIKE ($a AS host) TOP 5), (SELECT EVIDENCE FOR (RANK EDGE e:Meeting LIKE ($c) TOP 2) TOP 2)");
        assertEquals(List.of("item", "score", "sources", "exact"), fused.columns());
        for (var row : fused.rows()) {
            String item = row.getFirst().render().split(" ")[0];
            assertEquals(expected.get(item), Double.parseDouble(row.get(1).render()), 1e-12, item);
        }
        QueryResult cut = session.execute("FUSE (RANK EDGE e:Meeting LIKE ($a AS host) TOP 5), (DIFFUSE FROM $c TOP 5) LIMIT 1");
        assertEquals(1, cut.rows().size());
        assertTrue(cut.message().contains("LIMIT left out"), cut.message());
        assertThrows(HStoreException.class, () -> session.execute("FUSE (RANK EDGE e:Meeting LIKE ($a) TOP 5)"));
        assertThrows(HStoreException.class, () -> session.execute("FUSE (RANK EDGE e:Meeting LIKE ($a) TOP 5), (INSERT NODE Person 'q')"));
    }

    @Test
    void branchStatementsNameTypesAndRefuseHistoricalPins() {
        session.executeAll("USE BRANCH side; INSERT NODE Person 'z'; USE MAIN;");
        var delta = session.execute("BRANCH DELTA side");
        assertTrue(delta.rows().stream().anyMatch(row -> row.get(2).render().startsWith("node Person")), delta.rows().toString());
        assertThrows(HStoreException.class, () -> session.execute("AT GENERATION 1 BRANCH DELTA side"));
        assertThrows(HStoreException.class, () -> session.execute("AT GENERATION 1 COMPARE BRANCH side AND main"));
    }

    @Test
    void aFirstScanBeyondTheBudgetIsRefusedBeforeItStarts() {
        StringBuilder script = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            script.append("INSERT EDGE Meeting MEMBERS ($a, $b);\n");
        }
        session.executeAll(script.toString());
        assertEquals("41", session.execute("HYPERMATCH q:Meeting(u:Person, v:Person) COUNT").rows().getFirst().getFirst().render());
        database.read(reader -> {
            var query = io.hstore.db.retrieve.HyperQuery.builder().vertex("u", reader.requireReadableType("Person").id())
                    .vertex("v", reader.requireReadableType("Person").id()).edge("q", reader.requireReadableType("Meeting").id(), "u", "v").build();
            io.hstore.db.hora.Budget.Meter meter = io.hstore.db.hora.Budget.DEFAULT.withRows(10).start();
            assertThrows(HStoreException.ResourceLimit.class, () -> io.hstore.db.retrieve.HyperMatch.on(reader).prepare(query, meter));
            assertEquals(0, meter.consumed());
            return null;
        });
    }
}
