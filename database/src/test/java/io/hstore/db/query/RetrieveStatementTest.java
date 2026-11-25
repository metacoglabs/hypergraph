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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrieveStatementTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;
    Session session;
    String decision;

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        session = new Session(database);
        session.executeAll("""
                CREATE NODE TYPE Person;
                CREATE NODE TYPE Project;
                CREATE NODE TYPE Note;
                CREATE SET EDGE TYPE Meeting ROLES (participant, project, decision);
                INSERT NODE Person 'alice' AS $alice;
                INSERT NODE Project 'claimsbay' AS $cb;
                INSERT NODE Note 'notes' AS $n;
                INSERT NODE Note 'other' AS $o;
                REVISE $n MARKDOWN '# Meeting
                ## Decision
                Keep ClaimsBay on Postgres.
                ' VALID FROM 100 EMBED AS $r;
                REVISE $o MARKDOWN '# Garden
                Tomatoes need water.
                ' EMBED;
                INSERT EDGE Meeting MEMBERS ($alice AS participant, $cb AS project) AS $m;
                """);
        decision = column(session.execute("SECTIONS OF $r"), "section").get(1).split(" ")[0];
        session.execute("ADD " + decision + " AS decision VALID [100, 200) TO $m");
    }

    @AfterEach
    void close() {
        session.close();
        database.close();
    }

    private static List<String> column(QueryResult result, String name) {
        int index = result.columns().indexOf(name);
        return result.rows().stream().map(row -> row.get(index).render()).toList();
    }

    @Test
    void retrieveAnswersWithAnchorsPathsAndTime() {
        QueryResult now = session.execute("RETRIEVE 'What did Alice decide about ClaimsBay?' RESOLVE IN (Person, Project) TOP 2");
        assertEquals(decision, column(now, "section").getFirst().split(" ")[0]);
        assertEquals("no", column(now, "current").getFirst());
        assertTrue(column(now, "path").getFirst().contains("@" + session.variables().get("m")), column(now, "path").toString());
        assertTrue(now.message().contains("anchors"), now.message());
        QueryResult then = session.execute("RETRIEVE 'What did Alice decide about ClaimsBay?' RESOLVE IN (Person, Project) VALID AT 150 TOP 2");
        assertEquals("yes", column(then, "current").getFirst());
        assertTrue(column(session.execute("RETRIEVE 'tomatoes' IN (Note) TOP 1"), "text").getFirst().contains("Tomatoes"));
        assertEquals(List.of(), session.execute("RETRIEVE 'tomatoes' IN (Person) TOP 1").rows());
        assertThrows(HStoreException.class, () -> session.execute("RETRIEVE '' TOP 3"));
        assertThrows(HStoreException.class, () -> session.execute("RETRIEVE 'x' TOP 101"));
    }

    @Test
    void diffusionFiltersByIntervalAndWeighsConfidence() {
        List<String> during = column(session.execute("DIFFUSE FROM $alice VALID DURING [150, 160) TOP 10"), "id");
        assertTrue(during.stream().anyMatch(cell -> cell.startsWith(decision + " ")), during.toString());
        List<String> outside = column(session.execute("DIFFUSE FROM $alice VALID DURING [300, 400) TOP 10"), "id");
        assertFalse(outside.stream().anyMatch(cell -> cell.startsWith(decision + " ")), outside.toString());
        assertFalse(session.execute("DIFFUSE FROM $alice WEIGHTED BY CONFIDENCE TOP 10").rows().isEmpty());
        assertThrows(HStoreException.class, () -> session.execute("DIFFUSE FROM $alice VALID DURING [5, 5) TOP 10"));
    }
}
