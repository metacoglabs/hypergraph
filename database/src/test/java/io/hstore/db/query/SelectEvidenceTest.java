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

class SelectEvidenceTest {

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
                CREATE SET EDGE TYPE Meeting ROLES (actor, participant, subject);
                CREATE SET EDGE TYPE Review ROLES (actor, subject);
                INSERT NODE Person 'alice' AS $a;
                INSERT NODE Person 'bob' AS $b;
                INSERT NODE Topic 'claims' AS $t;
                INSERT NODE Topic 'billing' AS $u;
                INSERT EDGE Meeting MEMBERS ($a AS actor, $b AS participant, $t AS subject) AS $m1;
                INSERT EDGE Meeting MEMBERS ($a AS actor, $b AS participant, $t AS subject) AS $m2;
                INSERT EDGE Meeting MEMBERS ($a AS actor, $u AS subject) AS $m3;
                INSERT EDGE Meeting MEMBERS ($b AS actor, $u AS subject) AS $m4;
                INSERT NODE Topic 'fraud' AS $v;
                INSERT EDGE Review MEMBERS ($a AS actor, $v AS subject) AS $r1;
                """);
    }

    @AfterEach
    void close() {
        session.close();
        database.close();
    }

    @Test
    void picksCoveringEvidenceAndSkipsTheDuplicate() {
        var rows = session.execute("SELECT EVIDENCE FOR (RANK EDGE e:Meeting LIKE ($a AS actor, ANY Topic AS subject, $b AS participant) TOP 10) TOP 2 KAPPA 0.2");
        assertEquals(List.of("edge", "gain", "relevance"), rows.columns());
        assertEquals(2, rows.rows().size(), rows.rows().toString());
        List<String> chosen = rows.rows().stream().map(row -> row.getFirst().render().split(" ")[0]).toList();
        long m1 = session.variables().get("m1");
        long m2 = session.variables().get("m2");
        assertTrue(!(chosen.contains("@" + m1) && chosen.contains("@" + m2)), "both copies chosen: " + chosen);
        assertTrue(rows.message().contains("of at most"), rows.message());
    }

    @Test
    void connectedSelectionsStartFromTheSeeds() {
        var rows = session.execute("SELECT EVIDENCE FOR (RANK EDGE e:Meeting LIKE (ANY Topic AS subject) TOP 10) FROM $b CONNECTED BUDGET 6");
        assertTrue(rows.rows().size() >= 1, rows.rows().toString());
        assertThrows(HStoreException.class, () -> session.execute("SELECT EVIDENCE FOR (RANK EDGE e:Meeting LIKE ($a) TOP 5) CONNECTED TOP 2"));
        assertThrows(HStoreException.class, () -> session.execute("SELECT EVIDENCE FOR (RANK EDGE e:Meeting LIKE ($a) TOP 5)"));
        assertThrows(HStoreException.class, () -> session.execute("SELECT EVIDENCE FOR (RANK EDGE e:Meeting LIKE ($a) TOP 5) TOP 2 CANDIDATES 50"));
    }

    @Test
    void hyperedgesOfAnotherTypeOnlyConnect() {
        var rows = session.execute("SELECT EVIDENCE FOR (RANK EDGE e:Meeting LIKE ($a AS actor, ANY Topic AS subject) TOP 10) FROM $a TOP 5");
        long review = session.variables().get("r1");
        assertTrue(rows.rows().stream().noneMatch(row -> row.getFirst().render().split(" ")[0].equals("@" + review)), rows.rows().toString());
        assertTrue(rows.rows().size() >= 2, rows.rows().toString());
    }
}
