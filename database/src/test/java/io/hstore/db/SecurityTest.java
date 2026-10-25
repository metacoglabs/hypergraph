// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db;

import io.hstore.db.query.QueryResult;
import io.hstore.db.query.Session;
import io.hstore.db.security.Principal;
import io.hstore.db.semantic.Embedding;
import io.hstore.db.value.Value;
import io.hstore.db.view.MaterializedViews;
import io.hstore.engine.CheckpointThreshold;
import io.hstore.engine.topology.Weight;
import io.hstore.engine.topology.Validity;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.Dictionary;
import io.hstore.db.value.Json;
import io.hstore.db.security.SignIns;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;
import io.hstore.engine.txn.TxnOptions;
import io.hstore.engine.catalog.Branch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
        database = HypergraphDatabase.open(directory, options());
        admin = new Session(database);
        admin.executeAll("""
                CREATE NODE TYPE Patient (mrn STRING INDEXED REQUIRED);
                CREATE SET EDGE TYPE Encounter ROLES (patient, clinician);
                CREATE TENANT acme QUOTA (atoms 5);
                CREATE TENANT globex;
                CREATE USER alice PASSWORD 's3cret-pass' TENANT acme ROLE writer;
                CREATE USER bob PASSWORD 'hunter2-pass' TENANT globex ROLE writer;
                CREATE USER auditor PASSWORD 'readonly' TENANT acme ROLE reader;
                """);
    }

    @AfterEach
    void close() {
        admin.close();
        database.close();
    }

    private static DatabaseOptions options() {
        return DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
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
                CREATE USER carol PASSWORD 'c4rol-pass' TENANT acme ROLE admin;
                INSERT NODE Patient 'home' {mrn: 'H-1'} AS $h;
                INSERT EDGE Encounter MEMBERS ($h AS patient);
                """);
        long home = admin.variables().get("h");
        try (Session carol = login("carol", "c4rol-pass"); Session alice = login("alice", "s3cret-pass")) {
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

    private void awaitFeed() throws InterruptedException {
        long generation = database.engine().transactions().current().id();
        long deadline = System.currentTimeMillis() + 5000;
        while (database.engine().feed().lastGeneration() < generation && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    void viewStalenessIgnoresOtherTenants() throws InterruptedException {
        admin.execute("CREATE USER carol PASSWORD 'c4rol-pass' TENANT acme ROLE admin");
        try (Session carol = login("carol", "c4rol-pass"); Session alice = login("alice", "s3cret-pass"); Session bob = login("bob", "hunter2-pass")) {
            alice.executeAll("INSERT NODE Patient 'p1' {mrn: 'A-1'} AS $p; INSERT EDGE Encounter MEMBERS ($p AS patient);");
            long patient = alice.variables().get("p");
            carol.execute("CREATE VIEW acme_degrees AS DEGREE");
            for (int i = 0; i < 3; i++) {
                bob.executeAll("INSERT NODE Patient 'g" + i + "' {mrn: 'G-" + i + "'} AS $g; INSERT EDGE Encounter MEMBERS ($g AS patient);");
            }
            awaitFeed();
            assertEquals("0", carol.execute("VIEW acme_degrees KEY @" + patient).rows().getFirst().get(2).render());
            alice.execute("INSERT EDGE Encounter MEMBERS (@" + patient + " AS patient)");
            awaitFeed();
            assertEquals("1", carol.execute("VIEW acme_degrees KEY @" + patient).rows().getFirst().get(2).render());
        }
    }

    @Test
    void viewParametersAreValidated() {
        admin.execute("CREATE USER carol PASSWORD 'c4rol-pass' TENANT acme ROLE admin");
        try (Session carol = login("carol", "c4rol-pass")) {
            for (String definition : List.of("OVERLAP TOP -1", "OVERLAP TOP 0", "OVERLAP TOP 1001", "OVERLAP TOP 2147483648",
                    "ACTIVITY BUCKET 0", "ACTIVITY BUCKET -5")) {
                assertThrows(HStoreException.InvalidSchema.class, () -> carol.execute("CREATE VIEW bad AS " + definition), definition);
            }
            assertTrue(carol.execute("SHOW VIEWS").rows().isEmpty());
            carol.execute("CREATE VIEW overlap AS OVERLAP TOP 5 CONTINUOUS");
        }
    }

    @Test
    void brokenViewCannotStopAnotherTenantsContinuousView() throws InterruptedException {
        admin.execute("CREATE USER carol PASSWORD 'c4rol-pass' TENANT acme ROLE admin");
        try (Session carol = login("carol", "c4rol-pass"); Session alice = login("alice", "s3cret-pass"); Session bob = login("bob", "hunter2-pass")) {
            database.write(TxnOptions.defaults(), bob.principal(), writer -> {
                writer.transaction().put(MaterializedViews.VIEWS, 1, new MaterializedViews.Descriptor(1, "broken",
                        MaterializedViews.Kind.OVERLAP_TOP_K, MaterializedViews.Refresh.CONTINUOUS, -1, writer.generation(), writer.tenant()));
                return null;
            });
            bob.executeAll("INSERT NODE Patient 'g' {mrn: 'G-1'} AS $g; INSERT EDGE Encounter MEMBERS ($g AS patient);");
            alice.execute("INSERT NODE Patient 'p1' {mrn: 'A-1'} AS $p");
            carol.execute("CREATE VIEW acme_live AS CARDINALITY CONTINUOUS");
            alice.execute("INSERT EDGE Encounter MEMBERS ($p AS patient) AS $e");
            String edge = String.valueOf(alice.variables().get("e"));
            long deadline = System.currentTimeMillis() + 5000;
            while (!keys(carol.execute("VIEW acme_live")).contains(edge) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(List.of(edge), keys(carol.execute("VIEW acme_live")));
        }
    }

    @Test
    void dropViewIsTenantScopedAndNeedsAdmin() {
        admin.executeAll("""
                CREATE USER carol PASSWORD 'c4rol-pass' TENANT acme ROLE admin;
                CREATE USER dave PASSWORD 'd4ve-pass' TENANT globex ROLE admin;
                """);
        try (Session carol = login("carol", "c4rol-pass"); Session dave = login("dave", "d4ve-pass"); Session alice = login("alice", "s3cret-pass");
             Session bob = login("bob", "hunter2-pass")) {
            alice.executeAll("INSERT NODE Patient 'p1' {mrn: 'A-1'} AS $p; INSERT EDGE Encounter MEMBERS ($p AS patient);");
            bob.executeAll("INSERT NODE Patient 'g1' {mrn: 'G-1'} AS $g; INSERT EDGE Encounter MEMBERS ($g AS patient);");
            carol.execute("CREATE VIEW shared AS DEGREE");
            dave.execute("CREATE VIEW shared AS DEGREE");
            HStoreException.InvalidSchema denied = assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("DROP VIEW shared"));
            assertTrue(denied.getMessage().contains("ADMIN"), denied.getMessage());
            carol.execute("DROP VIEW shared");
            assertTrue(carol.execute("SHOW VIEWS").rows().isEmpty());
            assertEquals(1, keys(dave.execute("VIEW shared")).size());
            assertEquals(1L, (long) database.read(reader -> reader.view().scan(MaterializedViews.VIEW_DATA).values().count()));
            String again = assertThrows(HStoreException.InvalidSchema.class, () -> carol.execute("DROP VIEW shared")).getMessage();
            String missing = assertThrows(HStoreException.InvalidSchema.class, () -> carol.execute("DROP VIEW nothing")).getMessage();
            assertEquals(missing.replace("nothing", "shared"), again);
            carol.execute("CREATE VIEW shared AS DEGREE");
            assertEquals(1, keys(carol.execute("VIEW shared")).size());
        }
    }

    @Test
    void viewsPerTenantAreCapped() {
        admin.executeAll("""
                CREATE USER carol PASSWORD 'c4rol-pass' TENANT acme ROLE admin;
                CREATE USER dave PASSWORD 'd4ve-pass' TENANT globex ROLE admin;
                """);
        try (Session carol = login("carol", "c4rol-pass"); Session dave = login("dave", "d4ve-pass")) {
            for (int i = 0; i < 32; i++) {
                carol.execute("CREATE VIEW v" + i + " AS DEGREE");
            }
            assertThrows(HStoreException.ResourceLimit.class, () -> carol.execute("CREATE VIEW v32 AS DEGREE"));
            dave.execute("CREATE VIEW v32 AS DEGREE");
            carol.execute("DROP VIEW v0");
            carol.execute("CREATE VIEW v32 AS DEGREE");
        }
    }

    @Test
    void tenantsAreIsolatedIncludingCanonicalKeys() {
        try (Session alice = login("alice", "s3cret-pass"); Session bob = login("bob", "hunter2-pass")) {
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
    void edgeStatisticsDoNotReachAnotherTenant() {
        try (Session alice = login("alice", "s3cret-pass"); Session bob = login("bob", "hunter2-pass")) {
            alice.executeAll("""
                    INSERT NODE Patient 'p1' {mrn: 'A-1'} AS $p;
                    INSERT NODE Patient 'p2' {mrn: 'A-2'} AS $q;
                    INSERT EDGE Encounter MEMBERS ($p AS patient, $q AS patient) AS $e;
                    """);
            bob.execute("INSERT NODE Patient 'b1' {mrn: 'B-1'}");
            long acmeEdge = alice.variables().get("e");
            long acmePatient = alice.variables().get("p");
            long missing = acmeEdge + 1_000_000;
            assertEquals("2", cell(alice.execute("MATCH NODE p:Patient WHERE p.mrn = 'A-1' RETURN overlap(@" + acmeEdge + ", @" + acmeEdge + ")")));
            assertTrue(cell(alice.execute("MATCH NODE p:Patient WHERE p.mrn = 'A-1' RETURN weight(@" + acmeEdge + ")")).startsWith("2"));
            assertTrue(cell(bob.execute("MATCH NODE p:Patient RETURN weight(@" + acmeEdge + ")")).startsWith("0"));
            for (long probe : new long[]{acmeEdge, acmePatient, missing}) {
                String failure = assertThrows(HStoreException.InvalidSchema.class,
                        () -> bob.execute("MATCH NODE p:Patient RETURN overlap(@" + probe + ", @" + probe + ")")).getMessage();
                assertEquals("atom " + probe + " does not exist", failure);
            }
        }
    }

    @Test
    void membershipsAcrossTenantsAreNotFollowed() {
        try (Session alice = login("alice", "s3cret-pass"); Session bob = login("bob", "hunter2-pass")) {
            alice.executeAll("INSERT NODE Patient 'p1' {mrn: 'A-1'} AS $p; INSERT EDGE Encounter MEMBERS ($p AS patient) AS $e;");
            bob.executeAll("INSERT NODE Patient 'b1' {mrn: 'B-1'} AS $p; INSERT EDGE Encounter MEMBERS ($p AS patient) AS $e;");
            long acmePatient = alice.variables().get("p");
            long acmeEdge = alice.variables().get("e");
            long globexEdge = bob.variables().get("e");
            database.write(writer -> {
                writer.transaction().upsert(acmeEdge, globexEdge, _ -> Incidence.of(globexEdge));
                writer.transaction().upsert(globexEdge, acmePatient, _ -> Incidence.of(acmePatient));
                return null;
            });
            List<String> expanded = alice.execute("EXPAND @" + acmeEdge + " DEPTH 3").rows().stream()
                    .map(row -> row.stream().map(QueryResult.Cell::render).collect(Collectors.joining(" "))).toList();
            assertEquals(1, expanded.size());
            assertFalse(expanded.getFirst().contains(String.valueOf(globexEdge)));
            database.read(Branch.MAIN, alice.principal(), reader -> {
                List<Long> edges = new ArrayList<>();
                reader.forEachIncidentEdge(acmePatient, edges::add);
                assertEquals(List.of(acmeEdge), edges);
                assertEquals(reader.incident(acmePatient).map(Incident::edge).toList(), edges);
                return null;
            });
        }
    }

    @Test
    void qualifyOnlyAcceptsVisibleMembers() {
        try (Session alice = login("alice", "s3cret-pass"); Session bob = login("bob", "hunter2-pass")) {
            alice.executeAll("INSERT NODE Patient 'p1' {mrn: 'A-1'} AS $p; INSERT EDGE Encounter MEMBERS ($p AS patient) AS $e;");
            bob.executeAll("INSERT NODE Patient 'b1' {mrn: 'B-1'} AS $p; INSERT EDGE Encounter MEMBERS ($p AS patient) AS $e;");
            long acmePatient = alice.variables().get("p");
            long acmeEdge = alice.variables().get("e");
            long globexPatient = bob.variables().get("p");
            long globexEdge = bob.variables().get("e");
            assertThrows(HStoreException.InvalidSchema.class, () -> bob.execute("QUALIFY @" + acmePatient + " IN @" + globexEdge + " AS OBSERVED"));
            assertThrows(HStoreException.InvalidSchema.class, () -> bob.execute("QUALIFY @" + (globexEdge + 1_000_000) + " IN @" + globexEdge + " AS OBSERVED"));
            assertThrows(HStoreException.InvalidSchema.class, () -> bob.execute("QUALIFY @" + globexPatient + " IN @" + acmeEdge + " AS OBSERVED"));
            assertThrows(HStoreException.InvalidSchema.class, () -> bob.execute("QUALIFY @" + globexPatient + " IN @" + globexPatient + " AS OBSERVED"));
            bob.execute("QUALIFY @" + globexPatient + " IN @" + globexEdge + " AS OBSERVED");
            assertEquals(1, bob.execute("MEMBERS OF @" + globexEdge).rows().size());
            assertEquals(1, alice.execute("INCIDENT TO @" + acmePatient).rows().size());
            assertEquals("1", cell(alice.execute("MATCH NODE p:Patient RETURN degree(p)")));
            alice.execute("NEIGHBORS OF @" + acmePatient);
        }
    }

    @Test
    void tenantAdminsStayInsideTheirTenant() {
        admin.executeAll("""
                CREATE USER carol PASSWORD 'c4rol-pass' TENANT acme ROLE admin;
                CREATE USER root PASSWORD 'r00t-pass' ROLE admin;
                """);
        try (Session carol = login("carol", "c4rol-pass")) {
            String foreign = assertThrows(HStoreException.InvalidSchema.class, () -> carol.execute("USE TENANT globex")).getMessage();
            String unknown = assertThrows(HStoreException.InvalidSchema.class, () -> carol.execute("USE TENANT nowhere")).getMessage();
            assertEquals(foreign, unknown);
            assertThrows(HStoreException.InvalidSchema.class, () -> carol.execute("USE TENANT default"));
            String otherUser = assertThrows(HStoreException.InvalidSchema.class, () -> carol.execute("ALTER USER root PASSWORD 'mine-mine-1'")).getMessage();
            String noUser = assertThrows(HStoreException.InvalidSchema.class, () -> carol.execute("ALTER USER nobody PASSWORD 'mine-mine-1'")).getMessage();
            assertEquals(otherUser.replace("root", "?"), noUser.replace("nobody", "?"));
            assertThrows(HStoreException.InvalidSchema.class, () -> carol.execute("DROP USER bob"));
            assertThrows(HStoreException.InvalidSchema.class, () -> carol.execute("CREATE USER mallory PASSWORD 'mallory-pass' TENANT default ROLE admin"));
            carol.execute("CREATE USER dave PASSWORD 'd4ve-pass' ROLE writer");
            assertEquals(List.of("alice", "auditor", "carol", "dave"),
                    carol.execute("SHOW USERS").rows().stream().map(row -> row.getFirst().render()).sorted().toList());
            assertEquals(List.of("acme"), carol.execute("SHOW TENANTS").rows().stream().map(row -> row.get(1).render()).toList());
            for (String engineWide : List.of("CHECKPOINT", "CREATE NODE TYPE Secret", "CREATE INDEX ON Patient(mrn)", "CREATE BRANCH mine",
                    "SHOW BRANCHES", "HISTORY", "STATS", "CREATE TENANT initech", "ALTER TENANT globex QUOTA (atoms 1)")) {
                assertThrows(HStoreException.InvalidSchema.class, () -> carol.execute(engineWide), engineWide);
            }
        }
        try (Session dave = login("dave", "d4ve-pass")) {
            assertEquals("acme", dave.execute("WHOAMI").rows().getFirst().get(1).render());
        }
        admin.executeAll("USE TENANT acme; CREATE BRANCH audit; USE TENANT default;");
        assertEquals("default", admin.execute("WHOAMI").rows().getFirst().get(1).render());
    }

    @Test
    void usersCannotProbeTenantNamesOrBranches() {
        admin.execute("CREATE BRANCH what_if");
        try (Session alice = login("alice", "s3cret-pass")) {
            String foreign = assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("USE TENANT globex")).getMessage();
            String unknown = assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("USE TENANT nowhere")).getMessage();
            assertEquals(foreign, unknown);
            alice.execute("USE TENANT acme");
            assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("USE BRANCH what_if"));
            assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("SHOW BRANCHES"));
            alice.execute("USE MAIN");
        }
    }

    @Test
    void plansDoNotDependOnOtherTenants() {
        try (Session alice = login("alice", "s3cret-pass"); Session bob = login("bob", "hunter2-pass")) {
            alice.executeAll("INSERT NODE Patient 'p1' {mrn: 'A-1'} AS $p; INSERT EDGE Encounter MEMBERS ($p AS patient) AS $e;");
            bob.executeAll("INSERT NODE Patient 'b1' {mrn: 'B-1'} AS $p; INSERT EDGE Encounter MEMBERS ($p AS patient) AS $e;");
            long acmeEdge = alice.variables().get("e");
            long acmePatient = alice.variables().get("p");
            long globexEdge = bob.variables().get("e");
            List<String> probes = List.of(
                    "EXPLAIN MATCH NODE x RETURN x",
                    "EXPLAIN MATCH EDGE x RETURN x",
                    "EXPLAIN MATCH NODE x WHERE x IN @" + globexEdge + " RETURN x",
                    "EXPLAIN MATCH NODE x:Patient WHERE x.mrn = 'B-1' RETURN x");
            List<String> before = probes.stream().map(probe -> bob.render(bob.execute(probe))).toList();
            for (int i = 0; i < 20; i++) {
                admin.execute("INSERT NODE Patient 'd" + i + "' {mrn: 'D-" + i + "'}");
            }
            admin.execute("INSERT NODE Patient 'members' {mrn: 'M'} AS $m; INSERT EDGE Encounter MEMBERS ($m AS patient)");
            for (int i = 0; i < 10; i++) {
                alice.execute("MATCH NODE x WHERE x IN @" + acmeEdge + " RETURN x");
                alice.execute("MATCH EDGE e WHERE e CONTAINS (@" + acmePatient + ") RETURN e");
            }
            assertTrue(bob.execute("MATCH NODE x WHERE x IN @" + acmeEdge + " RETURN x").rows().isEmpty());
            assertTrue(bob.execute("MATCH EDGE e WHERE e CONTAINS (@" + acmePatient + ") RETURN e").rows().isEmpty());
            assertEquals(before, probes.stream().map(probe -> bob.render(bob.execute(probe))).toList());
        }
    }

    @Test
    void evidenceOnlyReferencesVisibleRecords() {
        try (Session alice = login("alice", "s3cret-pass"); Session bob = login("bob", "hunter2-pass")) {
            alice.executeAll("""
                    INSERT NODE Patient 'p1' {mrn: 'A-1'} AS $p;
                    EVIDENCE 'chart' SOURCE $p AS $ev;
                    """);
            bob.executeAll("INSERT NODE Patient 'b1' {mrn: 'B-1'} AS $p; INSERT EDGE Encounter MEMBERS ($p AS patient) AS $e;");
            long acmePatient = alice.variables().get("p");
            long acmeEvidence = alice.variables().get("ev");
            assertThrows(HStoreException.InvalidSchema.class, () -> bob.execute("EVIDENCE 'probe' SOURCE @" + acmePatient));
            assertThrows(HStoreException.InvalidSchema.class, () -> bob.execute("QUALIFY $p IN $e AS OBSERVED EVIDENCE (@" + acmeEvidence + ")"));
            bob.execute("EVIDENCE 'own' SOURCE $p AS $own; QUALIFY $p IN $e AS OBSERVED EVIDENCE ($own);");
        }
    }

    @Test
    void failedAuthenticationsInsideASessionAreCapped() {
        try (Session alice = login("alice", "s3cret-pass")) {
            for (int i = 0; i < Session.MAX_FAILED_AUTHENTICATIONS; i++) {
                assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("AUTHENTICATE bob PASSWORD 'guess'"));
            }
            HStoreException.InvalidSchema capped = assertThrows(HStoreException.InvalidSchema.class,
                    () -> alice.execute("AUTHENTICATE bob PASSWORD 'hunter2-pass'"));
            assertTrue(capped.getMessage().contains("too many"), capped.getMessage());
            assertEquals("acme", alice.execute("WHOAMI").rows().getFirst().get(1).render());
        }
    }

    @Test
    void mergeAndDiffSeeEveryTenantsEdges() {
        admin.execute("CREATE BRANCH what_if");
        try (Session alice = login("alice", "s3cret-pass")) {
            alice.executeAll("INSERT NODE Patient 'p1' {mrn: 'A-1'} AS $p; INSERT EDGE Encounter MEMBERS ($p AS patient);");
        }
        admin.executeAll("USE BRANCH what_if; INSERT NODE Patient 'n' {mrn: 'N-1'}; USE MAIN;");
        assertFalse(admin.execute("DIFF BRANCH what_if AND main").rows().isEmpty());
        admin.execute("MERGE BRANCH what_if");
        assertEquals("1", cell(admin.execute("MATCH NODE p:Patient WHERE p.mrn = 'N-1' RETURN count(*)")));
    }

    @Test
    void rolesAndAuthenticationAreEnforced() {
        assertFalse(new Session(database).authenticate("alice", "wrong"));
        try (Session auditor = login("auditor", "readonly"); Session alice = login("alice", "s3cret-pass")) {
            assertThrows(HStoreException.InvalidSchema.class, () -> auditor.execute("INSERT NODE Patient 'x' {mrn: 'x'}"));
            assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("CREATE NODE TYPE Forbidden"));
            assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("USE TENANT globex"));
            HStoreException.InvalidSchema denied = assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("CHECKPOINT AT 64MB"));
            assertTrue(denied.getMessage().contains("ADMIN"), denied.getMessage());
            assertEquals(CheckpointThreshold.Source.AUTOMATIC, database.engine().checkpointThreshold().source());
            assertEquals("acme", alice.execute("WHOAMI").rows().getFirst().get(1).render());
        }
        assertTrue(database.requiresAuthentication());
    }

    @Test
    void quotasAreExactUnderTheirLimit() {
        try (Session alice = login("alice", "s3cret-pass")) {
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

    private static String vector(int dimensions) {
        return IntStream.range(0, dimensions).mapToObj(i -> String.valueOf(i % 7 + 1)).collect(Collectors.joining(", ", "[", "]"));
    }

    private void awaitIndexed() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (database.semantic().indexedGeneration() < database.engine().feed().lastGeneration() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(database.semantic().indexedGeneration() >= database.engine().feed().lastGeneration(), "the semantic index stopped advancing");
    }

    private String nearestMrn(Session session, String text) {
        return cell(session.execute("MATCH NODE p:Patient WHERE p SIMILAR TO '" + text + "' TOP 1 SNAPSHOT RETURN p.mrn"));
    }

    @Test
    void wrongLengthVectorsCannotStallTheSharedIndex() throws InterruptedException {
        try (Session alice = login("alice", "s3cret-pass"); Session bob = login("bob", "hunter2-pass")) {
            bob.executeAll("INSERT NODE Patient 'b1' {mrn: 'B-1'} AS $p; EMBED $p TEXT 'chronic kidney disease';");
            alice.execute("INSERT NODE Patient 'a1' {mrn: 'A-1'} AS $p");
            assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("EMBED $p VECTOR " + vector(257) + " MODEL 'hashing-256'"));
            assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("EMBED $p VECTOR " + vector(3) + " MODEL 'hashing-256'"));
            assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("EMBED $p VECTOR [1e39, 0.5] MODEL 'hashing-256'"));
            bob.executeAll("INSERT NODE Patient 'b2' {mrn: 'B-2'} AS $q; EMBED $q TEXT 'fractured wrist after a fall';");
            awaitIndexed();
            assertEquals("B-2", nearestMrn(bob, "fractured wrist"));
            assertEquals("B-1", nearestMrn(bob, "kidney disease"));
        }
    }

    @Test
    void nanAndEmptyVectorsAreRejected() {
        Principal bob = database.authenticate("bob", "hunter2-pass").orElseThrow();
        long patient = database.write(database.transactionOptions(bob), bob, writer -> writer.node("Patient", "b1", Map.of("mrn", "B-1")));
        float[] poisoned = new float[256];
        poisoned[7] = Float.NaN;
        assertThrows(HStoreException.InvalidSchema.class, () -> database.write(database.transactionOptions(bob), bob,
                writer -> { writer.embed(patient, "hashing-256", 1, poisoned); return null; }));
        assertThrows(HStoreException.InvalidSchema.class, () -> database.write(database.transactionOptions(bob), bob,
                writer -> { writer.embed(patient, "hashing-256", 1, new float[0]); return null; }));
        assertTrue(database.read(reader -> reader.embedding(patient)).isEmpty());
    }

    @Test
    void corruptEmbeddingRowsAreSkippedByTheIndex() throws InterruptedException, IOException {
        try (Session bob = login("bob", "hunter2-pass")) {
            bob.executeAll("""
                    INSERT NODE Patient 'b1' {mrn: 'B-1'} AS $p;
                    EMBED $p TEXT 'chronic kidney disease';
                    INSERT NODE Patient 'b2' {mrn: 'B-2'} AS $q;
                    """);
            long corrupt = bob.variables().get("q");
            int model = database.semantic().model(database.semantic().encoder().model());
            database.write(writer -> {
                long ref = writer.writePayload(Embedding.encode(new float[257]));
                writer.transaction().put(Embedding.EMBEDDINGS, corrupt, new Embedding(model, 1, 257, ref));
                return null;
            });
            bob.executeAll("INSERT NODE Patient 'b3' {mrn: 'B-3'} AS $r; EMBED $r TEXT 'fractured wrist after a fall';");
            awaitIndexed();
            assertEquals("B-3", nearestMrn(bob, "fractured wrist"));
        }
        admin.close();
        database.close();
        try (Stream<Path> files = Files.walk(directory.resolve("semantic"))) {
            files.sorted(Comparator.reverseOrder()).forEach(file -> file.toFile().delete());
        }
        database = HypergraphDatabase.open(directory, options());
        admin = new Session(database);
        try (Session bob = login("bob", "hunter2-pass")) {
            assertEquals("B-1", nearestMrn(bob, "kidney disease"));
        }
    }

    @Test
    void onlyGlobalAdminsCreateEmbeddingModels() {
        try (Session alice = login("alice", "s3cret-pass")) {
            alice.execute("INSERT NODE Patient 'a1' {mrn: 'A-1'} AS $p");
            assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("EMBED $p VECTOR [0.1, 0.9, 0.2] MODEL 'custom-3d'"));
            assertTrue(database.semantic().existingModel("custom-3d").isEmpty());
            admin.executeAll("""
                    INSERT NODE Patient 'd1' {mrn: 'D-1'} AS $d;
                    EMBED $d VECTOR [0.1, 0.9, 0.2] MODEL 'custom-3d';
                    """);
            alice.execute("EMBED $p VECTOR [0.3, 0.1, 0.2] MODEL 'custom-3d'");
            assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("EMBED $p VECTOR [0.3, 0.1] MODEL 'custom-3d'"));
            assertThrows(HStoreException.InvalidSchema.class, () -> admin.execute("EMBED $d VECTOR [0.1] MODEL '" + "m".repeat(129) + "'"));
        }
    }

    @Test
    void oversizedNamesAreRefused() {
        String huge = "x".repeat(10_000);
        try (Session bob = login("bob", "hunter2-pass")) {
            bob.execute("INSERT NODE Patient 'b1' {mrn: 'B-1'} AS $p");
            long patient = bob.variables().get("p");
            assertThrows(HStoreException.InvalidSchema.class, () -> bob.execute("SET $p." + huge + " = 1"));
            assertThrows(HStoreException.InvalidSchema.class, () -> bob.execute("STATE $p = 1 SCHEMA '" + huge + "'"));
            Principal principal = database.authenticate("bob", "hunter2-pass").orElseThrow();
            assertThrows(HStoreException.InvalidSchema.class, () -> database.write(database.transactionOptions(principal), principal,
                    writer -> { writer.set(patient, huge, Value.of(1)); return null; }));
            assertTrue(database.schema().existingPropertyKey(huge).isEmpty());
            bob.execute("SET $p." + "y".repeat(128) + " = 1");
        }
    }

    @Test
    void sessionsEndWhenTheirUserIsDroppedOrItsPasswordChanges() {
        admin.execute("CREATE USER carol PASSWORD 'c4rol-pass' TENANT acme ROLE admin");
        try (Session alice = login("alice", "s3cret-pass"); Session carol = login("carol", "c4rol-pass"); Session other = login("carol", "c4rol-pass")) {
            alice.execute("INSERT NODE Patient 'p1' {mrn: 'A-1'}");
            carol.execute("ALTER USER carol PASSWORD 'n3w-pass-1'");
            carol.execute("MATCH NODE p:Patient RETURN count(*)");
            assertThrows(HStoreException.InvalidSchema.class, () -> other.execute("MATCH NODE p:Patient RETURN count(*)"));
            carol.execute("ALTER USER alice PASSWORD 'rotated-pass'");
            HStoreException.InvalidSchema ended = assertThrows(HStoreException.InvalidSchema.class,
                    () -> alice.execute("MATCH NODE p:Patient RETURN count(*)"));
            assertTrue(ended.getMessage().contains("authenticate again"), ended.getMessage());
            assertThrows(HStoreException.InvalidSchema.class, () -> alice.execute("INSERT NODE Patient 'p2' {mrn: 'A-2'}"));
            alice.execute("AUTHENTICATE alice PASSWORD 'rotated-pass'");
            assertEquals("1", cell(alice.execute("MATCH NODE p:Patient RETURN count(*)")));
            carol.execute("BEGIN");
            carol.execute("ALTER USER carol PASSWORD 'again-pass'");
            carol.execute("COMMIT");
            carol.execute("MATCH NODE p:Patient RETURN count(*)");
            admin.execute("DROP USER carol");
            assertThrows(HStoreException.InvalidSchema.class, () -> carol.execute("SHOW USERS"));
        }
    }

    @Test
    void tenantAdminsCannotRemoveTheLastUser() throws IOException {
        Path other = directory.resolve("lonely");
        Files.createDirectories(other);
        try (HypergraphDatabase lonely = HypergraphDatabase.open(other, options()); Session system = new Session(lonely)) {
            system.executeAll("CREATE TENANT acme; CREATE USER carol PASSWORD 'c4rol-pass' TENANT acme ROLE admin; CREATE USER dave PASSWORD 'd4ve-pass' TENANT acme ROLE writer;");
            try (Session carol = new Session(lonely)) {
                assertTrue(carol.authenticate("carol", "c4rol-pass"));
                carol.execute("DROP USER dave");
                HStoreException.InvalidSchema refused = assertThrows(HStoreException.InvalidSchema.class, () -> carol.execute("DROP USER carol"));
                assertTrue(refused.getMessage().contains("last user"), refused.getMessage());
            }
            assertTrue(lonely.requiresAuthentication());
            system.execute("DROP USER carol");
            assertFalse(lonely.requiresAuthentication());
        }
    }

    @Test
    void signInsAreThrottledPerUserAndAddress() {
        for (int i = 0; i < SignIns.ACCOUNT_LIMIT; i++) {
            try (Session attempt = new Session(database)) {
                attempt.origin("10.0.0.1");
                assertFalse(attempt.authenticate("bob", "guess" + i));
            }
        }
        try (Session attacker = new Session(database)) {
            attacker.origin("10.0.0.1");
            HStoreException.ResourceLimit throttled = assertThrows(HStoreException.ResourceLimit.class,
                    () -> attacker.authenticate("bob", "hunter2-pass"));
            assertTrue(throttled.getMessage().contains("too many failed sign-in attempts"), throttled.getMessage());
            assertTrue(attacker.authenticate("alice", "s3cret-pass"));
        }
        try (Session owner = new Session(database)) {
            owner.origin("10.0.0.2");
            assertTrue(owner.authenticate("bob", "hunter2-pass"));
        }
        for (int i = 0; i < 3 * SignIns.ACCOUNT_LIMIT; i++) {
            try (Session attempt = new Session(database)) {
                attempt.origin("10.1.0." + i);
                assertFalse(attempt.authenticate("alice", "guess" + i));
            }
        }
        try (Session owner = new Session(database)) {
            owner.origin("10.2.0.1");
            assertTrue(owner.authenticate("alice", "s3cret-pass"));
        }
        AtomicLong clock = new AtomicLong();
        SignIns signIns = new SignIns(3, 2, Duration.ofMinutes(1), clock::get);
        Optional<String> origin = Optional.of("203.0.113.9");
        assertTrue(signIns.attempt("x", origin, Optional::empty).isEmpty());
        assertTrue(signIns.attempt("y", origin, Optional::empty).isEmpty());
        assertThrows(HStoreException.ResourceLimit.class, () -> signIns.attempt("z", origin, () -> Optional.of("never")));
        assertEquals(Optional.of("ok"), signIns.attempt("z", Optional.of("198.51.100.1"), () -> Optional.of("ok")));
        clock.addAndGet(Duration.ofMinutes(2).toNanos());
        assertEquals(Optional.of("ok"), signIns.attempt("z", origin, () -> Optional.of("ok")));
        SignIns accounts = new SignIns(3, 100, Duration.ofMinutes(1), clock::get);
        for (int i = 0; i < 3; i++) {
            assertTrue(accounts.attempt("u", origin, Optional::empty).isEmpty());
        }
        assertThrows(HStoreException.ResourceLimit.class, () -> accounts.attempt("u", origin, () -> Optional.of("never")));
        assertEquals(Optional.of("ok"), accounts.attempt("u", Optional.of("198.51.100.1"), () -> Optional.of("ok")));
        assertEquals(Optional.of("ok"), accounts.attempt("v", origin, () -> Optional.of("ok")));
        for (int i = 0; i < 3; i++) {
            assertTrue(accounts.attempt("local", Optional.empty(), Optional::empty).isEmpty());
        }
        assertThrows(HStoreException.ResourceLimit.class, () -> accounts.attempt("local", Optional.empty(), () -> Optional.of("never")));
    }

    @Test
    void passwordsNeedEightToAThousandCharacters() {
        for (String password : new String[]{"", "short", "seven77", "x".repeat(1025)}) {
            assertThrows(HStoreException.InvalidSchema.class, () -> admin.execute("CREATE USER weak PASSWORD '" + password + "'"), password);
            assertThrows(HStoreException.InvalidSchema.class, () -> admin.execute("ALTER USER alice PASSWORD '" + password + "'"), password);
        }
        admin.execute("CREATE USER strong PASSWORD 'eight888'");
        admin.execute("CREATE USER emoji PASSWORD '" + "\uD83D\uDD12".repeat(8) + "'");
        assertTrue(new Session(database).authenticate("strong", "eight888"));
        assertTrue(new Session(database).authenticate("alice", "s3cret-pass"));
    }

    @Test
    void extremeInputsFailCleanly() {
        try (Session alice = login("alice", "s3cret-pass")) {
            for (String statement : List.of(
                    "INSERT NODE Patient 'd' {mrn: 'd', amount: DECIMAL '1e2000000000'}",
                    "INSERT NODE Patient 'd' {mrn: 'd', amount: " + "9".repeat(5_000) + "}",
                    "MATCH NODE p:Patient WHERE " + "NOT ".repeat(10_000) + "p.mrn = 'x' RETURN p",
                    "MATCH NODE p:Patient WHERE " + "(".repeat(10_000) + "p.mrn = 'x'" + ")".repeat(10_000) + " RETURN p",
                    "MATCH NODE p:Patient WHERE p SIMILAR TO 'x' TOP 0 RETURN p",
                    "MATCH NODE p:Patient WHERE p SIMILAR TO 'x' TOP 100000000 RETURN p",
                    "MATCH NODE p:Patient WHERE p SIMILAR TO 'x' TOP 4294967297 RETURN p",
                    "EXPAND @1 DEPTH 0", "EXPAND @1 DEPTH 1000000", "EXPAND @1 DEPTH 4294967300",
                    "TRACE @1 DEPTH -1", "SET FORMAT xml", "DESCRIBE @" + Long.MAX_VALUE, "MEMBERS OF @-1")) {
                assertThrows(HStoreException.class, () -> alice.execute(statement), statement);
            }
            assertThrows(HStoreException.class, () -> admin.execute("CREATE USER mallory PASSWORD 'x-password' ROLE superuser"));
            alice.execute("INSERT NODE Patient 'j' {mrn: 'j'}");
            assertThrows(HStoreException.class, () -> alice.execute("SET @Patient:'j'.doc = JSON '" + "[".repeat(100_000) + "'"));
            assertEquals("0", cell(alice.execute("MATCH NODE p:Patient WHERE p.mrn = 'x' OR " + "NOT ".repeat(201) + "p.mrn = 'j' RETURN count(*)")));
            assertEquals("0", cell(alice.execute("MATCH NODE p:Patient WHERE p IN @" + Long.MAX_VALUE + " OR p IN @-1 OR p IN @0 RETURN count(*)")));
        }
        assertThrows(HStoreException.class, () -> Json.parse("[".repeat(100_000)));
        assertThrows(HStoreException.class, () -> Json.parse("{\"n\": 1e999999999}"));
    }

    @Test
    void rolledBackPayloadsStillCountAgainstTheQuota() {
        admin.execute("ALTER TENANT globex QUOTA (payload_bytes 10000)");
        String large = "x".repeat(3_000);
        try (Session bob = login("bob", "hunter2-pass")) {
            bob.execute("INSERT NODE Patient 'p' {mrn: 'p'}");
            for (int i = 0; i < 3; i++) {
                bob.executeAll("BEGIN; SET @Patient:'p'.note = '" + large + "'; ROLLBACK;");
            }
            assertThrows(HStoreException.ResourceLimit.class, () -> bob.executeAll("BEGIN; SET @Patient:'p'.note = '" + large + "'; ROLLBACK;"));
        }
        assertTrue(admin.execute("SHOW TENANTS").rows().stream()
                .anyMatch(row -> row.get(1).render().equals("globex") && Long.parseLong(row.get(4).render()) >= 9_000));
    }

    @Test
    void membershipsLeftAcrossTenantsStayInvisible() {
        long foreignEdge;
        long acmePatient;
        try (Session alice = login("alice", "s3cret-pass"); Session bob = login("bob", "hunter2-pass")) {
            alice.execute("INSERT NODE Patient 'a' {mrn: 'A'} AS $a");
            acmePatient = alice.variables().get("a");
            bob.executeAll("INSERT NODE Patient 'b' {mrn: 'B'} AS $b; INSERT EDGE Encounter MEMBERS ($b AS patient) AS $e;");
            foreignEdge = bob.variables().get("e");
        }
        database.write(writer -> {
            writer.transaction().insert(foreignEdge, new Incidence(acmePatient, Dictionary.NO_ROLES, Weight.ONE,
                    Validity.ALWAYS.from(), Validity.ALWAYS.to(), 0, 0));
            return null;
        });
        try (Session alice = login("alice", "s3cret-pass")) {
            assertEquals("0", cell(alice.execute("MATCH NODE p:Patient WHERE p IN @" + foreignEdge + " RETURN count(*)")));
            assertEquals("0", cell(alice.execute("MATCH EDGE e WHERE e CONTAINS (@" + acmePatient + ") AND e = @" + foreignEdge + " RETURN count(*)")));
            assertEquals("0", cell(alice.execute("MATCH NODE p:Patient WHERE p VALID AT 0 RETURN count(*)")));
            assertTrue(alice.execute("NEIGHBORS OF @" + acmePatient).rows().isEmpty());
            assertEquals("0", cell(alice.execute("MATCH NODE p:Patient RETURN degree(p)")));
            assertTrue(alice.execute("INCIDENT TO @" + acmePatient).rows().stream()
                    .noneMatch(row -> row.stream().anyMatch(column -> column.render().contains(String.valueOf(foreignEdge)))));
        }
    }

    @Test
    void untypedMatchesReadOnlyTheTenantsOwnAtoms() {
        try (Session alice = login("alice", "s3cret-pass"); Session bob = login("bob", "hunter2-pass")) {
            alice.executeAll("INSERT NODE Patient 'a1' {mrn: 'A1'}; INSERT NODE Patient 'a2' {mrn: 'A2'};");
            QueryResult before = alice.execute("MATCH NODE n RETURN count(*)");
            for (int batch = 0; batch < 20; batch++) {
                int start = batch * 100;
                bob.execute(IntStream.range(start, start + 100).mapToObj(i -> "INSERT NODE Patient 'b" + i + "' {mrn: 'b'};")
                        .collect(Collectors.joining()));
            }
            QueryResult after = alice.execute("MATCH NODE n RETURN count(*)");
            assertEquals("2", cell(after));
            assertEquals(before.trace().orElseThrow().pagesRead() + before.trace().orElseThrow().cacheHits(),
                    after.trace().orElseThrow().pagesRead() + after.trace().orElseThrow().cacheHits());
        }
    }

    @Test
    void qualifiersMustBeTheWritersOwnAssertions() {
        try (Session bob = login("bob", "hunter2-pass")) {
            bob.executeAll("""
                    INSERT NODE Patient 'b' {mrn: 'B'} AS $b;
                    INSERT EDGE Encounter MEMBERS ($b AS patient) AS $e;
                    QUALIFY $b IN $e AS OBSERVED;
                    """);
        }
        long foreignAssertion = database.read(reader -> reader.view().scan(io.hstore.db.evidence.Provenance.QUALIFIERS).keys().findFirst()).orElseThrow();
        try (Session alice = login("alice", "s3cret-pass")) {
            alice.executeAll("INSERT NODE Patient 'a' {mrn: 'A'} AS $a; INSERT EDGE Encounter MEMBERS ($a AS patient) AS $e;");
            long patient = alice.variables().get("a");
            long edge = alice.variables().get("e");
            Principal acmeWriter = new Principal("alice", tenant("acme"), io.hstore.db.security.Role.WRITER);
            HStoreException.InvalidSchema refused = assertThrows(HStoreException.InvalidSchema.class, () -> database.write(TxnOptions.defaults(), acmeWriter, writer -> {
                writer.qualify(edge, patient, foreignAssertion);
                return null;
            }));
            assertTrue(refused.getMessage().contains("does not exist"), refused.getMessage());
        }
    }

    private int tenant(String name) {
        return database.read(reader -> io.hstore.db.security.Security.tenant(reader.view(), name)).orElseThrow().id();
    }
}
