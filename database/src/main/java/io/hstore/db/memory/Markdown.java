// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Markdown {

    public static final int SLUG_LIMIT = 40;
    public static final int ANCHOR_BYTES = 160;

    public record Part(int ordinal, int parent, int level, List<String> path, String anchor, int start, int end) {
        public Part {
            path = List.copyOf(path);
        }
    }

    private record Line(String text, int start, int end) {
    }

    private record Heading(int start, int level, String title) {
    }

    private Markdown() {
    }

    public static List<Part> sections(String markdown) {
        List<Line> lines = lines(markdown);
        int length = lines.isEmpty() ? 0 : lines.getLast().end();
        List<Heading> headings = headings(lines);
        List<Part> parts = new ArrayList<>();
        int first = headings.isEmpty() ? length : headings.getFirst().start();
        boolean preamble = lines.stream().filter(line -> line.end() <= first).anyMatch(line -> !line.text().isBlank());
        if (preamble) {
            parts.add(new Part(0, -1, 0, List.of(), "", 0, first));
        }
        List<Integer> levels = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<String> anchors = new ArrayList<>();
        List<Integer> ordinals = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < headings.size(); i++) {
            Heading heading = headings.get(i);
            while (!levels.isEmpty() && levels.getLast() >= heading.level()) {
                levels.removeLast();
                titles.removeLast();
                anchors.removeLast();
                ordinals.removeLast();
            }
            int parent = ordinals.isEmpty() ? -1 : ordinals.getLast();
            String base = capped((anchors.isEmpty() ? "" : anchors.getLast() + "/") + slug(heading.title()));
            int occurrence = seen.merge(base, 1, Integer::sum);
            String anchor = occurrence == 1 ? base : base + "~" + occurrence;
            levels.add(heading.level());
            titles.add(heading.title());
            anchors.add(anchor);
            ordinals.add(parts.size());
            int end = i + 1 < headings.size() ? headings.get(i + 1).start() : length;
            parts.add(new Part(parts.size(), parent, heading.level(), titles, anchor, heading.start(), end));
        }
        return List.copyOf(parts);
    }

    private static String capped(String anchor) {
        byte[] bytes = anchor.getBytes(StandardCharsets.UTF_8);
        int limit = ANCHOR_BYTES - 8;
        if (bytes.length <= limit) {
            return anchor;
        }
        int cut = limit;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return new String(bytes, 0, cut, StandardCharsets.UTF_8).replaceAll("[-/]+$", "");
    }

    public static String slug(String title) {
        StringBuilder slug = new StringBuilder();
        boolean gap = false;
        for (int point : title.toLowerCase(Locale.ROOT).codePoints().toArray()) {
            if (Character.isLetterOrDigit(point) || point == '_' || point == '-') {
                if (gap && !slug.isEmpty()) {
                    slug.append('-');
                }
                gap = false;
                slug.appendCodePoint(point);
            } else if (Character.isWhitespace(point)) {
                gap = true;
            }
        }
        String trimmed = slug.toString().replaceAll("-{2,}", "-").replaceAll("^-+|-+$", "");
        if (trimmed.codePointCount(0, trimmed.length()) > SLUG_LIMIT) {
            trimmed = trimmed.substring(0, trimmed.offsetByCodePoints(0, SLUG_LIMIT)).replaceAll("-+$", "");
        }
        return trimmed.isEmpty() ? "section" : trimmed;
    }

    private static List<Line> lines(String markdown) {
        List<Line> lines = new ArrayList<>();
        int bytes = 0;
        int from = 0;
        while (from < markdown.length()) {
            int newline = markdown.indexOf('\n', from);
            int to = newline < 0 ? markdown.length() : newline + 1;
            int size = utf8Length(markdown, from, to);
            String text = markdown.substring(from, newline < 0 ? to : newline);
            if (text.endsWith("\r")) {
                text = text.substring(0, text.length() - 1);
            }
            lines.add(new Line(text, bytes, bytes + size));
            bytes += size;
            from = to;
        }
        return lines;
    }

    private static int utf8Length(String text, int from, int to) {
        int size = 0;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                size += 1;
            } else if (c < 0x800) {
                size += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < to && Character.isLowSurrogate(text.charAt(i + 1))) {
                size += 4;
                i++;
            } else {
                size += 3;
            }
        }
        return size;
    }

    private static List<Heading> headings(List<Line> lines) {
        List<Heading> headings = new ArrayList<>();
        int index = frontMatter(lines);
        char fence = 0;
        int fenceLength = 0;
        boolean comment = false;
        boolean container = false;
        List<Line> paragraph = new ArrayList<>();
        for (; index < lines.size(); index++) {
            Line line = lines.get(index);
            String text = index == 0 && line.text().startsWith("\uFEFF") ? line.text().substring(1) : line.text();
            int[] indentation = indentation(text);
            int indent = indentation[0];
            String body = text.substring(indentation[1]);
            if (fence != 0) {
                if (indent <= 3 && run(body, fence) >= fenceLength && body.substring(run(body, fence)).isBlank()) {
                    fence = 0;
                }
                continue;
            }
            if (body.isBlank()) {
                paragraph.clear();
                container = false;
                continue;
            }
            if (comment) {
                comment = !body.contains("-->");
                continue;
            }
            String inner = indent <= 3 ? withoutContainers(body) : body;
            String opener = inner.stripLeading();
            if (indent <= 3 && (run(opener, '`') >= 3 || run(opener, '~') >= 3)) {
                fence = opener.charAt(0);
                fenceLength = run(opener, fence);
                paragraph.clear();
                continue;
            }
            if (indent <= 3 && body.startsWith("<!--")) {
                comment = !body.substring(4).contains("-->");
                paragraph.clear();
                continue;
            }
            if (indent >= 4 && paragraph.isEmpty()) {
                continue;
            }
            int level = indent <= 3 ? atx(body) : 0;
            if (level > 0) {
                headings.add(new Heading(line.start(), level, atxTitle(body, level)));
                paragraph.clear();
                container = false;
                continue;
            }
            int underline = indent <= 3 ? setext(body) : 0;
            if (underline > 0 && !paragraph.isEmpty()) {
                String title = String.join(" ", paragraph.stream().map(found -> found.text().strip()).toList());
                headings.add(new Heading(paragraph.getFirst().start(), underline, title));
                paragraph.clear();
                continue;
            }
            if (underline == 2 || thematicBreak(body)) {
                paragraph.clear();
                container = false;
                continue;
            }
            if (!inner.equals(body)) {
                paragraph.clear();
                container = true;
                continue;
            }
            if (container) {
                continue;
            }
            paragraph.add(line);
        }
        return headings;
    }

    private static String withoutContainers(String body) {
        String rest = body;
        while (true) {
            if (rest.startsWith(">")) {
                rest = rest.substring(1);
                if (rest.startsWith(" ")) {
                    rest = rest.substring(1);
                }
                continue;
            }
            int marker = listMarker(rest);
            if (marker == 0) {
                return rest;
            }
            rest = rest.substring(marker);
        }
    }

    private static int listMarker(String text) {
        int at = 0;
        if (!text.isEmpty() && (text.charAt(0) == '-' || text.charAt(0) == '*' || text.charAt(0) == '+')) {
            at = 1;
        } else {
            while (at < text.length() && at < 9 && Character.isDigit(text.charAt(at))) {
                at++;
            }
            if (at == 0 || at >= text.length() || (text.charAt(at) != '.' && text.charAt(at) != ')')) {
                return 0;
            }
            at++;
        }
        if (at == text.length()) {
            return at;
        }
        return text.charAt(at) == ' ' || text.charAt(at) == '\t' ? at + 1 : 0;
    }

    private static int frontMatter(List<Line> lines) {
        if (lines.isEmpty() || !lines.getFirst().text().replace("\uFEFF", "").equals("---")) {
            return 0;
        }
        for (int i = 1; i < lines.size(); i++) {
            String text = lines.get(i).text();
            if (text.equals("---") || text.equals("...")) {
                return i + 1;
            }
        }
        return 0;
    }

    private static int[] indentation(String text) {
        int columns = 0;
        int chars = 0;
        while (chars < text.length() && (text.charAt(chars) == ' ' || text.charAt(chars) == '\t')) {
            columns = text.charAt(chars) == '\t' ? (columns / 4 + 1) * 4 : columns + 1;
            chars++;
        }
        return new int[]{columns, chars};
    }

    private static int run(String text, char c) {
        int count = 0;
        while (count < text.length() && text.charAt(count) == c) {
            count++;
        }
        return count;
    }

    private static int atx(String body) {
        int level = run(body, '#');
        if (level == 0 || level > 6) {
            return 0;
        }
        return level == body.length() || body.charAt(level) == ' ' || body.charAt(level) == '\t' ? level : 0;
    }

    private static String atxTitle(String body, int level) {
        String title = body.substring(level).strip();
        int end = title.length();
        while (end > 0 && title.charAt(end - 1) == '#') {
            end--;
        }
        if (end < title.length() && (end == 0 || title.charAt(end - 1) == ' ' || title.charAt(end - 1) == '\t')) {
            title = title.substring(0, end).strip();
        }
        return title;
    }

    private static int setext(String body) {
        String trimmed = body.stripTrailing();
        if (!trimmed.isEmpty() && run(trimmed, '=') == trimmed.length()) {
            return 1;
        }
        if (!trimmed.isEmpty() && run(trimmed, '-') == trimmed.length()) {
            return 2;
        }
        return 0;
    }

    private static boolean thematicBreak(String body) {
        String compact = body.replace(" ", "").replace("\t", "");
        return compact.length() >= 3 && (run(compact, '*') == compact.length() || run(compact, '_') == compact.length()
                || run(compact, '-') == compact.length());
    }
}
