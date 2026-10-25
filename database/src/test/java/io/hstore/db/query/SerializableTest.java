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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializableTest {

    private static final String ON_CALL = "MATCH NODE n:Account WHERE n.oncall = true RETURN count(*)";
    private static final String BOOKED_R1 = "MATCH EDGE e:Booking WHERE e HAS @Room:'r1' RETURN count(*)";

    @TempDir
    Path directory;

    HypergraphDatabase database;
    Session admin;
    Session a;
    Session b;

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        admin = new Session(database);
        admin.executeAll("""
                CREATE NODE TYPE Account (balance INT, oncall BOOL INDEXED);
                CREATE NODE TYPE Room;
                CREATE NODE TYPE Note;
                CREATE SET EDGE TYPE Booking ROLES (room, guest);
                INSERT NODE Account 'x' {balance: 100, oncall: true};
                INSERT NODE Account 'y' {balance: 100, oncall: true};
                INSERT NODE Room 'r1';
                INSERT NODE Room 'r2';
                INSERT EDGE Booking MEMBERS (@Account:'x' AS guest) AS $bx;
                INSERT EDGE Booking MEMBERS (@Account:'y' AS guest) AS $by;
                """);
        a = new Session(database);
        b = new Session(database);
    }

    @AfterEach
    void close() {
        a.close();
        b.close();
        admin.close();
        database.close();
    }

    private static String cell(QueryResult result) {
        return result.rows().getFirst().getFirst().render();
    }

    private String count(String query) {
        return cell(admin.execute(query));
    }

    private void begin(boolean serializable) {
        a.execute(serializable ? "BEGIN SERIALIZABLE" : "BEGIN");
        b.execute(serializable ? "BEGIN SERIALIZABLE" : "BEGIN");
    }

    private List<Optional<HStoreException.Conflict>> commitBoth() {
        List<Optional<HStoreException.Conflict>> outcomes = new ArrayList<>();
        for (Session session : List.of(a, b)) {
            try {
                session.execute("COMMIT");
                outcomes.add(Optional.empty());
            } catch (HStoreException.Conflict conflict) {
                assertTrue(conflict.retryable());
                assertEquals(HStoreException.Code.RETRYABLE_CONFLICT, conflict.code());
                outcomes.add(Optional.of(conflict));
            }
        }
        return outcomes;
    }

    private static String onlySecondFails(List<Optional<HStoreException.Conflict>> outcomes) {
        assertTrue(outcomes.get(0).isEmpty(), "the first commit should succeed");
        assertTrue(outcomes.get(1).isPresent(), "the second commit should fail with a retryable conflict");
        return outcomes.get(1).get().getMessage();
    }

    private static void bothCommit(List<Optional<HStoreException.Conflict>> outcomes) {
        assertEquals(List.of(Optional.empty(), Optional.empty()), outcomes);
    }

    @Test
    void writeSkewThroughAnIndexedPredicateIsRejected() {
        begin(true);
        assertEquals("2", cell(a.execute(ON_CALL)));
        assertEquals("2", cell(b.execute(ON_CALL)));
        a.execute("SET @Account:'x'.oncall = false");
        b.execute("SET @Account:'y'.oncall = false");
        String message = onlySecondFails(commitBoth());
        assertTrue(message.contains("serializable read of property-index"), message);
        assertEquals("1", count(ON_CALL));
    }

    @Test
    void indexedPredicatesIgnoreChangesOutsideTheirRange() {
        a.execute("BEGIN SERIALIZABLE");
        assertEquals("2", cell(a.execute(ON_CALL)));
        a.execute("INSERT NODE Note 'checked'");
        admin.execute("INSERT NODE Account 'z' {balance: 5, oncall: false}");
        admin.execute("SET @Account:'x'.balance = 50");
        assertDoesNotThrow(() -> a.execute("COMMIT"));
    }

    @Test
    void snapshotAllowsWriteSkewThroughAnIndexedPredicate() {
        begin(false);
        assertEquals("2", cell(a.execute(ON_CALL)));
        assertEquals("2", cell(b.execute(ON_CALL)));
        a.execute("SET @Account:'x'.oncall = false");
        b.execute("SET @Account:'y'.oncall = false");
        bothCommit(commitBoth());
        assertEquals("0", count(ON_CALL));
    }

    @Test
    void snapshotStillPreventsLostUpdates() {
        begin(false);
        long first = Long.parseLong(cell(a.execute("MATCH NODE n:Account WHERE n = @Account:'x' RETURN n.balance")));
        long second = Long.parseLong(cell(b.execute("MATCH NODE n:Account WHERE n = @Account:'x' RETURN n.balance")));
        a.execute("SET @Account:'x'.balance = " + (first + 10));
        b.execute("SET @Account:'x'.balance = " + (second + 20));
        onlySecondFails(commitBoth());
        assertEquals("110", count("MATCH NODE n:Account WHERE n = @Account:'x' RETURN n.balance"));
    }

    @Test
    void phantomThroughIncidenceIsRejected() {
        long bx = admin.variables().get("bx");
        long by = admin.variables().get("by");
        begin(true);
        assertEquals("0", cell(a.execute(BOOKED_R1)));
        assertEquals("0", cell(b.execute(BOOKED_R1)));
        a.execute("ADD @Room:'r1' TO @" + bx + " AS room");
        b.execute("ADD @Room:'r1' TO @" + by + " AS room");
        String message = onlySecondFails(commitBoth());
        assertTrue(message.contains("incident edges of atom"), message);
        assertEquals("1", count(BOOKED_R1));
    }

    @Test
    void phantomThroughARoleFilteredMatchIsRejected() {
        long bx = admin.variables().get("bx");
        long by = admin.variables().get("by");
        String booked = "MATCH EDGE e:Booking WHERE e HAS @Room:'r1' AS room AND e HAS @Room:'r1' RETURN count(*)";
        begin(true);
        assertEquals("0", cell(a.execute(booked)));
        assertEquals("0", cell(b.execute(booked)));
        a.execute("ADD @Room:'r1' TO @" + bx + " AS room");
        b.execute("ADD @Room:'r1' TO @" + by + " AS room");
        String message = onlySecondFails(commitBoth());
        assertTrue(message.contains("incident edges of atom"), message);
        assertEquals("1", count(booked));
    }

    @Test
    void countThenInsertThroughIncidenceIsRejected() {
        begin(true);
        assertEquals("0", cell(a.execute(BOOKED_R1)));
        assertEquals("0", cell(b.execute(BOOKED_R1)));
        a.execute("INSERT EDGE Booking MEMBERS (@Room:'r1' AS room, @Account:'x' AS guest)");
        b.execute("INSERT EDGE Booking MEMBERS (@Room:'r1' AS room, @Account:'y' AS guest)");
        onlySecondFails(commitBoth());
        assertEquals("1", count(BOOKED_R1));
    }

    @Test
    void countThenInsertByTypeIsRejected() {
        begin(true);
        assertEquals("2", cell(a.execute("MATCH NODE n:Room RETURN count(*)")));
        assertEquals("2", cell(b.execute("MATCH NODE n:Room RETURN count(*)")));
        a.execute("INSERT NODE Room 'ra'");
        b.execute("INSERT NODE Room 'rb'");
        String message = onlySecondFails(commitBoth());
        assertTrue(message.contains("atoms of type"), message);
        assertEquals("3", count("MATCH NODE n:Room RETURN count(*)"));
    }

    @Test
    void snapshotAllowsCountThenInsertPhantoms() {
        begin(false);
        assertEquals("2", cell(a.execute("MATCH NODE n:Room RETURN count(*)")));
        assertEquals("2", cell(b.execute("MATCH NODE n:Room RETURN count(*)")));
        a.execute("INSERT NODE Room 'ra'");
        b.execute("INSERT NODE Room 'rb'");
        bothCommit(commitBoth());
        assertEquals("4", count("MATCH NODE n:Room RETURN count(*)"));
    }

    @Test
    void absentCanonicalKeyReadsAreInvalidatedByTheirInsert() {
        begin(true);
        assertThrows(HStoreException.InvalidSchema.class, () -> a.execute("MATCH NODE n:Room WHERE n = @Room:'r4' RETURN count(*)"));
        assertThrows(HStoreException.InvalidSchema.class, () -> b.execute("MATCH NODE n:Room WHERE n = @Room:'r3' RETURN count(*)"));
        a.execute("INSERT NODE Room 'r3'");
        b.execute("INSERT NODE Room 'r4'");
        String message = onlySecondFails(commitBoth());
        assertTrue(message.contains("canonical key"), message);
        assertEquals("3", count("MATCH NODE n:Room RETURN count(*)"));
    }

    @Test
    void insertsOfTypesNeitherTransactionScannedDoNotConflict() {
        begin(true);
        a.execute("INSERT NODE Room 'ra'");
        b.execute("INSERT NODE Account 'z' {balance: 5, oncall: false}");
        bothCommit(commitBoth());
        assertEquals("3", count("MATCH NODE n:Room RETURN count(*)"));
        assertEquals("3", count("MATCH NODE n:Account RETURN count(*)"));
    }

    @Test
    void disjointPostingListsDoNotConflict() {
        begin(true);
        assertEquals("0", cell(a.execute(BOOKED_R1)));
        assertEquals("0", cell(b.execute("MATCH EDGE e:Booking WHERE e HAS @Room:'r2' RETURN count(*)")));
        a.execute("INSERT EDGE Booking MEMBERS (@Room:'r1' AS room, @Account:'x' AS guest)");
        b.execute("INSERT EDGE Booking MEMBERS (@Room:'r2' AS room, @Account:'y' AS guest)");
        bothCommit(commitBoth());
        assertEquals("4", count("MATCH EDGE e:Booking RETURN count(*)"));
    }

    @Test
    void planEstimatesAreNotRecorded() {
        long bx = admin.variables().get("bx");
        a.execute("BEGIN SERIALIZABLE");
        assertTrue(a.execute("EXPLAIN " + ON_CALL).message().contains("IndexRange"));
        assertTrue(a.execute("EXPLAIN " + BOOKED_R1).message().contains("IncidentIntersection"));
        String plan = a.execute("EXPLAIN MATCH NODE n:Account WHERE n = @Account:'x' AND n.oncall = true RETURN n.balance").message();
        assertTrue(plan.startsWith("Lookup") && plan.contains("rejected IndexRange"), plan);
        assertEquals("100", cell(a.execute("MATCH NODE n:Account WHERE n = @Account:'x' AND n.oncall = true RETURN n.balance")));
        a.execute("SET @Account:'x'.balance = 7");
        admin.execute("SET @Account:'y'.oncall = false");
        admin.execute("ADD @Room:'r1' TO @" + bx + " AS room");
        admin.execute("INSERT NODE Account 'w' {balance: 1, oncall: true}");
        assertDoesNotThrow(() -> a.execute("COMMIT"));
        assertEquals("7", count("MATCH NODE n:Account WHERE n = @Account:'x' RETURN n.balance"));
    }

    @Test
    void resultsThatUseTheEstimatedReadsAreStillRecorded() {
        long bx = admin.variables().get("bx");
        a.execute("BEGIN SERIALIZABLE");
        assertEquals("0", cell(a.execute("MATCH NODE n:Room WHERE n = @Room:'r1' RETURN degree(n)")));
        a.execute("SET @Account:'x'.balance = 7");
        admin.execute("ADD @Room:'r1' TO @" + bx + " AS room");
        HStoreException.Conflict conflict = assertThrows(HStoreException.Conflict.class, () -> a.execute("COMMIT"));
        assertTrue(conflict.getMessage().contains("incident edges of atom"), conflict.getMessage());
    }
}
