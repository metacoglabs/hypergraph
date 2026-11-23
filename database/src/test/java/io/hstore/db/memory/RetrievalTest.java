// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.MemberSpec;
import io.hstore.db.evidence.Evidence;
import io.hstore.db.evidence.Provenance;
import io.hstore.db.hora.Budget;
import io.hstore.db.schema.AtomKind;
import io.hstore.db.semantic.Reranker;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.topology.Validity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;

    long alice;
    long me;
    long claimsBay;
    long meetingNotes;
    long architecture;
    long meetingDecision;
    long storageBefore;
    long storageAfter;
    long evidence;
    long meeting;
    long budgetNote;

    private void open(Reranker reranker) {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096))
                .withReranker(reranker));
        database.write(writer -> {
            writer.defineNode("Person", List.of());
            writer.defineNode("Project", List.of());
            writer.defineNode("Note", List.of());
            writer.defineNode("Log", List.of());
            writer.defineEdge("Meeting", AtomKind.SET_EDGE, List.of(), List.of("participant", "project", "decision"));
            writer.defineEdge("Decision", AtomKind.SET_EDGE, List.of(), List.of("project", "record"));
            alice = writer.node("Person", "alice");
            me = writer.node("Person", "me");
            claimsBay = writer.node("Project", "claimsbay");
            meetingNotes = writer.node("Note", "meeting-12-may");
            architecture = writer.node("Note", "claimsbay-architecture");
            budgetNote = writer.node("Note", "budget");
            return null;
        });
        evidence = database.write(writer -> Provenance.record(writer, new Evidence(alice, 0, 100, "meeting transcript", 0, Map.of(), 0)));
        Revision notes = database.write(writer -> writer.appendRevision(meetingNotes, """
                # Meeting with Alice
                Weekly sync on ClaimsBay.
                ## Decision
                We decided to keep ClaimsBay claims storage on Postgres for now.
                ## Other
                Lunch options were discussed.
                """, RevisionSpec.DEFAULT.validFrom(100).sources(List.of(evidence)).embedded()));
        Revision before = database.write(writer -> writer.appendRevision(architecture, """
                # ClaimsBay architecture
                ## Storage
                ClaimsBay stores claims in Postgres.
                """, RevisionSpec.DEFAULT.validFrom(100).embedded()));
        Revision after = database.write(writer -> writer.appendRevision(architecture, """
                # ClaimsBay architecture
                ## Storage
                ClaimsBay moved its claims storage to HStore after the June review.
                """, RevisionSpec.DEFAULT.validFrom(200).embedded()));
        database.write(writer -> writer.appendRevision(budgetNote, "# Budget\nThe hardware budget for next year.\n", RevisionSpec.DEFAULT.embedded()));
        database.write(writer -> writer.appendRevision(writer.node("Log", "garden"), "# Garden\nTomatoes need ClaimsBay water.\n",
                RevisionSpec.DEFAULT.embedded()));
        database.read(reader -> {
            meetingDecision = reader.sections(notes.id()).get(1).id();
            storageBefore = reader.sections(before.id()).get(1).id();
            storageAfter = reader.sections(after.id()).get(1).id();
            return null;
        });
        meeting = database.write(writer -> {
            long edge = writer.edge("Meeting");
            writer.add(edge, alice, "participant");
            writer.add(edge, me, "participant");
            writer.add(edge, claimsBay, "project");
            writer.add(edge, meetingDecision, MemberSpec.roles("decision").withValidity(new Validity(100, 200)));
            return edge;
        });
        database.write(writer -> {
            long edge = writer.edge("Decision");
            writer.add(edge, claimsBay, "project");
            writer.add(edge, storageAfter, MemberSpec.roles("record").withValidity(new Validity(200, Long.MAX_VALUE)));
            return edge;
        });
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Retrieval.Result ask(Retrieval.Query query) {
        return database.read(reader -> reader.retrieve(query, Budget.DEFAULT.start()));
    }

    private static Retrieval.Query question(String text) {
        return Retrieval.Query.of(text).withAnchors(List.of()).resolvingIn(Set.of("Person", "Project")).limit(4);
    }

    @Test
    void whatDidAliceAndIDecideAndWhatChangedAfterward() {
        open(Reranker.NONE);
        Retrieval.Result result = ask(question("What did Alice and I decide about ClaimsBay, and what changed afterward?").withAnchors(List.of(me)));
        assertTrue(result.anchors().containsAll(List.of(alice, claimsBay, me)), result.anchors().toString());
        Map<Long, Retrieval.Hit> hits = new java.util.HashMap<>();
        result.hits().forEach(hit -> hits.put(hit.section(), hit));
        assertTrue(hits.containsKey(meetingDecision), result.hits().toString());
        assertTrue(hits.containsKey(storageAfter), result.hits().toString());
        assertFalse(hits.containsKey(storageBefore), "the superseded revision is resolved to the current one");
        Retrieval.Hit decision = hits.get(meetingDecision);
        assertFalse(decision.current(), "the May decision ended in June");
        assertEquals(3, decision.path().size(), decision.toString());
        assertTrue(Set.of(alice, me, claimsBay).contains(decision.path().get(0)));
        assertEquals(List.of(meeting, meetingDecision), decision.path().subList(1, 3));
        assertTrue(decision.evidence().contains(evidence), decision.toString());
        assertTrue(decision.text().contains("Postgres"));
        assertEquals(List.of("Meeting with Alice", "Decision"), decision.heading());
        Retrieval.Hit storage = hits.get(storageAfter);
        assertTrue(storage.current());
        assertTrue(storage.text().contains("HStore"));
        for (Retrieval.Hit hit : result.hits()) {
            assertFalse(hit.current() && hit.text().contains("Postgres"), "no superseded decision presented as current: " + hit);
            assertFalse(hit.text().isEmpty());
        }
    }

    @Test
    void askingAboutMayGivesTheTextInForceThen() {
        open(Reranker.NONE);
        Retrieval.Result result = ask(question("ClaimsBay claims storage").withValidAt(OptionalLong.of(150)));
        Map<Long, Retrieval.Hit> hits = new java.util.HashMap<>();
        result.hits().forEach(hit -> hits.put(hit.section(), hit));
        assertTrue(hits.containsKey(storageBefore), result.hits().toString());
        assertFalse(hits.containsKey(storageAfter));
        Retrieval.Hit storage = hits.get(storageBefore);
        assertTrue(storage.current(), "it was the text in force at 150");
        assertEquals(1, storage.later().size(), "and it changed afterward");
        assertTrue(hits.get(meetingDecision).current(), "the decision was valid at 150");
    }

    @Test
    void lexicalSemanticAndStructuralListsAllContribute() {
        open(Reranker.NONE);
        Retrieval.Result lexical = ask(Retrieval.Query.of("hardware budget").limit(2));
        assertEquals(budgetNote, lexical.hits().getFirst().document());
        assertTrue(lexical.hits().getFirst().lexical() > 0);
        assertTrue(lexical.hits().getFirst().semantic() > 0);
        Retrieval.Result structural = ask(Retrieval.Query.of("zzz unrelated words").withAnchors(List.of(claimsBay)).limit(5));
        assertTrue(structural.hits().stream().anyMatch(hit -> hit.section() == storageAfter && hit.structural() > 0), structural.hits().toString());
        Retrieval.Result typed = ask(Retrieval.Query.of("ClaimsBay").inTypes(Set.of("Project")).limit(5));
        assertEquals(List.of(), typed.hits());
        assertTrue(ask(Retrieval.Query.of("ClaimsBay").limit(1)).hits().size() <= 1);
    }

    @Test
    void coverageSpreadsTheAnswerAcrossDocuments() {
        open(Reranker.NONE);
        Retrieval.Result result = ask(Retrieval.Query.of("ClaimsBay storage budget").limit(2));
        assertEquals(2, result.hits().size());
        assertTrue(result.hits().stream().anyMatch(hit -> hit.document() == budgetNote), result.hits().toString());
    }

    @Test
    void aSmallBudgetGivesAPartialAnswerWithANote() {
        open(Reranker.NONE);
        Retrieval.Result result = database.read(reader -> reader.retrieve(question("What did Alice and I decide about ClaimsBay?"),
                new Budget(300, 8, java.time.Duration.ofSeconds(5)).start()));
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("budget")), result.notes().toString());
    }

    @Test
    void anUnrelatedEditKeepsTheDecisionsLinks() {
        open(Reranker.NONE);
        database.write(writer -> writer.appendRevision(meetingNotes, """
                # Meeting with Alice
                Weekly sync on ClaimsBay.
                ## Decision
                We decided to keep ClaimsBay claims storage on Postgres for now.
                ## Other
                Lunch options were discussed again.
                """, RevisionSpec.DEFAULT.validFrom(100).embedded()));
        Retrieval.Result result = ask(question("What did Alice and I decide about ClaimsBay?").withAnchors(List.of(me)));
        Retrieval.Hit decision = result.hits().stream().filter(hit -> hit.heading().equals(List.of("Meeting with Alice", "Decision"))).findFirst()
                .orElseThrow(() -> new AssertionError(result.hits().toString()));
        assertFalse(decision.current(), decision.toString());
        assertEquals(3, decision.path().size(), decision.toString());
    }

    @Test
    void inForceMeansValidNowAndHistoryDoesNotMakeOldTextCurrent() {
        open(Reranker.NONE);
        database.write(writer -> writer.appendRevision(architecture, """
                # ClaimsBay architecture
                ## Storage
                During the pilot ClaimsBay kept claims in SQLite.
                """, RevisionSpec.DEFAULT.validFrom(50).validTo(80).embedded()));
        Retrieval.Result plain = ask(Retrieval.Query.of("ClaimsBay claims storage").limit(4));
        for (Retrieval.Hit hit : plain.hits()) {
            assertFalse(hit.current() && hit.text().contains("SQLite"), hit.toString());
        }
        assertTrue(plain.hits().stream().anyMatch(hit -> hit.current() && hit.text().contains("HStore")), plain.hits().toString());
        Retrieval.Result history = ask(Retrieval.Query.of("Postgres claims storage").withHistory(true).limit(6));
        Retrieval.Hit old = history.hits().stream().filter(hit -> hit.section() == storageBefore).findFirst()
                .orElseThrow(() -> new AssertionError(history.hits().toString()));
        assertFalse(old.current());
        assertFalse(old.later().isEmpty());
    }

    @Test
    void timedQueriesSearchTheTextOfTheirTimeAndHierarchyRoutesKeepTheTypeFilter() {
        open(Reranker.NONE);
        Retrieval.Result then = ask(Retrieval.Query.of("Postgres").withValidAt(OptionalLong.of(150)).limit(3));
        Retrieval.Hit storage = then.hits().stream().filter(hit -> hit.section() == storageBefore).findFirst()
                .orElseThrow(() -> new AssertionError(then.hits().toString()));
        assertTrue(storage.lexical() > 0, storage.toString());
        String model = database.semantic().encoder().model();
        database.write(writer -> Hierarchies.create(writer, new Hierarchies.Spec("memory", model, "content", java.util.Optional.empty(), 4, 4, false)));
        Retrieval.Result routed = ask(Retrieval.Query.of("ClaimsBay water").inTypes(Set.of("Note")).inHierarchy(java.util.Optional.of("memory")).limit(5));
        assertFalse(routed.hits().stream().anyMatch(hit -> hit.text().contains("Tomatoes")), routed.hits().toString());
    }

    @Test
    void aRerankerReordersTheCandidates() {
        open(new Reranker() {
            @Override
            public String name() {
                return "lunch-first";
            }

            @Override
            public double[] score(String query, List<String> passages) {
                return passages.stream().mapToDouble(text -> text.contains("Lunch") ? 1 : 0).toArray();
            }
        });
        Retrieval.Result result = ask(Retrieval.Query.of("ClaimsBay meeting lunch decision").limit(3));
        assertTrue(result.hits().getFirst().text().contains("Lunch"), result.hits().toString());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("lunch-first")), result.notes().toString());
    }
}
