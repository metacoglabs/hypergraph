// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db;

import io.hstore.db.query.QueryResult;
import io.hstore.db.query.Session;
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

class SecurityTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;
    Session admin;

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        admin = new Session(database);
        admin.executeAll("""
                CREATE NODE TYPE Patient (mrn STRING INDEXED REQUIRED);
                CREATE SET EDGE TYPE Encounter ROLES (patient, clinician);
                CREATE TENANT acme QUOTA (atoms 5);
                CREATE TENANT globex;
                CREATE USER alice PASSWORD 's3cret' TENANT acme ROLE writer;
                CREATE USER bob PASSWORD 'hunter2' TENANT globex ROLE writer;
                CREATE USER auditor PASSWORD 'readonly' TENANT acme ROLE reader;
                """);
    }

    @AfterEach
    void close() {
        admin.close();
        database.close();
    }

    private Session login(String user, String password) {
        Session session = new Session(database);
        assertTrue(session.authenticate(user, password));
        return session;
    }

    private static String cell(QueryResult result) {
        return result.rows().getFirst().getFirst().render();
    }

    @Test
    void materializedViewsStayInsideTheirTenant() throws InterruptedException {
        admin.executeAll("""
                CREATE USER carol PASSWORD 'c4rol' TENANT acme ROLE admin;
                INSERT NODE Patient 'home' {mrn: 'H-1'} AS $h;
                INSERT EDGE Encounter MEMBERS ($h AS patient);
                """);
        long home = admin.variables().get("h");
        try (Session carol = login("carol", "c4rol"); Session alice = login("alice", "s3cret")) {
            alice.executeAll("INSERT NODE Patient 'p1' {mrn: 'A-1'} AS $p; INSERT EDGE Encounter MEMBERS ($p AS patient);");
            long acmePatient = alice.variables().get("p");
            carol.execute("CREATE VIEW acme_degrees AS DEGREE");
            admin.execute("CREATE VIEW home_degrees AS DEGREE");
            assertEquals(List.of(String.valueOf(acmePatient)), keys(carol.execute("VIEW acme_degrees")));
            assertEquals(List.of(String.valueOf(home)), keys(admin.execute("VIEW home_degrees")));
            assertEquals(List.of("acme_degrees"), carol.execute("SHOW VIEWS").rows().stream().map(row -> row.get(1).render()).toList());
            assertThrows(HStoreException.InvalidSchema.class, () -> carol.execute("VIEW home_degrees"));
            alice.execute("INSERT EDGE Encounter MEMBERS (@" + acmePatient + " AS patient)");
            admin.execute("INSERT EDGE Encounter MEMBERS (@" + home + " AS patient)");
            carol.execute("REFRESH VIEW acme_degrees");
            assertEquals("2", carol.execute("VIEW acme_degrees").rows().getFirst().get(1).render());
            assertEquals(List.of(String.valueOf(acmePatient)), keys(carol.execute("VIEW acme_degrees")));
            carol.execute("CREATE VIEW acme_live AS CARDINALITY CONTINUOUS");
            alice.execute("INSERT EDGE Encounter MEMBERS (@" + acmePatient + " AS patient) AS $latest");
            admin.execute("INSERT EDGE Encounter MEMBERS (@" + home + " AS patient)");
            String latest = String.valueOf(alice.variables().get("latest"));
            long deadline = System.currentTimeMillis() + 5000;
            while (!keys(carol.execute("VIEW acme_live")).contains(latest) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            List<String> live = keys(carol.execute("VIEW acme_live"));
            assertTrue(live.contains(latest));
            assertEquals(3, live.size());
        }
    }

    private static List<String> keys(QueryResult result) {
        return result.rows().stream().map(row -> row.getFirst().render()).toList();
    }

    @Test
    void tenantsAreIsolatedIncludingCanonicalKeys() {
        try (Session alice = login("alice", "s3cret"); Session bob = login("bob", "hunter2")) {
            alice.executeAll("INSERT NODE Patient 'p1' {mrn: 'A-1'} AS $p; INSERT EDGE Encounter MEMBERS ($p AS patient) AS $e;");
            bob.executeAll("INSERT NODE Patient 'p1' {mrn: 'B-1'} AS $p;");
            assertEquals("1", cell(alice.execute("MATCH NODE p:Patient RETURN count(*)")));
            assertEquals("1", cell(bob.execute("MATCH NODE p:Patient RETURN count(*)")));
            assertEquals("A-1", cell(alice.execute("MATCH NODE p:Patient WHERE p.mrn = 'A-1' RETURN p.mrn")));
            assertTrue(bob.execute("MATCH NODE p:Patient WHERE p.mrn = 'A-1' RETURN p").rows().isEmpty());
            long acmeEdge = alice.variables().get("e");
            assertThrows(HStoreException.InvalidSchema.class, () -> bob.execute("MEMBERS OF @" + acmeEdge));
            long acmePatient = alice.variables().get("p");
            assertThrows(HStoreException.InvalidSchema.class, () -> bob.execute("ADD @" + acmePatient + " TO @" + acmeEdge));
            assertEquals(1, alice.execute("INCIDENT TO @" + acmePatient).rows().size());
            assertTrue(bob.execute("INCIDENT TO @" + acmePatient).rows().isEmpty());
            assertTrue(admin.execute("INCIDENT TO @" + acmePatient).rows().isEmpty());
        }
    }

    @Test
    void rolesAndAuthenticationAreEnforced() {
        assertFalse(new Session(database).authenticate("alice", "wrong"));
        try (Session auditor = login("auditor", "readonly"); Session alice = login("alice", "s3cret")) {
            assertThrows(HStoreException.InvalidSchema.class, () -> auditor.execute("INSERT NODE Patient 'x' {mrn: 'x'}"));
            assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("CREATE NODE TYPE Forbidden"));
            assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("USE TENANT globex"));
            assertEquals("acme", alice.execute("WHOAMI").rows().getFirst().get(1).render());
        }
        assertTrue(database.requiresAuthentication());
    }

    @Test
    void quotasAreExactUnderTheirLimit() {
        try (Session alice = login("alice", "s3cret")) {
            for (int i = 0; i < 5; i++) {
                alice.execute("INSERT NODE Patient 'q" + i + "' {mrn: 'q" + i + "'}");
            }
            HStoreException.ResourceLimit exceeded = assertThrows(HStoreException.ResourceLimit.class,
                    () -> alice.execute("INSERT NODE Patient 'q5' {mrn: 'q5'}"));
            assertTrue(exceeded.getMessage().contains("quota"));
            alice.execute("DELETE @Patient:'q0'");
            alice.execute("INSERT NODE Patient 'q5' {mrn: 'q5'}");
        }
        List<List<QueryResult.Cell>> tenants = admin.execute("SHOW TENANTS").rows();
        assertTrue(tenants.stream().anyMatch(row -> row.get(1).render().equals("acme") && row.get(2).render().equals("5")));
    }

    @Test
    void threeWayMergeDetectsAndResolvesConflicts() {
        admin.executeAll("""
                INSERT NODE Patient 'm' {mrn: 'base'} AS $m;
                CREATE BRANCH what_if;
                USE BRANCH what_if;
                SET $m.mrn = 'branch';
                INSERT NODE Patient 'n' {mrn: 'new'};
                USE MAIN;
                SET $m.mrn = 'main';
                """);
        assertThrows(HStoreException.Conflict.class, () -> admin.execute("MERGE BRANCH what_if"));
        admin.execute("MERGE BRANCH what_if ON CONFLICT TARGET");
        assertEquals("main", cell(admin.execute("MATCH NODE p:Patient WHERE p.mrn = 'main' RETURN p.mrn")));
        assertEquals("1", cell(admin.execute("MATCH NODE p:Patient WHERE p.mrn = 'new' RETURN count(*)")));
        admin.executeAll("""
                CREATE BRANCH second;
                USE BRANCH second;
                SET $m.mrn = 'second';
                USE MAIN;
                SET $m.mrn = 'main-again';
                MERGE BRANCH second ON CONFLICT SOURCE;
                """);
        assertEquals("1", cell(admin.execute("MATCH NODE p:Patient WHERE p.mrn = 'second' RETURN count(*)")));
    }

    @Test
    void jsonOutputIsMachineReadable() {
        admin.execute("FORMAT JSON");
        String json = admin.render(admin.execute("SHOW TENANTS"));
        assertTrue(json.startsWith("{\"columns\":[\"id\",\"name\""), json);
    }
}
