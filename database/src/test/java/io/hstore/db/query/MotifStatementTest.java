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
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotifStatementTest {

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
                CREATE SET EDGE TYPE Meeting ROLES (host, guest);
                INSERT NODE Person 'a' AS $a;
                INSERT NODE Person 'b' AS $b;
                INSERT NODE Person 'c' AS $c;
                INSERT NODE Person 'd' AS $d;
                INSERT NODE Person 'e' AS $e;
                INSERT NODE Person 'f' AS $f;
                INSERT NODE Person 'g' AS $g;
                INSERT EDGE Meeting MEMBERS ($a, $b, $c, $d AS host) AS $e1;
                INSERT EDGE Meeting MEMBERS ($c, $d AS guest, $e) AS $e2;
                INSERT EDGE Meeting MEMBERS ($d, $e, $f) AS $e3;
                INSERT EDGE Meeting MEMBERS ($f, $g) AS $e4;
                """);
    }

    @AfterEach
    void close() {
        session.close();
        database.close();
    }

    private Map<String, String> counts(QueryResult result) {
        Map<String, String> counts = new TreeMap<>();
        result.rows().forEach(row -> counts.put(row.get(0).render(), row.get(2).render()));
        return counts;
    }

    private String id(String variable) {
        return "@" + session.variables().get(variable);
    }

    @Test
    void theRunningExampleCountsAsDesigned() {
        assertEquals(Map.of("30", "1", "31", "1", "94", "1"), counts(session.execute("MOTIFS EXACT")));
        assertEquals(Map.of("30", "1", "31", "1", "94", "1"), counts(session.execute("MOTIFS OF Meeting")));
        assertEquals(Map.of("30", "1", "31", "1", "94", "1"), counts(session.execute("MOTIF PROFILE $e3")));
        assertEquals(Map.of("31", "1", "94", "1"), counts(session.execute("MOTIF PROFILE $e1")));
        assertEquals(Map.of("30", "1", "31", "1"), counts(session.execute("MOTIF PROFILE $e4")));
        assertTrue(session.execute("MOTIFS SAMPLE 50 SEED 4").message().contains("estimated from 50 sampled"));
    }

    @Test
    void occurrencesHonourRegionsRolesAndRanking() {
        var rows = session.execute("MOTIF 31 REGION '011' HAS $d").rows();
        assertEquals(1, rows.size());
        assertEquals(List.of(id("e3"), id("e1"), id("e4")), rows.getFirst().stream().map(cell -> cell.render().split(" ")[0]).toList());
        var roled = session.execute("MOTIF 94 REGION '111' HAS ANY Person ROLES (guest, host OR guest, ANY)").rows();
        assertEquals(List.of(id("e2"), id("e1"), id("e3")), roled.getFirst().stream().map(cell -> cell.render().split(" ")[0]).toList());
        assertEquals(0, session.execute("MOTIF 94 REGION '111' HAS ANY ROLES (host, ANY, ANY)").rows().size());
        assertEquals(1, session.execute("MOTIF 30 AROUND $e2").rows().size());
        var ranked = session.execute("MOTIF 94 RANK BY COMMON LIMIT 5");
        assertEquals(List.of("e1", "e2", "e3", "weight"), ranked.columns());
        assertEquals("1", ranked.rows().getFirst().get(3).render());
    }

    @Test
    void theCensusViewFollowsChanges() {
        session.execute("CREATE VIEW shapes AS MOTIFS OF Meeting");
        assertEquals(Map.of("30", "1", "31", "1", "94", "1"), view());
        session.execute("ADD $g TO $e2");
        session.execute("REFRESH VIEW shapes");
        assertEquals(Map.of("31", "2", "53", "1", "95", "1"), view());
        assertEquals(Map.of("31", "2", "53", "1", "95", "1"), counts(session.execute("MOTIFS EXACT")));
    }

    private Map<String, String> view() {
        Map<String, String> cells = new TreeMap<>();
        session.execute("VIEW shapes").rows().forEach(row -> cells.put(row.get(0).render(), row.get(1).render()));
        return cells;
    }

    @Test
    void badMotifsAreRefused() {
        assertThrows(HStoreException.class, () -> session.execute("MOTIF 1"));
        assertThrows(HStoreException.class, () -> session.execute("MOTIF 30 REGION '111' HAS ANY"));
        assertThrows(HStoreException.class, () -> session.execute("MOTIF 94 REGION '011' HAS ANY ROLES (host)"));
        assertThrows(HStoreException.class, () -> session.execute("MOTIF 94 REGION '000' HAS ANY"));
        assertThrows(HStoreException.class, () -> session.execute("MOTIFS OF Person"));
        assertThrows(HStoreException.class, () -> session.execute("MOTIF PROFILE $a"));
        assertThrows(HStoreException.class, () -> session.execute("MOTIF 94 RANK BY LOUDEST"));
        assertThrows(HStoreException.class, () -> session.execute("CREATE VIEW busy AS MOTIFS CONTINUOUS"));
        assertThrows(HStoreException.class, () -> session.execute("MOTIFS EXACT SEED 3"));
    }
}
