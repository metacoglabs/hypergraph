// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTest {

    private static String slice(String markdown, Markdown.Part part) {
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        return new String(Arrays.copyOfRange(bytes, part.start(), part.end()), StandardCharsets.UTF_8);
    }

    @Test
    void headingsSplitTheTextAndCarryTheirPath() {
        String markdown = """
                Intro line.

                # ClaimsBay
                Overview.
                ## Decisions
                Use HStore.
                ### Rationale
                Fewer joins.
                ## People
                Alice.
                # Other
                """;
        List<Markdown.Part> parts = Markdown.sections(markdown);
        assertEquals(List.of(List.of(), List.of("ClaimsBay"), List.of("ClaimsBay", "Decisions"), List.of("ClaimsBay", "Decisions", "Rationale"),
                List.of("ClaimsBay", "People"), List.of("Other")), parts.stream().map(Markdown.Part::path).toList());
        assertEquals(List.of(0, 1, 2, 3, 2, 1), parts.stream().map(Markdown.Part::level).toList());
        assertEquals(List.of("", "claimsbay", "claimsbay/decisions", "claimsbay/decisions/rationale", "claimsbay/people", "other"),
                parts.stream().map(Markdown.Part::anchor).toList());
        assertEquals("## Decisions\nUse HStore.\n", slice(markdown, parts.get(2)));
        assertEquals("Intro line.\n\n", slice(markdown, parts.get(0)));
        assertEquals(List.of(0, 1, 2, 3, 4, 5), parts.stream().map(Markdown.Part::ordinal).toList());
    }

    @Test
    void headingsInsideFencedCodeAreText() {
        String markdown = """
                # Setup
                ```bash
                # not a heading
                ```
                ~~~~
                ## still code
                ```
                ~~~~
                ## Next
                ````
                # unclosed fence runs to the end
                """;
        List<Markdown.Part> parts = Markdown.sections(markdown);
        assertEquals(List.of(List.of("Setup"), List.of("Setup", "Next")), parts.stream().map(Markdown.Part::path).toList());
    }

    @Test
    void setextHeadingsFrontMatterAndBreaksAreRecognised() {
        String markdown = """
                ---
                title: notes
                ---
                Plan
                ====
                text

                ---
                after a break
                Sub part
                --------
                    # indented code
                #nospace is text
                # Closed ##
                """;
        List<Markdown.Part> parts = Markdown.sections(markdown);
        assertEquals(List.of(List.of(), List.of("Plan"), List.of("Plan", "after a break Sub part"), List.of("Closed")),
                parts.stream().map(Markdown.Part::path).toList());
        assertTrue(slice(markdown, parts.get(0)).contains("title: notes"));
    }

    @Test
    void repeatedHeadingsGetNumberedAnchors() {
        String markdown = "# Notes\n## Q&A: Why?\na\n## Q&A: Why?\nb\n# Notes\n## Q&A: Why?\n# !!!\n";
        assertEquals(List.of("notes", "notes/qa-why", "notes/qa-why~2", "notes~2", "notes~2/qa-why", "section"),
                Markdown.sections(markdown).stream().map(Markdown.Part::anchor).toList());
    }

    @Test
    void offsetsAreUtf8BytesAndCrlfIsKept() {
        String markdown = "# Résumé\r\nnaïve – text\r\n## Café ☕\r\nmore\r\n";
        List<Markdown.Part> parts = Markdown.sections(markdown);
        assertEquals(List.of(List.of("Résumé"), List.of("Résumé", "Café ☕")), parts.stream().map(Markdown.Part::path).toList());
        assertEquals("## Café ☕\r\nmore\r\n", slice(markdown, parts.get(1)));
        assertEquals("résumé/café", parts.get(1).anchor());
    }

    @Test
    void blankPreambleAndEmptyDocumentsHaveNoPreambleSection() {
        assertEquals(List.of(), Markdown.sections(""));
        assertEquals(List.of(), Markdown.sections("\n  \n"));
        List<Markdown.Part> parts = Markdown.sections("\n\n# A\n");
        assertEquals(1, parts.size());
        assertEquals(2, parts.getFirst().start());
        assertEquals(List.of(List.of()), Markdown.sections("just text").stream().map(Markdown.Part::path).toList());
    }

    @Test
    void randomDocumentsAgreeWithASimpleReference() {
        Random random = new Random(7);
        String[] words = {"alpha", "beta", "Gamma", "δέλτα", "x", "#tag", "a # b"};
        for (int round = 0; round < 400; round++) {
            StringBuilder text = new StringBuilder();
            int lines = random.nextInt(30);
            for (int i = 0; i < lines; i++) {
                switch (random.nextInt(7)) {
                    case 0 -> text.append("#".repeat(1 + random.nextInt(7))).append(' ').append(words[random.nextInt(words.length)]);
                    case 1 -> text.append("```");
                    case 2 -> text.append("");
                    case 3 -> text.append("  ").append("#".repeat(1 + random.nextInt(3))).append(" ").append(words[random.nextInt(words.length)]);
                    default -> text.append(words[random.nextInt(words.length)]).append(' ').append(words[random.nextInt(words.length)]);
                }
                text.append(random.nextBoolean() ? "\n" : "\r\n");
            }
            String markdown = text.toString();
            List<Markdown.Part> parts = Markdown.sections(markdown);
            assertEquals(reference(markdown), parts.stream().map(part -> part.level() + ":" + String.join("/", part.path())).toList(), markdown);
            int length = markdown.getBytes(StandardCharsets.UTF_8).length;
            for (int i = 0; i < parts.size(); i++) {
                assertEquals(i + 1 < parts.size() ? parts.get(i + 1).start() : length, parts.get(i).end());
                assertTrue(parts.get(i).start() < parts.get(i).end());
            }
        }
    }

    private static List<String> reference(String markdown) {
        List<String> out = new ArrayList<>();
        List<String> stack = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        boolean fence = false;
        boolean preamble = false;
        boolean seenHeading = false;
        for (String raw : markdown.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            String trimmed = line.stripLeading();
            int indent = line.length() - trimmed.length();
            if (indent <= 3 && trimmed.startsWith("```")) {
                fence = !fence;
            } else if (!fence && indent <= 3 && trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                    level++;
                }
                if (level <= 6 && (level == trimmed.length() || trimmed.charAt(level) == ' ')) {
                    String title = trimmed.substring(level).strip();
                    while (!levels.isEmpty() && levels.getLast() >= level) {
                        levels.removeLast();
                        stack.removeLast();
                    }
                    levels.add(level);
                    stack.add(title);
                    out.add(level + ":" + String.join("/", stack));
                    seenHeading = true;
                    continue;
                }
            }
            if (!seenHeading && !line.isBlank()) {
                preamble = true;
            }
        }
        if (preamble) {
            out.addFirst("0:");
        }
        return out;
    }
}
