// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.query;

import io.hstore.engine.HStoreException;

import java.util.ArrayList;
import java.util.List;

final class Lexer {

    private static final String SYMBOLS = "(){}[],.:;=<>+-*/@|&";

    private Lexer() {
    }

    static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < source.length() && source.charAt(i + 1) == '-') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '\'' || c == '"') {
                i = string(source, i, c, tokens);
            } else if (Character.isDigit(c) || (c == '-' && i + 1 < source.length() && Character.isDigit(source.charAt(i + 1)) && numericContext(tokens))) {
                int start = i++;
                while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '_'
                        || (source.charAt(i) == '.' && i + 1 < source.length() && Character.isDigit(source.charAt(i + 1)))
                        || ((source.charAt(i) == 'e' || source.charAt(i) == 'E') && i + 1 < source.length()
                        && (Character.isDigit(source.charAt(i + 1)) || source.charAt(i + 1) == '-')))) {
                    i += (source.charAt(i) == 'e' || source.charAt(i) == 'E') && source.charAt(i + 1) == '-' ? 2 : 1;
                }
                tokens.add(new Token(Token.Kind.NUMBER, source.substring(start, i).replace("_", ""), start));
            } else if (c == '$') {
                int start = i++;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(Token.Kind.VARIABLE, source.substring(start + 1, i), start));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(Token.Kind.WORD, source.substring(start, i), start));
            } else if (i + 1 < source.length() && isPair(source.substring(i, i + 2))) {
                tokens.add(new Token(Token.Kind.SYMBOL, source.substring(i, i + 2), i));
                i += 2;
            } else if (SYMBOLS.indexOf(c) >= 0) {
                tokens.add(new Token(Token.Kind.SYMBOL, String.valueOf(c), i));
                i++;
            } else {
                throw HStoreException.invalid("unexpected character '" + c + "' at offset " + i);
            }
        }
        tokens.add(new Token(Token.Kind.END, "", source.length()));
        return tokens;
    }

    private static boolean isPair(String candidate) {
        return candidate.equals("<=") || candidate.equals(">=") || candidate.equals("!=") || candidate.equals("<>");
    }

    private static boolean numericContext(List<Token> tokens) {
        if (tokens.isEmpty()) {
            return true;
        }
        Token previous = tokens.getLast();
        return previous.kind() == Token.Kind.SYMBOL && !previous.text().equals(")") && !previous.text().equals("]");
    }

    private static int string(String source, int start, char quote, List<Token> tokens) {
        StringBuilder text = new StringBuilder();
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == quote) {
                if (i + 1 < source.length() && source.charAt(i + 1) == quote) {
                    text.append(quote);
                    i += 2;
                    continue;
                }
                tokens.add(new Token(Token.Kind.STRING, text.toString(), start));
                return i + 1;
            }
            text.append(c);
            i++;
        }
        throw HStoreException.invalid("unterminated string starting at offset " + start);
    }
}
