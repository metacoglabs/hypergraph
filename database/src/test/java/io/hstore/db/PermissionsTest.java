// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db;

import io.hstore.db.query.QueryResult;
import io.hstore.db.query.Session;
import io.hstore.db.security.Scram;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionsTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;
    Session admin;

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        admin = new Session(database);
        admin.executeAll("""
                CREATE NODE TYPE Patient (mrn STRING INDEXED);
                CREATE NODE TYPE Doctor (name STRING);
                CREATE SET EDGE TYPE Encounter ROLES (patient, doctor);
                CREATE USER root PASSWORD 'root-password' ROLE admin;
                CREATE USER clerk PASSWORD 'clerk-password' ROLE none;
                CREATE USER ops PASSWORD 'ops-password' ROLE reader;
                INSERT NODE Patient 'p1' {mrn: 'A-1'} AS $p;
                INSERT NODE Doctor 'd1' {name: 'Ada'} AS $d;
                INSERT EDGE Encounter MEMBERS ($p AS patient, $d AS doctor) AS $e;
                """);
    }

    @AfterEach
    void close() {
        admin.close();
        database.close();
    }

    private Session login(String user, String password) {
        Session session = new Session(database);
        assertTrue(session.authenticate(user, password), user);
        return session;
    }

    private static String cell(QueryResult result) {
        return result.rows().getFirst().getFirst().render();
    }

    private long id(String variable) {
        return admin.variables().get(variable);
    }

    @Test
    void roleNoneSeesOnlyGrantedTypes() {
        try (Session clerk = login("clerk", "clerk-password")) {
            String denied = assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("MATCH NODE n:Patient RETURN n")).getMessage();
            assertTrue(denied.contains("no READ privilege on type Patient"), denied);
            assertEquals("0", cell(clerk.execute("MATCH NODE n RETURN count(*)")));
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("DESCRIBE @" + id("p")));
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("PATTERN (n: NODE Patient)"));
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("RANK EDGE e LIKE (ANY Patient) TOP 3"));

            admin.execute("GRANT READ ON TYPE Patient TO clerk");
            assertEquals("1", cell(clerk.execute("MATCH NODE n:Patient RETURN count(*)")));
            assertEquals("1", cell(clerk.execute("MATCH NODE n:Patient WHERE n.mrn = 'A-1' RETURN count(*)")));
            assertEquals("1", cell(clerk.execute("MATCH NODE n RETURN count(*)")));
            assertEquals(0, clerk.execute("INCIDENT TO @" + id("p")).rows().size());
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("MEMBERS OF @" + id("e")));
            assertEquals("0", cell(clerk.execute("MATCH NODE n:Patient RETURN degree(n)")));

            admin.execute("GRANT READ ON TYPE Encounter TO clerk");
            List<String> members = clerk.execute("MEMBERS OF @" + id("e")).rows().stream().map(row -> row.get(1).render()).toList();
            assertEquals(List.of("@" + id("p") + " Patient:'p1'", "@" + id("d")), members);
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("DESCRIBE @" + id("d")));
            assertEquals(1, clerk.execute("INCIDENT TO @" + id("p")).rows().size());
            assertEquals("1", cell(clerk.execute("MATCH EDGE e:Encounter WHERE e HAS @" + id("p") + " AS patient RETURN count(*)")));
            assertEquals("0", cell(clerk.execute("MATCH EDGE e:Encounter WHERE e HAS @" + id("d") + " RETURN count(*)")));
        }
    }

    @Test
    void writesNeedTheWritePrivilegeOfTheChangedType() {
        admin.executeAll("GRANT READ ON TYPE Patient TO clerk; GRANT READ ON TYPE Encounter TO clerk;");
        try (Session clerk = login("clerk", "clerk-password")) {
            String denied = assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("INSERT NODE Patient 'p2'")).getMessage();
            assertTrue(denied.contains("no WRITE privilege on type Patient"), denied);
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("SET @" + id("p") + ".mrn = 'X'"));
            admin.execute("GRANT WRITE ON TYPE Patient TO clerk");
            clerk.execute("INSERT NODE Patient 'p2' AS $p2");
            clerk.execute("SET @" + id("p") + ".mrn = 'A-9'");
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("INSERT EDGE Encounter MEMBERS ($p2 AS patient)"));
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("ADD $p2 TO @" + id("e") + " AS patient"));
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("SET @" + id("d") + ".name = 'Eve'"));
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("DELETE @" + id("d")));
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("EVIDENCE @" + id("p") + " SOURCE 'x'"));
            admin.execute("GRANT WRITE ON TYPE Encounter TO clerk");
            clerk.execute("ADD $p2 TO @" + id("e") + " AS patient");
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("ADD @" + id("d") + " TO @" + id("e") + " AS doctor"));
            admin.execute("REVOKE WRITE ON TYPE Patient FROM clerk");
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("INSERT NODE Patient 'p3'"));
            assertEquals("2", cell(clerk.execute("MATCH NODE n:Patient RETURN count(*)")));
            admin.execute("REVOKE READ ON TYPE Patient FROM clerk");
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("MATCH NODE n:Patient RETURN count(*)"));
        }
        assertEquals("A-9", cell(admin.execute("MATCH NODE n:Patient WHERE n = @" + id("p") + " RETURN n.mrn")));
    }

    @Test
    void grantsAreCheckedAndListed() {
        String covered = assertThrows(HStoreException.InvalidSchema.class, () -> admin.execute("GRANT READ ON TYPE Patient TO ops")).getMessage();
        assertTrue(covered.contains("already covers every type"), covered);
        assertThrows(HStoreException.InvalidSchema.class, () -> admin.execute("GRANT READ ON TYPE Missing TO clerk"));
        assertThrows(HStoreException.InvalidSchema.class, () -> admin.execute("GRANT READ ON TYPE Patient TO nobody"));
        assertThrows(HStoreException.class, () -> admin.execute("GRANT DELETE ON TYPE Patient TO clerk"));
        admin.execute("GRANT WRITE ON TYPE Patient TO clerk");
        assertTrue(admin.execute("GRANT READ ON TYPE Patient TO clerk").message().contains("already holds"));
        admin.execute("GRANT MONITOR TO ops");
        QueryResult all = admin.execute("SHOW GRANTS");
        assertEquals(List.of(List.of("clerk", "WRITE", "Patient"), List.of("ops", "MONITOR", "NULL")),
                all.rows().stream().map(row -> row.stream().map(cell -> cell.render().toUpperCase().equals("NULL") ? "NULL" : cell.render()).toList()).toList());
        try (Session clerk = login("clerk", "clerk-password")) {
            assertEquals(1, clerk.execute("SHOW GRANTS").rows().size());
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("SHOW GRANTS FOR ops"));
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("GRANT READ ON TYPE Doctor TO clerk"));
        }
        admin.execute("DROP USER clerk");
        admin.execute("CREATE USER clerk PASSWORD 'clerk-password' ROLE none");
        assertEquals(1, admin.execute("SHOW GRANTS").rows().size());
        assertEquals(List.of("user", "tenant", "role", "valid until", "connection limit"), admin.execute("SHOW USERS").columns());
    }

    @Test
    void tenantAdminsGrantOnlyInsideTheirTenant() {
        admin.executeAll("""
                CREATE TENANT acme;
                CREATE USER boss PASSWORD 'boss-password' TENANT acme ROLE admin;
                CREATE USER temp PASSWORD 'temp-password' TENANT acme ROLE none;
                """);
        try (Session boss = login("boss", "boss-password")) {
            boss.execute("GRANT READ ON TYPE Patient TO temp");
            assertThrows(HStoreException.InvalidSchema.class, () -> boss.execute("GRANT READ ON TYPE Patient TO clerk"));
            assertThrows(HStoreException.InvalidSchema.class, () -> boss.execute("GRANT MONITOR TO temp"));
            assertEquals(List.of("temp"), boss.execute("SHOW GRANTS").rows().stream().map(row -> row.getFirst().render()).toList());
        }
        assertThrows(HStoreException.InvalidSchema.class, () -> admin.execute("GRANT MONITOR TO temp"));
    }

    @Test
    void capabilitiesOpenEngineStatementsWithoutAdmin() {
        try (Session ops = login("ops", "ops-password")) {
            assertThrows(HStoreException.InvalidSchema.class, () -> ops.execute("STATS"));
            assertThrows(HStoreException.InvalidSchema.class, () -> ops.execute("CHECKPOINT"));
            admin.execute("GRANT MONITOR TO ops");
            assertFalse(ops.execute("STATS").rows().isEmpty());
            assertThrows(HStoreException.InvalidSchema.class, () -> ops.execute("CHECKPOINT"));
            admin.execute("GRANT MAINTAIN TO ops");
            ops.execute("CHECKPOINT");
            ops.execute("COMPACT");
            assertThrows(HStoreException.InvalidSchema.class, () -> ops.execute("HISTORY"));
            assertThrows(HStoreException.InvalidSchema.class, () -> ops.execute("CREATE NODE TYPE Nope"));
            admin.execute("REVOKE MONITOR FROM ops");
            assertThrows(HStoreException.InvalidSchema.class, () -> ops.execute("STATS"));
        }
    }

    @Test
    void rolesChangeAndTheLastAdminStays() {
        try (Session ops = login("ops", "ops-password")) {
            assertThrows(HStoreException.InvalidSchema.class, () -> ops.execute("INSERT NODE Patient 'p9'"));
            admin.execute("ALTER USER ops ROLE writer");
            String ended = assertThrows(HStoreException.InvalidSchema.class, () -> ops.execute("WHOAMI")).getMessage();
            assertTrue(ended.contains("authenticate again"), ended);
        }
        try (Session ops = login("ops", "ops-password")) {
            ops.execute("INSERT NODE Patient 'p9'");
        }
        try (Session root = login("root", "root-password")) {
            String last = assertThrows(HStoreException.InvalidSchema.class, () -> root.execute("ALTER USER root ROLE reader")).getMessage();
            assertTrue(last.contains("last admin"), last);
            assertTrue(assertThrows(HStoreException.InvalidSchema.class, () -> root.execute("DROP USER root")).getMessage().contains("last admin"));
            root.execute("ALTER USER ops ROLE admin");
            root.execute("ALTER USER root ROLE reader");
        }
        assertTrue(admin.execute("ALTER USER ops PASSWORD 'ops-password-2' CONNECTION LIMIT 3 VALID UNTIL 'infinity'").message()
                .contains("password, password expiry, connection limit"));
        assertThrows(HStoreException.class, () -> admin.execute("ALTER USER ops"));
        assertThrows(HStoreException.class, () -> admin.execute("ALTER USER ops CONNECTION LIMIT -2"));
    }

    @Test
    void expiredPasswordsCannotSignIn() {
        admin.execute("ALTER USER clerk VALID UNTIL '2000-01-01'");
        assertFalse(new Session(database).authenticate("clerk", "clerk-password"));
        try (Session session = new Session(database)) {
            Scram.Client client = new Scram.Client("clerk", "clerk-password", Optional.empty());
            assertTrue(session.scramFinal(client.last(session.scramFirst(Scram.MECHANISM, client.first(), Optional.empty()))).isEmpty());
        }
        admin.execute("ALTER USER clerk VALID UNTIL '2999-01-01'");
        assertTrue(new Session(database).authenticate("clerk", "clerk-password"));
        admin.execute("CREATE USER brief PASSWORD 'brief-password' ROLE reader VALID UNTIL 1");
        assertFalse(new Session(database).authenticate("brief", "brief-password"));
        admin.execute("ALTER USER brief VALID UNTIL 'infinity'");
        assertTrue(new Session(database).authenticate("brief", "brief-password"));
        assertEquals("infinity", admin.execute("SHOW USERS").rows().stream().filter(row -> row.getFirst().render().equals("brief"))
                .findFirst().orElseThrow().get(3).render());
    }

    @Test
    void connectionLimitsCountSignedInSessions() {
        admin.execute("ALTER USER ops CONNECTION LIMIT 1");
        Session first = login("ops", "ops-password");
        Session second = new Session(database);
        String limited = assertThrows(HStoreException.ResourceLimit.class, () -> second.authenticate("ops", "ops-password")).getMessage();
        assertTrue(limited.contains("too many connections for user ops"), limited);
        first.close();
        assertTrue(second.authenticate("ops", "ops-password"));
        second.close();
        assertEquals(0, database.connections("ops"));
        admin.execute("ALTER USER root CONNECTION LIMIT 0");
        try (Session one = login("root", "root-password"); Session two = login("root", "root-password")) {
            assertTrue(one.authenticated() && two.authenticated());
            assertEquals(2, database.connections("root"));
        }
        admin.execute("ALTER USER ops CONNECTION LIMIT -1");
        try (Session one = login("ops", "ops-password"); Session two = login("ops", "ops-password")) {
            assertTrue(one.authenticated());
            two.authenticate("clerk", "clerk-password");
            assertEquals(1, database.connections("ops"));
            assertEquals(1, database.connections("clerk"));
        }
        assertEquals(0, database.connections("ops"));
        assertEquals(0, database.connections("clerk"));
    }
}
