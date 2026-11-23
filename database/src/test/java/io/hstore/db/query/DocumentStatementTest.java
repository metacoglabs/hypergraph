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

class DocumentStatementTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;
    Session session;

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        session = new Session(database);
        session.executeAll("""
                CREATE NODE TYPE Note;
                CREATE NODE TYPE Person;
                INSERT NODE Note 'plan' AS $doc;
                INSERT NODE Person 'alice' AS $alice;
                REVISE $doc MARKDOWN '# Plan
                Use Postgres.
                ' VALID FROM 100 NOTE 'first draft' AS $r1;
                REVISE $doc MARKDOWN 'Context first.
                # Plan
                Use HStore.
                ## Why
                Fewer joins.
                ' VALID FROM 200 SOURCES ($alice) AS $r2;
                """);
    }

    @AfterEach
    void close() {
        session.close();
        database.close();
    }

    private String id(String variable) {
        return "@" + session.variables().get(variable);
    }

    private List<String> column(QueryResult result, String name) {
        int index = result.columns().indexOf(name);
        return result.rows().stream().map(row -> row.get(index).render()).toList();
    }

    @Test
    void revisionsAreListedAndResolvedByTime() {
        QueryResult revisions = session.execute("REVISIONS OF $doc");
        assertEquals(List.of("1", "2"), column(revisions, "number"));
        assertEquals(List.of("first draft", ""), column(revisions, "note"));
        assertEquals(List.of("1", "3"), column(revisions, "sections"));
        assertEquals(List.of("# Plan\nUse Postgres.\n"), column(session.execute("REVISION OF $doc VALID AT 150"), "markdown"));
        assertTrue(column(session.execute("REVISION OF $doc"), "markdown").getFirst().contains("Use HStore."));
        assertEquals(List.of("1"), column(session.execute("REVISION OF $doc NUMBER 1"), "number"));
        assertEquals(List.of(), session.execute("REVISION OF $doc NUMBER 9").rows());
        assertEquals(List.of(), session.execute("REVISION OF $doc VALID AT 50").rows());
        long recorded = Long.parseLong(column(revisions, "recorded_at").getFirst());
        assertEquals(List.of("1"), column(session.execute("REVISION OF $doc VALID AT 250 RECORDED AT " + recorded), "number"));
    }

    @Test
    void sectionsCarryTheirPathAndText() {
        QueryResult sections = session.execute("SECTIONS OF $r2");
        assertEquals(List.of("", "plan", "plan/why"), column(sections, "anchor"));
        assertEquals(List.of("", "Plan", "Plan / Why"), column(sections, "path"));
        assertEquals(column(sections, "section"), column(session.execute("SECTIONS OF $doc"), "section"));
        String why = column(sections, "section").get(2).split(" ")[0];
        assertEquals(List.of("## Why\nFewer joins.\n"), column(session.execute("SECTION " + why), "text"));
        assertEquals(List.of(id("r2")), column(session.execute("SECTION " + why), "revision").stream().map(cell -> cell.split(" ")[0]).toList());
    }

    @Test
    void purgeRemovesEverything() {
        String why = column(session.execute("SECTIONS OF $r2"), "section").get(2).split(" ")[0];
        assertEquals("purged " + id("doc") + ": 2 revisions, 4 sections", session.execute("PURGE $doc").message());
        assertThrows(HStoreException.class, () -> session.execute("REVISIONS OF $doc"));
        assertThrows(HStoreException.class, () -> session.execute("SECTION " + why));
    }

    @Test
    void revisionsWrittenOnABranchMergeBack() {
        session.executeAll("""
                CREATE BRANCH draft;
                USE BRANCH draft;
                REVISE $doc MARKDOWN '# Plan
                Use HStore everywhere.
                ' AS $r3;
                """);
        assertEquals(List.of("1", "2", "3"), column(session.execute("REVISIONS OF $doc"), "number"));
        assertThrows(HStoreException.class, () -> session.execute("PURGE $doc"));
        session.execute("USE MAIN");
        assertEquals(List.of("1", "2"), column(session.execute("REVISIONS OF $doc"), "number"));
        session.execute("MERGE BRANCH draft");
        assertEquals(List.of("1", "2", "3"), column(session.execute("REVISIONS OF $doc"), "number"));
        assertTrue(column(session.execute("REVISION OF $doc"), "markdown").getFirst().contains("everywhere"));
        assertEquals(List.of("plan"), column(session.execute("SECTIONS OF $r3"), "anchor"));
    }

    @Test
    void revisingOnBothSidesRefusesTheMerge() {
        session.executeAll("""
                CREATE BRANCH draft;
                USE BRANCH draft;
                REVISE $doc MARKDOWN 'branch side';
                USE MAIN;
                REVISE $doc MARKDOWN 'main side';
                """);
        for (String policy : List.of("", " ON CONFLICT SOURCE", " ON CONFLICT TARGET")) {
            HStoreException refused = assertThrows(HStoreException.class, () -> session.execute("MERGE BRANCH draft" + policy));
            assertTrue(refused.getMessage().contains("revisions"), refused.getMessage());
        }
        assertEquals(List.of("main side"), column(session.execute("REVISION OF $doc"), "markdown"));
    }

    @Test
    void badStatementsAreRefused() {
        assertThrows(HStoreException.class, () -> session.execute("REVISE $r1 MARKDOWN 'x'"));
        assertThrows(HStoreException.class, () -> session.execute("REVISE $doc MARKDOWN 'x' VALID FROM 5 VALID TO 5"));
        assertThrows(HStoreException.class, () -> session.execute("DELETE $doc"));
        assertThrows(HStoreException.class, () -> session.execute("REVISION OF $doc NUMBER 0"));
        assertThrows(HStoreException.class, () -> session.execute("SECTION $doc"));
        assertEquals(List.of(), session.execute("SECTIONS OF $alice").rows());
    }

    @Test
    void wholeGraphQueriesSkipRevisionsAndSections() {
        assertEquals("2", session.execute("MATCH NODE n RETURN count(*)").rows().getFirst().getFirst().render());
    }
}
