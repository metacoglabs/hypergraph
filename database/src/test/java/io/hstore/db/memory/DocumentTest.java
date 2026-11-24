// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.evidence.Evidence;
import io.hstore.db.evidence.Provenance;
import io.hstore.db.schema.AtomKind;
import io.hstore.db.schema.TypeDef.PropertyDef;
import io.hstore.db.security.Security;
import io.hstore.db.value.TypeTag;
import io.hstore.db.value.Value;
import io.hstore.engine.EngineOptions;
import io.hstore.db.security.Principal;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.txn.Durability;
import io.hstore.engine.txn.IncidentEdge;
import io.hstore.engine.txn.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;

    @BeforeEach
    void open() {
        database = reopen();
        database.write(writer -> {
            writer.defineNode("Note", List.of(new PropertyDef("body", TypeTag.STRING, false, false)));
            writer.defineNode("Person", List.of());
            writer.defineEdge("Mentions", AtomKind.SET_EDGE, List.of(), List.of("subject", "source"));
            return null;
        });
    }

    private HypergraphDatabase reopen() {
        return HypergraphDatabase.open(directory, DatabaseOptions.defaults()
                .withEngine(EngineOptions.defaults().withPageSize(4096)));
    }

    @AfterEach
    void close() {
        database.close();
    }

    private List<Long> sectionIds(Revision revision) {
        return database.read(reader -> reader.sections(revision.id()).stream().map(Section::id).toList());
    }

    private long note(String key) {
        return database.write(writer -> writer.node("Note", key));
    }

    @Test
    void revisionsAreKeptNumberedAndLinked() {
        long doc = note("plan");
        Revision first = database.write(writer -> writer.appendRevision(doc, "# Plan\nUse Postgres.\n", RevisionSpec.DEFAULT));
        Revision second = database.write(writer -> writer.appendRevision(doc, "# Plan\nUse HStore.\n## Why\nFewer joins.\n",
                RevisionSpec.DEFAULT.note("switch")));
        database.read(reader -> {
            assertEquals(List.of(1, 2), reader.revisions(doc).stream().map(Revision::number).toList());
            assertEquals(first.id(), reader.revision(second.id()).orElseThrow().parent());
            assertEquals(second.id(), reader.latestRevision(doc).orElseThrow().id());
            assertEquals("# Plan\nUse Postgres.\n", reader.markdown(reader.revision(first.id()).orElseThrow()));
            assertEquals("switch", reader.revision(second.id()).orElseThrow().note());
            assertTrue(second.recordedAt() > first.recordedAt());
            assertEquals(second.recordedAt(), second.validFrom());
            assertEquals(Long.MAX_VALUE, second.validTo());
            assertTrue(reader.visible(first.id()));
            assertEquals("$Revision", reader.require(first.id()).type().name());
            return null;
        });
    }

    @Test
    void sectionsAreAtomsThatHyperedgesCanReference() {
        long doc = note("plan");
        Revision revision = database.write(writer -> writer.appendRevision(doc, "Preface\n# Plan\nUse HStore.\n## Why\nFewer joins.\n",
                RevisionSpec.DEFAULT));
        long alice = database.write(writer -> writer.node("Person", "alice"));
        database.read(reader -> {
            List<Section> sections = reader.sections(revision.id());
            assertEquals(List.of("", "plan", "plan/why"), sections.stream().map(Section::anchor).toList());
            assertEquals(3, revision.sections());
            assertEquals(List.of("Plan", "Why"), reader.headingPath(sections.get(2)));
            assertEquals(List.of(), reader.headingPath(sections.get(0)));
            assertEquals(Optional.of(sections.get(2)), reader.section(sections.get(2).id()));
            assertEquals("## Why\nFewer joins.\n", reader.sectionText(sections.get(2)));
            assertEquals("$Section", reader.require(sections.get(2).id()).type().name());
            return null;
        });
        long why = sectionIds(revision).get(2);
        long mention = database.write(writer -> {
            long edge = writer.edge("Mentions");
            writer.add(edge, alice, "subject");
            writer.add(edge, why, "source");
            return edge;
        });
        database.read(reader -> {
            assertEquals(2, reader.members(mention).count());
            assertEquals(List.of(mention), reader.incidentEdges(why).map(IncidentEdge::edge).toList());
            return null;
        });
    }

    @Test
    void revisionAtFollowsValidAndRecordedTime() {
        long doc = note("timeline");
        Revision early = database.write(writer -> writer.appendRevision(doc, "a", RevisionSpec.DEFAULT.validFrom(100)));
        Revision later = database.write(writer -> writer.appendRevision(doc, "b", RevisionSpec.DEFAULT.validFrom(200)));
        Revision correction = database.write(writer -> writer.appendRevision(doc, "c", RevisionSpec.DEFAULT.validFrom(150)));
        long now = Long.MAX_VALUE;
        database.read(reader -> {
            assertEquals(Optional.of(correction.id()), reader.documentAt(doc, 160, now).map(Revision::id));
            assertEquals(Optional.of(early.id()), reader.documentAt(doc, 160, later.recordedAt()).map(Revision::id));
            assertEquals(Optional.of(later.id()), reader.documentAt(doc, 250, now).map(Revision::id));
            assertEquals(Optional.empty(), reader.documentAt(doc, 50, now));
            assertEquals(Optional.empty(), reader.documentAt(doc, 160, early.recordedAt() - 1));
            return null;
        });
    }

    @Test
    void revisionAtAgreesWithAScanOnRandomHistories() {
        Random random = new Random(11);
        long doc = note("random");
        List<Revision> written = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            long from = random.nextInt(50);
            long to = random.nextInt(5) == 0 ? from + 1 + random.nextInt(20) : Long.MAX_VALUE;
            written.add(database.write(writer -> writer.appendRevision(doc, "r", RevisionSpec.DEFAULT.validFrom(from).validTo(to))));
        }
        database.read(reader -> {
            for (int probe = 0; probe < 300; probe++) {
                long valid = random.nextInt(80) - 5;
                long recorded = written.get(random.nextInt(written.size())).recordedAt() + random.nextInt(3) - 1;
                Optional<Long> expected = written.stream()
                        .filter(revision -> revision.recordedAt() <= recorded && revision.validAt(valid))
                        .max(Comparator.comparingLong(Revision::validFrom).thenComparingInt(Revision::number))
                        .map(Revision::id);
                assertEquals(expected, reader.documentAt(doc, valid, recorded).map(Revision::id), valid + " " + recorded);
            }
            return null;
        });
    }

    @Test
    void revisionsOutliveTheHistoryWindowAndRestarts() {
        long doc = note("long");
        for (int i = 1; i <= 90; i++) {
            int number = i;
            database.write(writer -> writer.appendRevision(doc, "# V\nversion " + number + "\n", RevisionSpec.DEFAULT));
        }
        database.close();
        database = reopen();
        database.read(reader -> {
            List<Revision> revisions = reader.revisions(doc);
            assertEquals(90, revisions.size());
            assertEquals("# V\nversion 1\n", reader.markdown(revisions.getFirst()));
            assertEquals("version 37\n", reader.sectionText(reader.sections(revisions.get(36).id()).getFirst()).substring(4));
            return null;
        });
    }

    @Test
    void revisionsAndSectionsCannotBeChangedOrCreatedDirectly() {
        long doc = note("fixed");
        Revision revision = database.write(writer -> writer.appendRevision(doc, "# A\ntext\n", RevisionSpec.DEFAULT));
        long section = sectionIds(revision).getFirst();
        assertThrows(HStoreException.class, () -> database.write(writer -> {
            writer.set(section, "x", Value.of("y"));
            return null;
        }));
        assertThrows(HStoreException.class, () -> database.write(writer -> {
            writer.delete(revision.id());
            return null;
        }));
        assertThrows(HStoreException.class, () -> database.write(writer -> writer.node("$Section", "1@1#0")));
        assertThrows(HStoreException.class, () -> database.write(writer -> writer.appendRevision(revision.id(), "x", RevisionSpec.DEFAULT)));
        HStoreException refused = assertThrows(HStoreException.class, () -> database.write(writer -> {
            writer.delete(doc);
            return null;
        }));
        assertTrue(refused.getMessage().contains("PURGE"), refused.getMessage());
        assertThrows(HStoreException.class, () -> database.write(writer ->
                writer.appendRevision(doc, "x", RevisionSpec.DEFAULT.validFrom(10).validTo(10))));
    }

    @Test
    void sourcesNameEvidenceOrAtoms() {
        long doc = note("sourced");
        long alice = database.write(writer -> writer.node("Person", "alice"));
        long evidence = database.write(writer -> Provenance.record(writer, new Evidence(alice, 0, 1, "meeting", 0, Map.of(), 0)));
        Revision revision = database.write(writer -> writer.appendRevision(doc, "x", RevisionSpec.DEFAULT.sources(List.of(evidence, alice))));
        assertEquals(List.of(evidence, alice), revision.sources());
        assertThrows(HStoreException.class, () -> database.write(writer ->
                writer.appendRevision(doc, "x", RevisionSpec.DEFAULT.sources(List.of(987_654_321L)))));
    }

    @Test
    void purgeRemovesTheDocumentAndErasesItsContent() throws IOException {
        long doc = note("secret");
        String marker = "ZQXJ-secret-" + "payload-marker";
        Revision first = database.write(writer -> writer.appendRevision(doc, "# Secret\n" + marker + " one\n", RevisionSpec.DEFAULT));
        Revision second = database.write(writer -> writer.appendRevision(doc, "# Secret\n" + marker + " two\n## More\nx\n", RevisionSpec.DEFAULT));
        List<Long> secondSections = sectionIds(second);
        database.write(writer -> {
            writer.set(doc, "body", Value.of(marker + "x".repeat(2000)));
            return null;
        });
        long alice = database.write(writer -> writer.node("Person", "alice"));
        long bob = database.write(writer -> writer.node("Person", "bob"));
        long onlySection = database.write(writer -> {
            long edge = writer.edge("Mentions");
            writer.add(edge, secondSections.get(1), "source");
            return edge;
        });
        long shared = database.write(writer -> {
            long edge = writer.edge("Mentions");
            writer.add(edge, alice, "subject");
            writer.add(edge, bob, "subject");
            writer.add(edge, secondSections.getFirst(), "source");
            return edge;
        });
        long before = database.engine().transactions().current().id();
        Documents.Purged purged = database.write(writer -> writer.purge(doc));
        assertEquals(new Documents.Purged(2, 3), purged);
        database.read(reader -> {
            assertFalse(reader.visible(doc));
            assertFalse(reader.visible(first.id()));
            assertFalse(reader.visible(secondSections.get(1)));
            assertEquals(List.of(), reader.revisions(doc));
            assertFalse(reader.visible(onlySection));
            assertEquals(2, reader.members(shared).count());
            return null;
        });
        assertFalse(payloadsContain(marker));
        HStoreException erased = assertThrows(HStoreException.class, () -> database.readAt(before, 0,
                reader -> reader.markdown(reader.revision(first.id()).orElseThrow())));
        assertTrue(erased.getMessage().contains("purged"), erased.getMessage());
        database.read(reader -> {
            assertEquals(0, reader.engineView().scan(MemorySlots.ERASURES).size());
            return null;
        });
    }

    @Test
    void erasuresLeftByACrashAreFinishedOnOpen() throws IOException {
        long doc = note("crash");
        String marker = "QWZX-left-behind-marker";
        Revision revision = database.write(writer -> writer.appendRevision(doc, marker, RevisionSpec.DEFAULT));
        try (Transaction txn = database.engine().begin(database.options().transaction())) {
            txn.put(MemorySlots.ERASURES, revision.payload(), new Erasure(0, revision.length()));
            txn.commit();
        }
        assertTrue(payloadsContain(marker));
        database.close();
        database = reopen();
        assertFalse(payloadsContain(marker));
        database.read(reader -> {
            assertEquals(0, reader.engineView().scan(MemorySlots.ERASURES).size());
            return null;
        });
    }

    @Test
    void failedRevisionsLeaveNoMarkdownBehind() throws IOException {
        long doc = note("failing");
        long usage = database.read(reader -> Security.usage(reader.engineView(), 0).payloadBytes());
        assertThrows(HStoreException.class, () -> database.write(writer ->
                writer.appendRevision(doc, "# T\nLEAK-ONE-marker\n", RevisionSpec.DEFAULT.validTo(5))));
        assertThrows(IllegalStateException.class, () -> database.write(writer -> {
            writer.appendRevision(doc, "# T\nLEAK-TWO-marker\n", RevisionSpec.DEFAULT);
            throw new IllegalStateException("caller gave up");
        }));
        assertThrows(HStoreException.class, () -> database.write(writer -> writer.appendRevision(doc, "bad \uD800 text", RevisionSpec.DEFAULT)));
        assertFalse(payloadsContain("LEAK-ONE-marker"));
        assertFalse(payloadsContain("LEAK-TWO-marker"));
        assertEquals(usage + "# T\nLEAK-TWO-marker\n".length(), (long) database.read(reader -> Security.usage(reader.engineView(), 0).payloadBytes()),
                "rolled-back Markdown still counts, as other rolled-back payloads do");
        assertEquals(List.of(), database.read(reader -> reader.revisions(doc)));
    }

    @Test
    void aFirstRevisionRacingADeleteFails() {
        long doc = note("racing");
        Transaction txn = database.engine().begin(database.options().transaction());
        try {
            database.writer(txn).appendRevision(doc, "# A\nrace\n", RevisionSpec.DEFAULT);
            database.write(writer -> {
                writer.delete(doc);
                return null;
            });
            assertThrows(HStoreException.class, () -> database.commit(txn));
        } finally {
            txn.close();
        }
        assertEquals(0L, (long) database.read(reader -> reader.engineView().scan(MemorySlots.REVISIONS).size()));
    }

    @Test
    void purgeErasesOlderVersionsStillInHistoryAndEdgesOnRevisions() {
        long doc = note("versions");
        long first = database.write(writer -> {
            writer.set(doc, "body", Value.of("OLDSECRET-" + "y".repeat(2000)));
            return writer.transaction().generation();
        });
        long afterFirst = database.engine().transactions().current().id();
        database.write(writer -> {
            writer.set(doc, "body", Value.of("NEWSECRET-" + "z".repeat(2000)));
            return null;
        });
        Revision revision = database.write(writer -> writer.appendRevision(doc, "x", RevisionSpec.DEFAULT));
        long onRevision = database.write(writer -> {
            long edge = writer.edge("Mentions");
            writer.add(edge, revision.id(), "source");
            return edge;
        });
        database.write(writer -> writer.purge(doc));
        HStoreException erased = assertThrows(HStoreException.class, () -> database.readAt(afterFirst, 0,
                reader -> reader.text(reader.property(doc, "body").orElseThrow())));
        assertTrue(erased.getMessage().contains("purged"), erased.getMessage() + " " + first);
        assertFalse((boolean) database.read(reader -> reader.visible(onRevision)));
    }

    @Test
    void recordsThatCannotFitASmallPageAreRefusedBeforeAnythingIsWritten() throws IOException {
        database.close();
        directory = directory.resolve("small");
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(1024)));
        database.write(writer -> {
            writer.defineNode("Note", List.of());
            writer.defineNode("Person", List.of());
            writer.defineEdge("Mentions", AtomKind.SET_EDGE, List.of(), List.of("subject", "source"));
            return null;
        });
        long doc = database.write(writer -> writer.node("Note", "small"));
        String deep = "# " + "標題".repeat(40) + "\n## " + "小節".repeat(40) + "\n### " + "段落".repeat(40) + "\n#### " + "節".repeat(60) + "\nx\n";
        Revision revision = database.write(writer -> writer.appendRevision(doc, deep, RevisionSpec.DEFAULT));
        assertEquals(4, revision.sections());
        List<Long> sources = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            int index = i;
            sources.add(database.write(writer -> writer.node("Person", "p" + index)));
        }
        HStoreException refused = assertThrows(HStoreException.class, () -> database.write(writer ->
                writer.appendRevision(doc, "TOO-BIG-marker", RevisionSpec.DEFAULT.note("注".repeat(200)).sources(sources))));
        assertTrue(refused.getMessage().contains("page"), refused.getMessage());
        assertFalse(payloadsContain("TOO-BIG-marker"));
    }

    @Test
    void asynchronousDurabilityErasesOnlyAfterACheckpoint() throws IOException {
        database.close();
        directory = directory.resolve("async");
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults()
                .withEngine(EngineOptions.defaults().withPageSize(4096).withDurability(Durability.ASYNC)));
        database.write(writer -> writer.defineNode("Note", List.of()));
        long doc = database.write(writer -> writer.node("Note", "async"));
        database.write(writer -> writer.appendRevision(doc, "ASYNC-marker", RevisionSpec.DEFAULT));
        database.write(writer -> writer.purge(doc));
        assertTrue(payloadsContain("ASYNC-marker"));
        database.engine().checkpoint();
        assertFalse(payloadsContain("ASYNC-marker"));
    }

    @Test
    void erasedMarkdownIsNotIndexedFromTheTermCache() {
        database.close();
        directory = directory.resolve("cached");
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults()
                .withEngine(EngineOptions.defaults().withPageSize(4096).withDurability(Durability.ASYNC)));
        database.write(writer -> writer.defineNode("Note", List.of()));
        long doc = database.write(writer -> writer.node("Note", "secret"));
        database.write(writer -> writer.appendRevision(doc, "# Secret\nzebra crossing\n", RevisionSpec.DEFAULT));
        int old = database.engine().createBranch("old", Branch.MAIN).id();
        database.write(writer -> writer.purge(doc));
        database.rebuildTextIndex(old, Principal.SYSTEM);
        database.engine().checkpoint();
        database.rebuildTextIndex(old, Principal.SYSTEM);
        assertEquals(List.of(), database.read(old, reader -> reader.searchText("zebra", TextIndex.Options.top(5).withHistory())));
    }

    private boolean payloadsContain(String marker) throws IOException {
        byte[] needle = marker.getBytes(StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.list(directory.resolve("payload"))) {
            for (Path file : files.toList()) {
                byte[] bytes = Files.readAllBytes(file);
                outer:
                for (int i = 0; i + needle.length <= bytes.length; i++) {
                    for (int j = 0; j < needle.length; j++) {
                        if (bytes[i + j] != needle[j]) {
                            continue outer;
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }
}
