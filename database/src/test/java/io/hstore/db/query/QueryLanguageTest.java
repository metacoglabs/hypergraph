// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.query;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Member;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryLanguageTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;
    Session session;

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults()
                .withEngine(EngineOptions.defaults().withPageSize(4096).withHistoryLimit(500)));
        session = new Session(database);
        session.executeAll("""
                CREATE NODE TYPE Person (name STRING INDEXED REQUIRED, age INT INDEXED);
                CREATE NODE TYPE Drug (name STRING);
                CREATE SET EDGE TYPE Claim (amount FLOAT INDEXED) ROLES (buyer, seller, provider, approver);
                CREATE ORDERED EDGE TYPE Pathway;
                INSERT NODE Person 'alice' {name: 'Alice', age: 34} AS $alice;
                INSERT NODE Person 'bob' {name: 'Bob', age: 41} AS $bob;
                INSERT NODE Person 'carol' {name: 'Carol', age: 29} AS $carol;
                INSERT NODE Drug 'aspirin' {name: 'Aspirin'} AS $aspirin;
                INSERT EDGE Claim {amount: 120.5} MEMBERS (@Person:'alice' AS buyer|approver WEIGHT 0.5, $bob AS seller, $aspirin) AS $c1;
                INSERT EDGE Claim {amount: 80.0} MEMBERS ($alice AS buyer, $carol AS provider) AS $c2;
                INSERT EDGE Claim {amount: 15.0} MEMBERS ($carol, $bob) AS $c3;
                INSERT EDGE Pathway MEMBERS ($alice, $bob, $carol) AS $path;
                """);
    }

    @AfterEach
    void close() {
        session.close();
        database.close();
    }

    private List<String> column(QueryResult result, int index) {
        return result.rows().stream().map(row -> row.get(index).render()).toList();
    }

    @Test
    void matchUsesIncidenceIntersectionAndVerifiesExactly() {
        QueryResult claims = session.execute("MATCH EDGE c:Claim WHERE c CONTAINS ($alice, $bob) RETURN c.amount, card(c)");
        assertEquals(List.of("120.5"), column(claims, 0));
        assertEquals(List.of("3"), column(claims, 1));
        String plan = session.execute("EXPLAIN MATCH EDGE c:Claim WHERE c CONTAINS ($alice, $bob) AND c.amount > 10").message();
        assertTrue(plan.startsWith("IncidentIntersection"), plan);
        assertEquals(List.of("80.0"), column(session.execute("MATCH EDGE c:Claim WHERE c HAS $carol AS provider RETURN c.amount"), 0));
        assertEquals(List.of("120.5", "80.0"), column(session.execute("MATCH EDGE c:Claim WHERE c CONTAINS ($alice) RETURN c.amount"), 0));
        assertEquals(List.of("120.5", "15.0"), column(session.execute("MATCH EDGE c:Claim WHERE c CONTAINS ($bob) RETURN c.amount"), 0));
    }

    @Test
    void jsonPathPredicatesUseJsonIndexes() {
        session.executeAll("""
                CREATE JSON INDEX ON Person ('$.address.zip' ) AS INT;
                DOCUMENT $alice '{"address": {"zip": 94110}}';
                DOCUMENT $bob '{"address": {"zip": 10001}}';
                DOCUMENT $carol '{}';
                """);
        String plan = session.execute("EXPLAIN MATCH NODE p:Person WHERE json(p, '$.address.zip') = 94110").message();
        assertTrue(plan.startsWith("IndexRange(Person.$.address.zip"), plan);
        assertEquals(List.of("Alice"), column(session.execute("MATCH NODE p:Person WHERE json(p, '$.address.zip') = 94110 RETURN p.name"), 0));
        assertEquals(List.of("Bob"), column(session.execute("MATCH NODE p:Person WHERE json(p, '$.address.zip') < 50000 RETURN p.name"), 0));
        assertEquals(List.of("10001"), column(session.execute("MATCH NODE p:Person WHERE p.name = 'Bob' RETURN json(p, '$.address.zip')"), 0));
    }

    @Test
    void memberIdsFollowMembershipOrder() {
        session.execute("INSERT EDGE Pathway MEMBERS ($carol, $alice, $bob) AS $reverse");
        Map<String, Long> bound = session.variables();
        List<Long> expected = List.of(bound.get("carol"), bound.get("alice"), bound.get("bob"));
        database.read(reader -> {
            assertEquals(expected, reader.memberIds(bound.get("reverse")).boxed().toList());
            for (String edge : List.of("reverse", "path", "c1", "c2")) {
                assertEquals(reader.members(bound.get(edge)).map(Member::atom).toList(), reader.memberIds(bound.get(edge)).boxed().toList());
            }
            return null;
        });
    }

    @Test
    void broadPredicatesAreIntersectedAndDisjunctionsUnioned() {
        StringBuilder script = new StringBuilder("CREATE NODE TYPE Item (color STRING INDEXED, shape STRING INDEXED, size INT INDEXED);\nBEGIN;\n");
        for (int i = 0; i < 4000; i++) {
            script.append("INSERT NODE Item 'i").append(i).append("' {color: '").append(i % 2 == 0 ? "red" : "blue")
                    .append("', shape: '").append(i % 4 < 2 ? "round" : "square").append("', size: ").append(i % 100).append("};\n");
        }
        session.executeAll(script.append("COMMIT;").toString());
        String both = "MATCH NODE i:Item WHERE i.color = 'red' AND i.shape = 'round'";
        assertTrue(session.execute("EXPLAIN " + both).message().startsWith("Intersect["), session.execute("EXPLAIN " + both).message());
        assertEquals("1000", column(session.execute(both + " RETURN count(*)"), 0).getFirst());
        String either = "MATCH NODE i:Item WHERE i.size = 1 OR i.size = 2";
        String plan = session.execute("EXPLAIN " + either).message();
        assertTrue(plan.startsWith("Union["), plan);
        assertTrue(plan.lines().noneMatch(line -> line.contains("Verify")), plan);
        assertEquals("80", column(session.execute(either + " RETURN count(*)"), 0).getFirst());
        assertEquals("80", column(session.execute("MATCH NODE i:Item WHERE i.size + 0 = 1 OR i.size + 0 = 2 RETURN count(*)"), 0).getFirst());
    }

    @Test
    void indexedPredicatesDriveThePlan() {
        String plan = session.execute("EXPLAIN MATCH NODE p:Person WHERE p.age >= 30 AND p.age < 40").message();
        assertTrue(plan.startsWith("IndexRange(Person.age"), plan);
        QueryResult people = session.execute("MATCH NODE p:Person WHERE p.age BETWEEN 30 AND 45 RETURN p.name ORDER BY p.age DESC");
        assertEquals(List.of("Bob", "Alice"), column(people, 0));
        QueryResult aggregate = session.execute("MATCH NODE p:Person RETURN count(*), avg(p.age), max(p.name)");
        assertEquals(List.of("3"), column(aggregate, 0));
        assertEquals(List.of("Carol"), column(aggregate, 2));
        QueryResult expensive = session.execute("MATCH EDGE c:Claim WHERE c.amount > 50 RETURN c.amount ORDER BY c.amount");
        assertEquals(List.of("80.0", "120.5"), column(expensive, 0));
    }

    @Test
    void orderedEdgesAndMembership() {
        session.execute("ADD @Drug:'aspirin' TO $path AT 1");
        QueryResult members = session.execute("MEMBERS OF $path");
        assertEquals(4, members.rows().size());
        assertTrue(members.rows().get(1).get(1).render().contains("aspirin"));
        session.execute("REMOVE AT 0 FROM $path");
        assertEquals(3, session.execute("MEMBERS OF $path").rows().size());
        QueryResult incident = session.execute("INCIDENT TO $bob");
        assertEquals(3, incident.rows().size());
    }

    @Test
    void transactionsRollbackAndTimeTravel() {
        long before = database.engine().transactions().current().id();
        session.executeAll("BEGIN; INSERT NODE Person 'dave' {name: 'Dave'} AS $dave; ROLLBACK;");
        assertEquals(List.of("0"), column(session.execute("MATCH NODE p:Person WHERE p.name = 'Dave' RETURN count(*)"), 0));
        session.executeAll("BEGIN; SET $alice.age = 35; COMMIT;");
        assertEquals(List.of("35"), column(session.execute("MATCH NODE p:Person WHERE p.name = 'Alice' RETURN p.age"), 0));
        assertEquals(List.of("34"), column(session.execute("AT GENERATION " + before + " MATCH NODE p:Person WHERE p.name = 'Alice' RETURN p.age"), 0));
        assertThrows(HStoreException.InvalidSchema.class, () -> session.execute("AT GENERATION " + before + " SET $alice.age = 1"));
    }

    @Test
    void setAlgebraAndHigherOrderStatements() {
        assertEquals(List.of("1"), column(session.execute("OVERLAP $c1 $c2"), 0));
        assertEquals(List.of("false"), column(session.execute("SUBSET $c3 $c1"), 0));
        assertEquals(3, session.execute("OVERLAP JOIN Claim THRESHOLD 1").rows().size());
        QueryResult union = session.execute("UNION $c1 $c2 INTO $merged");
        assertEquals(List.of("4"), column(union, 1));
        assertEquals(4, session.execute("MEMBERS OF $merged").rows().size());
        assertEquals(List.of("2.5"), column(session.execute("REDUCE SUM(weight) OVER $c1"), 0));
        assertEquals(6, session.execute("OVERLAP JOIN Claim THRESHOLD 1").rows().size());
        QueryResult neighbors = session.execute("NEIGHBORS OF $alice");
        assertEquals(3, neighbors.rows().size());
        QueryResult closure = session.execute("CLOSURE $c2 DIM 1");
        assertEquals(3, closure.rows().size());
        QueryResult pattern = session.execute("""
                PATTERN (x: EDGE Claim, y: EDGE Claim, p: NODE Person)
                WHERE x CONTAINS p AND y CONTAINS p AND x != y AND p = $carol
                """);
        assertEquals(6, pattern.rows().size());
        QueryResult propagated = session.execute("PROPAGATE age OVER EDGES Claim");
        assertTrue(propagated.rows().size() >= 3);
        QueryResult gathered = session.execute("GATHER age FROM $c2");
        assertEquals(2, gathered.rows().size());
    }

    @Test
    void evidenceSignalsStateAndBranches() {
        session.executeAll("""
                EVIDENCE 'chart-review' SOURCE $alice ATTRIBUTES {site: 'north'} AS $ev;
                QUALIFY $carol IN $c2 AS HYPOTHESIZED CONFIDENCE 0.3 EVIDENCE ($ev) AS $q;
                STATE $alice = 0.7 SCHEMA risk;
                SIGNAL ON $bob CLOCK 'ecg' FROM 0 TO 1000 RESOLUTION 4;
                """);
        assertEquals(1, session.execute("MEMBERS OF $c2 POLICY OBSERVED").rows().size());
        assertEquals(2, session.execute("MEMBERS OF $c2 POLICY ANY").rows().size());
        assertTrue(session.execute("TRACE $q").rows().size() >= 3);
        assertEquals(List.of("0.7"), column(session.execute("MATCH NODE p:Person WHERE p.name = 'Alice' RETURN state(p)"), 0));
        assertEquals(1, session.execute("SIGNALS FOR $c1 DURING [100, 200)").rows().size());
        session.executeAll("""
                CREATE BRANCH scenario;
                USE BRANCH scenario;
                INSERT NODE Person 'zed' {name: 'Zed'};
                USE MAIN;
                """);
        assertEquals(List.of("3"), column(session.execute("MATCH NODE p:Person RETURN count(*)"), 0));
        QueryResult diff = session.execute("DIFF BRANCH main AND scenario");
        assertTrue(diff.rows().stream().anyMatch(row -> row.get(1).render().equals("created")));
    }

    @Test
    void semanticPredicatesAreVerifiedCandidates() {
        session.executeAll("""
                EMBED $aspirin TEXT 'aspirin reduces fever and inflammation';
                INSERT NODE Drug 'insulin' {name: 'Insulin'} AS $insulin;
                EMBED $insulin TEXT 'insulin regulates blood glucose';
                """);
        QueryResult similar = session.execute("MATCH NODE d:Drug WHERE d SIMILAR TO 'fever inflammation' TOP 1 RETURN d.name");
        assertEquals(List.of("Aspirin"), column(similar, 0));
    }

    @Test
    void summaryPrunedMemberFiltersAndContainment() {
        assertEquals(1, session.execute("MEMBERS OF $c1 ROLE approver").rows().size());
        assertEquals(1, session.execute("MEMBERS OF $c1 WEIGHT BETWEEN 0.4 AND 0.6").rows().size());
        assertEquals(0, session.execute("MEMBERS OF $c1 ROLE provider").rows().size());
        assertEquals(List.of("0.5"), column(session.execute("CONTAINMENT $c2 $c1"), 0));
        session.execute("STATE $carol = 3 SCHEMA level VALID [100, 200)");
        assertEquals(List.of("3"), column(session.execute("MATCH NODE p:Person WHERE p.name = 'Carol' RETURN state(p, 150)"), 0));
        assertEquals(List.of("null"), column(session.execute("MATCH NODE p:Person WHERE p.name = 'Carol' RETURN state(p, 250)"), 0));
    }

    @Test
    void branchesMergeThroughExplicitRootDiffs() {
        session.executeAll("""
                CREATE BRANCH hypothesis;
                USE BRANCH hypothesis;
                INSERT NODE Person 'erin' {name: 'Erin', age: 50} AS $erin;
                ADD $erin TO $c3 AS provider;
                REMOVE $bob FROM $c3;
                SET $carol.age = 30;
                USE MAIN;
                """);
        assertEquals(2, session.execute("MEMBERS OF $c3").rows().size());
        assertTrue(session.execute("MERGE BRANCH hypothesis").message().contains("1 atoms created"));
        QueryResult merged = session.execute("MEMBERS OF $c3");
        assertTrue(merged.rows().stream().anyMatch(row -> row.get(1).render().contains("erin")));
        assertTrue(merged.rows().stream().noneMatch(row -> row.get(1).render().contains("bob")));
        assertEquals(List.of("30"), column(session.execute("MATCH NODE p:Person WHERE p.name = 'Carol' RETURN p.age"), 0));
        assertTrue(session.execute("SHOW BRANCHES").rows().stream().noneMatch(row -> row.get(1).render().equals("hypothesis")));
    }

    @Test
    void syntaxErrorsAreReportedWithOffsets() {
        HStoreException.InvalidSchema error = assertThrows(HStoreException.InvalidSchema.class, () -> session.execute("MATCH EDGE c:Claim WHERE"));
        assertTrue(error.getMessage().contains("offset"), error.getMessage());
    }
}
