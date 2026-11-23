// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Reader;
import io.hstore.db.hora.Budget;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextSearchTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;

    @BeforeEach
    void open() {
        database = reopen();
        database.write(writer -> {
            writer.defineNode("Note", List.of());
            writer.defineNode("Log", List.of());
            return null;
        });
    }

    private HypergraphDatabase reopen() {
        return HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Revision revise(String type, String key, String markdown) {
        return database.write(writer -> {
            long node = writer.find(type, key).orElseGet(() -> writer.node(type, key));
            return writer.appendRevision(node, markdown, RevisionSpec.DEFAULT);
        });
    }

    private List<Long> ids(List<TextIndex.Hit> hits) {
        return hits.stream().map(TextIndex.Hit::section).toList();
    }

    @Test
    void tokensFoldCaseAndAccentsAndKeepCompoundTerms() {
        assertEquals(List.of("reader", "retrieve", "reader.retrieve", "in", "hstore", "v1", "2", "v1.2", "pr", "123", "pr-123", "naive", "ecole",
                        "snake", "case", "snake_case"),
                Tokenizer.tokens("Reader.retrieve() in HStore v1.2, PR-123 naïve ÉCOLE snake_case"));
        assertEquals(List.of(), Tokenizer.tokens("  ... --- "));
        assertEquals(64, Tokenizer.tokens("x".repeat(100)).getFirst().length());
    }

    @Test
    void exactTechnicalTermsRankFirst() {
        Revision exact = revise("Note", "a", "# API\nCall Reader.retrieve with a query.\n");
        revise("Note", "b", "# Reader\nThe reader can retrieve a value.\n");
        revise("Note", "c", "# Other\nNothing relevant here at all.\n");
        List<TextIndex.Hit> hits = database.read(reader -> reader.searchText("Reader.retrieve", TextIndex.Options.top(10)));
        assertEquals(2, hits.size());
        assertEquals(sections(exact).getFirst(), hits.getFirst().section());
        assertTrue(hits.getFirst().score() > hits.get(1).score());
    }

    @Test
    void onlyTheLatestRevisionIsSearchedUnlessHistoryIsAsked() {
        Revision old = revise("Note", "plan", "# Plan\nWe use Postgres for claims.\n");
        Revision now = revise("Note", "plan", "# Plan\nWe use HStore for claims.\n");
        assertEquals(List.of(), database.read(reader -> reader.searchText("postgres", TextIndex.Options.top(5))));
        assertEquals(sections(old), ids(database.read(reader -> reader.searchText("postgres", TextIndex.Options.top(5).withHistory()))));
        assertEquals(sections(now), ids(database.read(reader -> reader.searchText("hstore", TextIndex.Options.top(5)))));
        assertEquals(2, database.read(reader -> reader.searchText("claims", TextIndex.Options.top(5).withHistory())).size());
    }

    @Test
    void typesFilterTheResultsAndPurgeRemovesThem() {
        Revision note = revise("Note", "n", "# Budget\nThe migration budget is fixed.\n");
        Revision log = revise("Log", "l", "# Budget\nBudget overrun logged.\n");
        assertEquals(sections(log), ids(database.read(reader -> reader.searchText("budget", TextIndex.Options.top(5).inTypes(Set.of("Log"))))));
        long logNode = log.document();
        database.write(writer -> writer.purge(logNode));
        assertEquals(sections(note), ids(database.read(reader -> reader.searchText("budget", TextIndex.Options.top(5)))));
        database.close();
        database = reopen();
        assertEquals(sections(note), ids(database.read(reader -> reader.searchText("budget", TextIndex.Options.top(5)))));
    }

    @Test
    void latestSearchReadsOnlyCurrentPostingsAndARevisionCostsOneCommit() {
        database.write(writer -> writer.appendRevision(writer.node("Note", "warm"), "# W\nw\n", RevisionSpec.DEFAULT.embedded()));
        long before = database.engine().transactions().current().id();
        database.write(writer -> writer.appendRevision(writer.node("Note", "embedded"), "# A\na\n# B\nb\n# C\nc\n# D\nd\n",
                RevisionSpec.DEFAULT.embedded()));
        assertEquals(before + 3, database.engine().transactions().current().id(), "one charge for the Markdown, one for all vectors, one commit");
        revise("Note", "busy", "# Zebra\nzebra stripes\n");
        for (int i = 0; i < 40; i++) {
            revise("Note", "busy", "# Zebra\nzebra stripes " + i + "\n");
        }
        assertEquals(1, database.read(reader -> reader.searchText("zebra", TextIndex.Options.top(5), new Budget(5, 8, Duration.ofSeconds(5)).start())).size());
        assertThrows(HStoreException.class, () -> database.read(reader -> reader.searchText("zebra", TextIndex.Options.top(5).withHistory(),
                new Budget(5, 8, Duration.ofSeconds(5)).start())));
    }

    @Test
    void scriptsKeepTheirWords() {
        assertEquals(List.of("हिंदी", "किताब"), Tokenizer.tokens("हिंदी किताब"));
        assertEquals(List.of("日本", "本語", "語の", "のテ", "テキ", "キス", "スト"), Tokenizer.tokens("日本語のテキスト"));
        assertTrue(!Tokenizer.tokens("で").equals(Tokenizer.tokens("て")));
        assertEquals(List.of("hstore", "日本"), Tokenizer.tokens("HStore日本"));
        assertEquals(List.of("中"), Tokenizer.tokens("中"));
        assertEquals(List.of("cafe", "δελτα", "ёж", "йод"), Tokenizer.tokens("Café δέλτα ёж йод"));
    }

    @Test
    void scoresMatchANaiveBm25OnRandomCorpora() {
        Random random = new Random(5);
        String[] vocabulary = {"alpha", "beta", "gamma", "delta", "claims", "hstore", "alice", "bob", "decision", "budget", "x.y", "pr-7"};
        for (int doc = 0; doc < 30; doc++) {
            int revisions = 1 + random.nextInt(3);
            for (int r = 0; r < revisions; r++) {
                StringBuilder text = new StringBuilder();
                for (int section = 0; section < 1 + random.nextInt(4); section++) {
                    text.append("# H").append(random.nextInt(5)).append('\n');
                    for (int word = 0; word < random.nextInt(25); word++) {
                        text.append(vocabulary[random.nextInt(vocabulary.length)]).append(random.nextInt(6) == 0 ? "\n" : " ");
                    }
                    text.append('\n');
                }
                revise(random.nextBoolean() ? "Note" : "Log", "d" + doc, text.toString());
            }
        }
        database.read(reader -> {
            for (String query : List.of("alpha", "claims hstore", "alice decision budget", "x.y", "pr-7 alpha alpha", "missing", "h3 beta")) {
                for (boolean history : List.of(false, true)) {
                    TextIndex.Options options = history ? TextIndex.Options.top(8).withHistory() : TextIndex.Options.top(8);
                    List<TextIndex.Hit> got = reader.searchText(query, options);
                    List<Map.Entry<Long, Double>> want = reference(reader, query, history);
                    assertEquals(want.stream().map(Map.Entry::getKey).toList(), ids(got), query + " history " + history);
                    for (int i = 0; i < got.size(); i++) {
                        assertEquals(want.get(i).getValue(), got.get(i).score(), 1e-9, query);
                    }
                }
            }
            return null;
        });
    }

    private List<Long> sections(Revision revision) {
        return database.read(reader -> reader.sections(revision.id()).stream().map(Section::id).toList());
    }

    private static List<Map.Entry<Long, Double>> reference(Reader reader, String query, boolean history) {
        Map<Long, List<String>> corpus = new LinkedHashMap<>();
        for (String type : List.of("Note", "Log")) {
            reader.atoms(type).forEach(node -> {
                List<Revision> revisions = reader.revisions(node);
                for (Revision revision : history ? revisions : List.of(revisions.getLast())) {
                    reader.sections(revision.id()).forEach(section -> corpus.put(section.id(), Tokenizer.tokens(reader.sectionText(section))));
                }
            });
        }
        double n = corpus.size();
        double average = corpus.values().stream().mapToInt(List::size).average().orElse(0);
        Map<String, Integer> queryTerms = new LinkedHashMap<>();
        Tokenizer.tokens(query).forEach(term -> queryTerms.merge(term, 1, Integer::sum));
        Map<Long, Double> scores = new HashMap<>();
        for (Map.Entry<String, Integer> term : queryTerms.entrySet()) {
            long df = corpus.values().stream().filter(tokens -> tokens.contains(term.getKey())).count();
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            corpus.forEach((section, tokens) -> {
                long tf = tokens.stream().filter(term.getKey()::equals).count();
                if (tf > 0) {
                    double norm = tf * (1.2 + 1) / (tf + 1.2 * (1 - 0.75 + 0.75 * tokens.size() / average));
                    scores.merge(section, idf * norm * term.getValue(), Double::sum);
                }
            });
        }
        List<Map.Entry<Long, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()));
        return ranked.subList(0, Math.min(8, ranked.size()));
    }
}
