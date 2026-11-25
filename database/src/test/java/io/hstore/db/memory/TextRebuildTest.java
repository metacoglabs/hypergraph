// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.security.Principal;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.index.Postings;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.txn.Durability;
import io.hstore.engine.txn.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextRebuildTest {

    private static final String[] WORDS = {"alpha", "beta", "gamma", "delta", "claims", "hstore", "alice", "bob", "budget", "x.y", "pr-7",
            "postgres", "ledger", "zebra", "audit", "graph", "vector", "query", "shard", "replica"};

    record Content(TreeMap<Long, TreeMap<Long, TextIndex.Posting>> postings, TreeMap<Long, TextIndex.Stat> stats,
                   TreeMap<Long, TreeSet<Long>> revisions) {
    }

    @TempDir
    Path directory;

    HypergraphDatabase database;
    final Random random = new Random(23);
    final List<Long> documents = new ArrayList<>();

    @BeforeEach
    void open() {
        open(DatabaseOptions.defaults());
        database.write(writer -> {
            writer.defineNode("Note", List.of());
            return null;
        });
    }

    private void open(DatabaseOptions options) {
        database = HypergraphDatabase.open(directory, options.withEngine(EngineOptions.defaults().withPageSize(4096)));
    }

    @AfterEach
    void close() {
        database.close();
    }

    private String markdown() {
        StringBuilder text = new StringBuilder();
        int sections = 1 + random.nextInt(3);
        for (int section = 0; section < sections; section++) {
            text.append("# ").append(WORDS[random.nextInt(WORDS.length)]).append("\nshared");
            for (int word = random.nextInt(12); word > 0; word--) {
                text.append(' ').append(WORDS[random.nextInt(WORDS.length)]);
            }
            text.append(' ').append("w").append(random.nextInt(400)).append("\n");
        }
        return text.toString();
    }

    private long newDocument() {
        long document = database.write(writer -> writer.node("Note", "n" + documents.size() + "-" + random.nextInt(1_000_000)));
        documents.add(document);
        revise(document);
        return document;
    }

    private void revise(long document) {
        String text = markdown();
        database.write(writer -> writer.appendRevision(document, text, RevisionSpec.DEFAULT));
    }

    private void purge(long document) {
        documents.remove(document);
        database.write(writer -> writer.purge(document));
    }

    private void step() {
        int choice = documents.isEmpty() ? 0 : random.nextInt(10);
        if (choice < 4) {
            newDocument();
        } else if (choice < 9) {
            revise(documents.get(random.nextInt(documents.size())));
        } else {
            purge(documents.get(random.nextInt(documents.size())));
        }
    }

    private void corpus(int steps) {
        for (int i = 0; i < steps; i++) {
            step();
        }
    }

    private Content content(int branch) {
        return database.read(branch, Principal.SYSTEM, reader -> content(reader.engineView()));
    }

    private static Content content(View view) {
        TreeMap<Long, TreeMap<Long, TextIndex.Posting>> postings = new TreeMap<>();
        Tree<Postings<TextIndex.Posting>> index = view.scan(TextIndex.INDEX);
        index.keys().forEach(key -> TextIndex.POSTINGS.stream(index, key)
                .forEach(entry -> postings.computeIfAbsent(key, _ -> new TreeMap<>()).put(entry.key(), entry.value())));
        TreeMap<Long, TextIndex.Stat> stats = new TreeMap<>();
        view.scan(TextIndex.STATS).stream().forEach(entry -> stats.put(entry.key(), entry.value()));
        TreeMap<Long, TreeSet<Long>> revisions = new TreeMap<>();
        var sections = view.scan(TextIndex.REVISION_SECTIONS);
        sections.keys().forEach(key -> TextIndex.REVISION_INDEX.stream(sections, key)
                .forEach(entry -> revisions.computeIfAbsent(key, _ -> new TreeSet<>()).add(entry.key())));
        return new Content(postings, stats, revisions);
    }

    private int sections() {
        return database.read(reader -> (int) reader.engineView().scan(MemorySlots.SECTIONS).size());
    }

    private void corrupt(int branch) {
        long section = database.read(branch, Principal.SYSTEM, reader -> reader.engineView().scan(MemorySlots.SECTIONS).first().orElseThrow().key());
        database.engine().transactions().system(branch, workspace -> {
            TextIndex.Posting ghost = new TextIndex.Posting(3, TextIndex.fingerprint("ghost"));
            long term = TextIndex.termKey(0, "ghost");
            var index = TextIndex.POSTINGS.add(workspace.tree(TextIndex.INDEX), workspace.scope(), term, section, ghost);
            workspace.replace(TextIndex.INDEX, TextIndex.POSTINGS.add(index, workspace.scope(), TextIndex.currentKey(term), section, ghost));
            workspace.write(TextIndex.STATS, TextIndex.totalKey(0), current -> current.map(stat -> new TextIndex.Stat(stat.sections() + 7,
                    stat.tokens() + 70, stat.currentSections() + 7, stat.currentTokens() + 70)));
            return null;
        });
    }

    private boolean ghostly(Content content) {
        return content.postings().containsKey(TextIndex.termKey(0, "ghost"));
    }

    private boolean scratchIsEmpty() throws IOException {
        try (var left = Files.list(database.scratchDirectory())) {
            return left.findAny().isEmpty();
        }
    }

    @Test
    void aRebuildReproducesTheLiveIndexFromMemoryOrFromSortedRuns() throws IOException {
        corpus(120);
        Content live = content(Branch.MAIN);
        assertTrue(live.postings().values().stream().anyMatch(postings -> postings.size() > 16), "some term needs promoted postings");
        corrupt(Branch.MAIN);
        assertTrue(ghostly(content(Branch.MAIN)));
        TextRebuild.Result single = TextRebuild.run(database, Branch.MAIN);
        assertEquals(0, single.runs());
        assertEquals(sections(), single.sections());
        assertEquals(live, content(Branch.MAIN));
        corrupt(Branch.MAIN);
        TextRebuild.Result many = new TextRebuild(database, 50, TextRebuild.Steps.NONE).run(Branch.MAIN);
        assertTrue(many.runs() > 10, String.valueOf(many));
        assertTrue(many.peakEntries() <= 50, String.valueOf(many));
        assertEquals(single.sections(), many.sections());
        assertEquals(live, content(Branch.MAIN));
        assertTrue(scratchIsEmpty());
        TextRebuild.Result merged = new TextRebuild(database, 3, TextRebuild.Steps.NONE).run(Branch.MAIN);
        assertTrue(merged.runs() > 128, String.valueOf(merged));
        assertEquals(live, content(Branch.MAIN));
        assertTrue(scratchIsEmpty());
        database.close();
        open(DatabaseOptions.defaults());
        assertEquals(live, content(Branch.MAIN));
    }

    @Test
    void aRebuildIsNotBoundByTheTransactionPageQuota() {
        corpus(400);
        Content live = content(Branch.MAIN);
        database.close();
        open(DatabaseOptions.defaults().withTransactionPages(4));
        HStoreException.ResourceLimit quota = assertThrows(HStoreException.ResourceLimit.class, () -> database.write(writer -> {
            writer.transaction().apply("copy the index", workspace -> {
                Tree<Postings<TextIndex.Posting>> copy = Tree.empty(TextIndex.INDEX.schema(), workspace.source());
                for (var term : live.postings().entrySet()) {
                    for (var posting : term.getValue().entrySet()) {
                        copy = TextIndex.POSTINGS.add(copy, workspace.scope(), term.getKey(), posting.getKey(), posting.getValue());
                    }
                }
                workspace.replace(TextIndex.INDEX, copy);
            });
            return null;
        }));
        assertTrue(quota.getMessage().contains("over its quota"), quota.getMessage());
        TextRebuild.run(database, Branch.MAIN);
        assertEquals(live, content(Branch.MAIN));
    }

    @Test
    void commitsMadeWhileTheRebuildRunsAreFoldedIn() {
        corpus(60);
        long purgedEarly = documents.getFirst();
        long purgedLate = documents.get(1);
        long payload = database.read(reader -> Documents.head(reader.engineView(), 0, purgedEarly)
                .flatMap(head -> reader.engineView().get(MemorySlots.REVISIONS, head.latest())).orElseThrow().payload());
        AtomicReference<Long> lateDocument = new AtomicReference<>();
        AtomicBoolean erasedDuringRebuild = new AtomicBoolean();
        corrupt(Branch.MAIN);
        TextRebuild.Steps steps = new TextRebuild.Steps() {
            @Override
            public void built() {
                revise(documents.get(2));
                newDocument();
                purge(purgedEarly);
                erasedDuringRebuild.set(database.payloads().erased(payload));
                for (int i = 0; i < 30; i++) {
                    step();
                }
            }

            @Override
            public void caughtUp() {
                revise(documents.get(3));
                purge(purgedLate);
                lateDocument.set(newDocument());
            }
        };
        new TextRebuild(database, 40, steps).run(Branch.MAIN);
        assertFalse(erasedDuringRebuild.get(), "payload erasure waits for the rebuild");
        assertTrue(database.payloads().erased(payload), "payload erasure runs once the rebuild ends");
        Content folded = content(Branch.MAIN);
        assertFalse(ghostly(folded));
        TextRebuild.run(database, Branch.MAIN);
        assertEquals(content(Branch.MAIN), folded);
        assertTrue(folded.revisions().containsKey(database.read(reader -> Documents.head(reader.engineView(), 0, lateDocument.get()))
                .orElseThrow().latest()));
    }

    @Test
    void writersRacingRepeatedRebuildsLeaveTheIndexAFreshRebuildWouldBuild() throws InterruptedException {
        corpus(40);
        corrupt(Branch.MAIN);
        Random writes = new Random(99);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();
        List<Long> owned = new ArrayList<>();
        int[] created = {0};
        Thread writer = Thread.ofPlatform().start(() -> {
            try {
                for (int i = 0; i < 250; i++) {
                    int choice = owned.isEmpty() ? 0 : writes.nextInt(10);
                    if (choice < 4) {
                        long document = database.write(w -> w.node("Note", "race-" + created[0]++));
                        owned.add(document);
                        database.write(w -> w.appendRevision(document, "# Race\nshared race w" + writes.nextInt(50) + "\n", RevisionSpec.DEFAULT));
                    } else if (choice < 9) {
                        long document = owned.get(writes.nextInt(owned.size()));
                        database.write(w -> w.appendRevision(document, "# Race\nshared again w" + writes.nextInt(50) + "\n", RevisionSpec.DEFAULT));
                    } else {
                        long document = owned.remove(writes.nextInt(owned.size()));
                        database.write(w -> w.purge(document));
                    }
                }
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.set(true);
            }
        });
        AtomicReference<Throwable> rebuildFailure = new AtomicReference<>();
        Thread second = Thread.ofPlatform().start(() -> {
            try {
                for (int i = 0; i < 3 || !done.get(); i++) {
                    new TextRebuild(database, 97, TextRebuild.Steps.NONE).run(Branch.MAIN);
                }
            } catch (Throwable thrown) {
                rebuildFailure.set(thrown);
            }
        });
        int rebuilds = 0;
        while (!done.get() || rebuilds < 3) {
            new TextRebuild(database, 64, TextRebuild.Steps.NONE).run(Branch.MAIN);
            rebuilds++;
        }
        writer.join();
        second.join();
        assertNull(failure.get());
        assertNull(rebuildFailure.get());
        Content raced = content(Branch.MAIN);
        assertFalse(ghostly(raced));
        TextRebuild.run(database, Branch.MAIN);
        assertEquals(content(Branch.MAIN), raced);
    }

    @Test
    void aRebuildThatFailsLeavesTheIndexAndTheDatabaseUsable() {
        corpus(30);
        Content before = content(Branch.MAIN);
        TextRebuild.Steps failing = new TextRebuild.Steps() {
            @Override
            public void caughtUp() {
                throw new IllegalStateException("stop here");
            }
        };
        assertThrows(IllegalStateException.class, () -> new TextRebuild(database, 40, failing).run(Branch.MAIN));
        assertEquals(before, content(Branch.MAIN));
        long document = newDocument();
        purge(document);
        corpus(5);
        Content after = content(Branch.MAIN);
        TextRebuild.run(database, Branch.MAIN);
        assertEquals(after, content(Branch.MAIN));
    }

    @Test
    void aRebuildOnABranchLeavesMainAlone() {
        corpus(20);
        Content clean = content(Branch.MAIN);
        int side = database.engine().createBranch("side", Branch.MAIN).id();
        corrupt(Branch.MAIN);
        corrupt(side);
        Content main = content(Branch.MAIN);
        TextRebuild.run(database, side);
        assertEquals(main, content(Branch.MAIN));
        assertEquals(clean, content(side));
    }

    @Test
    void underAsynchronousDurabilityPurgedTextIsErasedAtACheckpointAfterTheRebuild() {
        database.close();
        directory = directory.resolve("async");
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults()
                .withEngine(EngineOptions.defaults().withPageSize(4096).withDurability(Durability.ASYNC)));
        database.write(writer -> writer.defineNode("Note", List.of()));
        corpus(30);
        long purged = documents.getFirst();
        long payload = database.read(reader -> Documents.head(reader.engineView(), 0, purged)
                .flatMap(head -> reader.engineView().get(MemorySlots.REVISIONS, head.latest())).orElseThrow().payload());
        AtomicBoolean erasedDuringRebuild = new AtomicBoolean();
        TextRebuild.Steps steps = new TextRebuild.Steps() {
            @Override
            public void built() {
                purge(purged);
                database.engine().checkpoint();
                erasedDuringRebuild.set(database.payloads().erased(payload));
                corpus(5);
            }
        };
        new TextRebuild(database, 40, steps).run(Branch.MAIN);
        assertFalse(erasedDuringRebuild.get());
        database.engine().checkpoint();
        assertTrue(database.payloads().erased(payload));
        Content folded = content(Branch.MAIN);
        TextRebuild.run(database, Branch.MAIN);
        assertEquals(content(Branch.MAIN), folded);
    }
}
