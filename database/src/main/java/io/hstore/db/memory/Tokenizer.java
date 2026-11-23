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
            int start = i;
            List<String> parts = new ArrayList<>();
            while (true) {
                int partStart = i;
                while (i < length && word(folded.codePointAt(i))) {
                    i += Character.charCount(folded.codePointAt(i));
                }
                parts.add(folded.substring(partStart, i));
                if (i + 1 < length && joiner(folded.charAt(i)) && word(folded.codePointAt(i + 1))) {
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

    static String fold(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        StringBuilder plain = new StringBuilder(decomposed.length());
        decomposed.codePoints().filter(point -> Character.getType(point) != Character.NON_SPACING_MARK).forEach(plain::appendCodePoint);
        return Normalizer.normalize(plain.toString().toLowerCase(Locale.ROOT), Normalizer.Form.NFKC);
    }

    private static boolean word(int point) {
        return Character.isLetterOrDigit(point);
    }

    private static boolean joiner(char c) {
        return c == '.' || c == '-' || c == '_';
    }

    private static String capped(String token) {
        return token.codePointCount(0, token.length()) <= MAX_LENGTH ? token : token.substring(0, token.offsetByCodePoints(0, MAX_LENGTH));
    }
}
