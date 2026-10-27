// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db;

import io.hstore.db.query.QueryResult;
import io.hstore.db.query.Session;
import io.hstore.db.security.Access;
import io.hstore.db.security.Principal;
import io.hstore.db.security.Scram;
import io.hstore.db.security.Security;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;
import io.hstore.engine.txn.TxnOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
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
                CREATE NODE TYPE Secret (mrn STRING INDEXED);
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
            assertTrue(last.contains("no admin of the default tenant whose password never expires"), last);
            assertTrue(assertThrows(HStoreException.InvalidSchema.class, () -> root.execute("DROP USER root")).getMessage()
                    .contains("no admin of the default tenant whose password never expires"));
            assertThrows(HStoreException.InvalidSchema.class, () -> root.execute("ALTER USER root VALID UNTIL '2000-01-01'"));
            String soon = Instant.now().plusSeconds(1).toString();
            assertTrue(assertThrows(HStoreException.InvalidSchema.class, () -> root.execute("ALTER USER root VALID UNTIL '" + soon + "'"))
                    .getMessage().contains("whose password never expires"));
            assertThrows(HStoreException.InvalidSchema.class, () -> root.execute("ALTER USER root VALID UNTIL '2999-01-01'"));
            root.execute("ALTER USER ops ROLE admin");
            root.execute("ALTER USER root VALID UNTIL '2999-01-01'");
            assertThrows(HStoreException.InvalidSchema.class, () -> root.execute("ALTER USER ops VALID UNTIL '2999-01-01'"));
            root.execute("ALTER USER root VALID UNTIL 'infinity'");
            root.execute("CREATE USER temp PASSWORD 'temp-password' ROLE admin VALID UNTIL '2999-01-01'");
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

    @Test
    void reviewFindingsStayFixed() {
        admin.executeAll("""
                INSERT NODE Secret 's1' {mrn: 'S-1'};
                INSERT NODE Patient 'lone' {mrn: 'L-1'} AS $lone;
                INSERT EDGE Encounter MEMBERS ($p AS patient, $d AS doctor) AS $e2;
                EVIDENCE 'lab report' SOURCE $p AS $ev;
                GRANT WRITE ON TYPE Patient TO clerk;
                """);
        try (Session clerk = login("clerk", "clerk-password")) {
            assertTrue(admin.execute("TRACE @" + id("ev")).render().contains("lab report"));
            assertFalse(clerk.execute("TRACE @" + id("ev")).render().contains("lab report"));
            String present = assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("DESCRIBE @Doctor:'d1'")).getMessage();
            String absent = assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("DESCRIBE @Doctor:'nobody'")).getMessage();
            assertEquals(present, absent);
            String cascade = assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("DELETE @" + id("p"))).getMessage();
            assertTrue(cascade.contains("member of a hyperedge"), cascade);
            clerk.execute("DELETE @" + id("lone"));
            String known = clerk.execute("EXPLAIN MATCH NODE n:Patient WHERE n.mrn = 'S-1'").message();
            String unknown = clerk.execute("EXPLAIN MATCH NODE n:Patient WHERE n.mrn = 'nowhere'").message();
            assertEquals(unknown.replace("nowhere", "?"), known.replace("S-1", "?"));
            admin.execute("GRANT WRITE ON TYPE Encounter TO clerk");
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("INTERSECT @" + id("e") + ", @" + id("e2") + " INTO $both"));

            clerk.execute("BEGIN");
            assertEquals("1", cell(clerk.execute("MATCH NODE n:Patient RETURN count(*)")));
            long before = database.engine().transactions().current().id();
            admin.execute("REVOKE READ ON TYPE Patient FROM clerk");
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("MATCH NODE n:Patient RETURN count(*)"));
            clerk.execute("ROLLBACK");
            assertThrows(HStoreException.InvalidSchema.class, () -> clerk.execute("AT GENERATION " + before + " MATCH NODE n:Patient RETURN count(*)"));
        }
        assertEquals(1, admin.execute("SHOW GRANTS FOR clerk").rows().size());
    }

    @Test
    void concurrentAdminRemovalsCannotBothCommit() {
        admin.execute("CREATE USER second PASSWORD 'second-password' ROLE admin");
        try (Session root = login("root", "root-password"); Session other = login("second", "second-password")) {
            root.execute("BEGIN");
            other.execute("BEGIN");
            root.execute("ALTER USER second ROLE writer");
            other.execute("ALTER USER root ROLE writer");
            root.execute("COMMIT");
            assertThrows(HStoreException.class, () -> other.execute("COMMIT"));
        }
        assertEquals(1, admin.execute("SHOW USERS").rows().stream().filter(row -> row.get(2).render().equals("ADMIN")
                && row.get(1).render().equals("0")).count());
    }

    @Test
    void theFirstGlobalAdminCannotExpire(@TempDir Path elsewhere) {
        try (HypergraphDatabase fresh = HypergraphDatabase.open(elsewhere); Session system = new Session(fresh)) {
            assertTrue(assertThrows(HStoreException.InvalidSchema.class,
                    () -> system.execute("CREATE USER first PASSWORD 'first-password' ROLE admin VALID UNTIL '2999-01-01'"))
                    .getMessage().contains("whose password never expires"));
            system.execute("CREATE USER first PASSWORD 'first-password' ROLE admin");
        }
    }

    @Test
    void everyChangeLeavesAGlobalAdmin(@TempDir Path elsewhere) {
        try (HypergraphDatabase fresh = HypergraphDatabase.open(elsewhere); Session system = new Session(fresh)) {
            system.execute("CREATE USER early PASSWORD 'early-password' ROLE reader");
            system.execute("CREATE USER root PASSWORD 'root-password' ROLE admin");
            system.execute("DROP USER early");
            system.execute("BEGIN");
            system.execute("DROP USER root");
            assertThrows(HStoreException.InvalidSchema.class, () -> system.execute("CREATE USER bob PASSWORD 'bob-password' ROLE reader"));
            system.execute("ROLLBACK");
            try (Session dropping = new Session(fresh); Session creating = new Session(fresh)) {
                dropping.execute("BEGIN");
                creating.execute("BEGIN");
                dropping.execute("DROP USER root");
                creating.execute("CREATE USER bob PASSWORD 'bob-password' ROLE reader");
                dropping.execute("COMMIT");
                assertThrows(HStoreException.class, () -> creating.execute("COMMIT"));
            }
            assertEquals(List.of(), fresh.read(reader -> Security.users(reader.engineView())));
        }
    }

    @Test
    void restatingARoleStillGuardsAgainstARacingDrop() {
        try (Session altering = new Session(database)) {
            altering.execute("BEGIN");
            altering.execute("ALTER USER ops ROLE reader VALID UNTIL '2000-01-01'");
            admin.execute("DROP USER ops");
            admin.execute("CREATE USER ops PASSWORD 'ops-new-password' ROLE reader");
            assertThrows(HStoreException.class, () -> altering.execute("COMMIT"));
        }
        assertTrue(new Session(database).authenticate("ops", "ops-new-password"));
    }

    @Test
    void signingInAgainAsARecreatedUserCountsInItsNewTenant() {
        admin.executeAll("""
                CREATE TENANT a;
                CREATE TENANT b QUOTA (CONNECTIONS 1);
                CREATE USER mover PASSWORD 'mover-password' TENANT a ROLE reader;
                CREATE USER filler PASSWORD 'filler-password' TENANT b ROLE reader;
                """);
        int a = database.read(reader -> Security.requireTenant(reader.engineView(), "a").id());
        int b = database.read(reader -> Security.requireTenant(reader.engineView(), "b").id());
        try (Session mover = login("mover", "mover-password"); Session filler = login("filler", "filler-password")) {
            assertTrue(filler.authenticated());
            admin.executeAll("DROP USER mover; CREATE USER mover PASSWORD 'mover-password' TENANT b ROLE reader;");
            assertThrows(HStoreException.ResourceLimit.class, () -> mover.authenticate("mover", "mover-password"));
            assertEquals(1, database.tenantConnections(a));
            assertEquals(1, database.tenantConnections(b));
        }
        assertEquals(0, database.tenantConnections(a));
        assertEquals(0, database.tenantConnections(b));
        admin.execute("ALTER TENANT b QUOTA (Connections -1)");
    }

    @Test
    void aClosedSessionRunsNothing() {
        Session ops = login("ops", "ops-password");
        ops.close();
        assertThrows(HStoreException.InvalidSchema.class, () -> ops.execute("BEGIN"));
        assertEquals(0, database.connections("ops"));
    }

    @Test
    void tenantAdminsChangeOnlyTheirOwnAdminPassword() {
        admin.executeAll("""
                CREATE TENANT acme;
                CREATE USER boss PASSWORD 'boss-password' TENANT acme ROLE admin;
                CREATE USER peer PASSWORD 'peer-password' TENANT acme ROLE admin;
                """);
        try (Session boss = login("boss", "boss-password")) {
            assertThrows(HStoreException.InvalidSchema.class, () -> boss.execute("ALTER USER peer PASSWORD 'taken-over-1'"));
            boss.execute("ALTER USER boss PASSWORD 'boss-password-2'");
        }
        assertTrue(new Session(database).authenticate("peer", "peer-password"));
    }

    @Test
    void concurrentExpiriesCannotLockEveryAdminOut() {
        admin.execute("CREATE USER second PASSWORD 'second-password' ROLE admin");
        try (Session root = login("root", "root-password"); Session other = login("second", "second-password")) {
            root.execute("BEGIN");
            other.execute("BEGIN");
            root.execute("ALTER USER second VALID UNTIL '2000-01-01'");
            other.execute("ALTER USER root VALID UNTIL '2000-01-01'");
            root.execute("COMMIT");
            assertThrows(HStoreException.class, () -> other.execute("COMMIT"));
        }
        assertTrue(new Session(database).authenticate("root", "root-password"));
        assertFalse(new Session(database).authenticate("second", "second-password"));
    }

    @Test
    void futureExpiriesOnBothAdminsCannotBothCommit() {
        admin.execute("CREATE USER second PASSWORD 'second-password' ROLE admin");
        try (Session one = new Session(database); Session two = new Session(database)) {
            one.execute("BEGIN");
            two.execute("BEGIN");
            one.execute("ALTER USER second VALID UNTIL '2999-01-01'");
            two.execute("ALTER USER root VALID UNTIL '2999-01-01'");
            one.execute("COMMIT");
            assertThrows(HStoreException.class, () -> two.execute("COMMIT"));
        }
    }

    @Test
    void aRoleChangeRacingAnExpiryCannotLockEveryAdminOut() {
        admin.execute("CREATE USER second PASSWORD 'second-password' ROLE admin");
        try (Session one = new Session(database); Session two = new Session(database)) {
            one.execute("BEGIN");
            two.execute("BEGIN");
            one.execute("ALTER USER second ROLE writer");
            two.execute("ALTER USER root VALID UNTIL '2999-01-01'");
            one.execute("COMMIT");
            assertThrows(HStoreException.class, () -> two.execute("COMMIT"));
        }
    }

    @Test
    void loadingAnEmptyMemberListStillNeedsWriteAccess() {
        admin.execute("GRANT WRITE ON TYPE Patient TO clerk; GRANT READ ON TYPE Encounter TO clerk");
        Principal clerk = database.read(reader -> Security.user(reader.engineView(), "clerk")).map(Security::principal).orElseThrow();
        long encounter = id("e");
        assertThrows(HStoreException.InvalidSchema.class, () -> database.write(TxnOptions.defaults(), clerk, writer -> {
            writer.load(encounter, new long[0], MemberSpec.PLAIN);
            return null;
        }));
        assertEquals("1", cell(admin.execute("MATCH EDGE e:Encounter WHERE e HAS @Patient:'p1' RETURN count(*)")));
    }

    @Test
    void aGrantRacingADropDoesNotOutliveTheUser() {
        try (Session granting = new Session(database); Session dropping = new Session(database)) {
            granting.execute("BEGIN");
            dropping.execute("BEGIN");
            granting.execute("GRANT READ ON TYPE Secret TO clerk");
            dropping.execute("DROP USER clerk");
            dropping.execute("COMMIT");
            assertThrows(HStoreException.class, () -> granting.execute("COMMIT"));
        }
        assertEquals(0, admin.execute("SHOW GRANTS").rows().stream().filter(row -> row.getFirst().render().equals("clerk")).count());
        try (Session altering = new Session(database); Session dropping = new Session(database)) {
            altering.execute("BEGIN");
            dropping.execute("BEGIN");
            altering.execute("ALTER USER ops CONNECTION LIMIT 1");
            dropping.execute("DROP USER ops");
            dropping.execute("COMMIT");
            assertThrows(HStoreException.class, () -> altering.execute("COMMIT"));
        }
        database.write(writer -> {
            Access.grant(writer.transaction(), "clerk", writer.requireType("Secret").id(), Access.Privilege.READ);
            Access.setOptions(writer.transaction(), new Access.Options("clerk", 1, 1));
            return null;
        });
        admin.execute("CREATE USER clerk PASSWORD 'clerk-password-2' ROLE none");
        assertEquals(0, admin.execute("SHOW GRANTS").rows().stream().filter(row -> row.getFirst().render().equals("clerk")).count());
        assertTrue(new Session(database).authenticate("clerk", "clerk-password-2"));
    }

    @Test
    void signingInAgainAsTheSameUserKeepsOneConnection() {
        admin.execute("ALTER USER ops CONNECTION LIMIT 1");
        try (Session ops = login("ops", "ops-password")) {
            assertTrue(ops.authenticate("ops", "ops-password"));
            assertEquals(1, database.connections("ops"));
            assertTrue(ops.authenticate("clerk", "clerk-password"));
            assertEquals(0, database.connections("ops"));
            assertTrue(ops.authenticate("ops", "ops-password"));
        }
        assertEquals(0, database.connections("ops"));
    }

    @Test
    void tenantsCanBeLimitedInConnections() {
        admin.executeAll("""
                CREATE TENANT acme QUOTA (connections 2);
                CREATE USER boss PASSWORD 'boss-password' TENANT acme ROLE admin CONNECTION LIMIT 1;
                """);
        try (Session boss = login("boss", "boss-password")) {
            boss.execute("CREATE USER helper PASSWORD 'helper-password' ROLE admin");
            try (Session helper = login("helper", "helper-password")) {
                assertTrue(helper.authenticated());
                String limited = assertThrows(HStoreException.ResourceLimit.class,
                        () -> new Session(database).authenticate("helper", "helper-password")).getMessage();
                assertTrue(limited.contains("too many connections for tenant"), limited);
                assertThrows(HStoreException.InvalidSchema.class, () -> boss.execute("ALTER TENANT acme QUOTA (connections 10)"));
                assertTrue(admin.execute("SHOW TENANTS").render().contains("acme"));
            }
            assertTrue(new Session(database).authenticate("helper", "helper-password"));
        }
        admin.execute("ALTER TENANT acme QUOTA (connections -1)");
        try (Session one = login("boss", "boss-password")) {
            assertTrue(one.authenticated());
            assertTrue(login("helper", "helper-password").authenticated());
            assertTrue(login("helper", "helper-password").authenticated());
        }
        assertThrows(HStoreException.InvalidSchema.class, () -> admin.execute("ALTER TENANT acme QUOTA (connections -5)"));
    }

    @Test
    void tenantAdminsCannotLiftLimitsOnAdmins() {
        admin.executeAll("""
                CREATE TENANT acme;
                CREATE USER boss PASSWORD 'boss-password' TENANT acme ROLE admin CONNECTION LIMIT 2;
                CREATE USER peer PASSWORD 'peer-password' TENANT acme ROLE admin VALID UNTIL '2000-01-01';
                CREATE USER staff PASSWORD 'staff-password' TENANT acme ROLE writer;
                """);
        try (Session boss = login("boss", "boss-password")) {
            assertThrows(HStoreException.InvalidSchema.class, () -> boss.execute("ALTER USER boss CONNECTION LIMIT -1"));
            assertThrows(HStoreException.InvalidSchema.class, () -> boss.execute("ALTER USER peer VALID UNTIL 'infinity'"));
            assertThrows(HStoreException.InvalidSchema.class, () -> boss.execute("ALTER USER peer ROLE writer"));
            boss.execute("ALTER USER boss PASSWORD 'boss-password-2'");
            boss.execute("ALTER USER staff CONNECTION LIMIT 1 VALID UNTIL '2999-01-01' ROLE reader");
        }
    }

    @Test
    void sessionsEndedByAChangeStayEndedUntilTheySignIn() {
        try (Session ops = login("ops", "ops-password")) {
            admin.execute("ALTER USER ops ROLE writer");
            assertThrows(HStoreException.InvalidSchema.class, () -> ops.execute("WHOAMI"));
            admin.execute("ALTER USER ops ROLE reader");
            assertThrows(HStoreException.InvalidSchema.class, () -> ops.execute("WHOAMI"));
            assertEquals(0, database.connections("ops"));
            assertTrue(ops.authenticate("ops", "ops-password"));
            assertTrue(cell(ops.execute("WHOAMI")).equals("ops"));
            assertEquals(1, database.connections("ops"));
        }
    }
}
