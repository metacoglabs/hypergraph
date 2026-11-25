// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Tokenizer {

    public static final int MAX_LENGTH = 64;

    private Tokenizer() {
    }

    public static List<String> tokens(String text) {
        String folded = fold(text);
        List<String> tokens = new ArrayList<>();
        int length = folded.length();
        int i = 0;
        while (i < length) {
            int point = folded.codePointAt(i);
            if (!word(point)) {
                i += Character.charCount(point);
                continue;
            }
            if (dense(point)) {
                int start = i;
                while (i < length && dense(folded.codePointAt(i))) {
                    i += Character.charCount(folded.codePointAt(i));
                }
                bigrams(folded.substring(start, i), tokens);
                continue;
            }
            int start = i;
            List<String> parts = new ArrayList<>();
            while (true) {
                int partStart = i;
                while (i < length && word(folded.codePointAt(i)) && !dense(folded.codePointAt(i))) {
                    i += Character.charCount(folded.codePointAt(i));
                }
                parts.add(folded.substring(partStart, i));
                if (i + 1 < length && joiner(folded.charAt(i)) && word(folded.codePointAt(i + 1)) && !dense(folded.codePointAt(i + 1))) {
                    i++;
                    continue;
                }
                break;
            }
            parts.forEach(part -> tokens.add(capped(part)));
            if (parts.size() > 1) {
                tokens.add(capped(folded.substring(start, i)));
            }
        }
        return tokens;
    }

    private static void bigrams(String run, List<String> tokens) {
        int[] points = run.codePoints().toArray();
        if (points.length == 1) {
            tokens.add(new String(points, 0, 1));
        }
        for (int i = 0; i + 1 < points.length; i++) {
            tokens.add(new String(points, i, 2));
        }
    }

    static String fold(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        StringBuilder plain = new StringBuilder(decomposed.length());
        Character.UnicodeScript base = Character.UnicodeScript.COMMON;
        for (int point : decomposed.codePoints().toArray()) {
            if (Character.getType(point) == Character.NON_SPACING_MARK
                    && (base == Character.UnicodeScript.LATIN || base == Character.UnicodeScript.GREEK)) {
                continue;
            }
            if (!mark(point)) {
                base = Character.UnicodeScript.of(point);
            }
            plain.appendCodePoint(point);
        }
        return Normalizer.normalize(plain.toString().toLowerCase(Locale.ROOT), Normalizer.Form.NFKC);
    }

    private static boolean mark(int point) {
        int type = Character.getType(point);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }

    private static boolean word(int point) {
        return Character.isLetterOrDigit(point) || mark(point);
    }

    private static boolean dense(int point) {
        Character.UnicodeScript script = Character.UnicodeScript.of(point);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.THAI || script == Character.UnicodeScript.LAO || script == Character.UnicodeScript.KHMER
                || script == Character.UnicodeScript.MYANMAR || point == 0x30FC;
    }

    private static boolean joiner(char c) {
        return c == '.' || c == '-' || c == '_';
    }

    private static String capped(String token) {
        return token.codePointCount(0, token.length()) <= MAX_LENGTH ? token : token.substring(0, token.offsetByCodePoints(0, MAX_LENGTH));
    }
}
