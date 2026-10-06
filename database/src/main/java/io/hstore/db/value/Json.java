// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.value;

import io.hstore.engine.HStoreException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public sealed interface Json {

    record Null() implements Json {
    }

    record Bool(boolean value) implements Json {
    }

    record Number(BigDecimal value) implements Json {
    }

    record Str(String value) implements Json {
    }

    record Array(List<Json> items) implements Json {
        public Array {
            items = List.copyOf(items);
        }
    }

    record Obj(Map<String, Json> fields) implements Json {
        public Obj {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }

    Json NULL = new Null();

    static Json parse(String text) {
        Parser parser = new Parser(text);
        Json value = parser.value();
        parser.skipSpace();
        if (!parser.done()) {
            throw parser.error("trailing characters");
        }
        return value;
    }

    default String print() {
        return switch (this) {
            case Null _ -> "null";
            case Bool(boolean value) -> Boolean.toString(value);
            case Number(BigDecimal value) -> value.toPlainString();
            case Str(String value) -> quote(value);
            case Array(List<Json> items) -> items.stream().map(Json::print).collect(Collectors.joining(",", "[", "]"));
            case Obj(Map<String, Json> fields) -> fields.entrySet().stream()
                    .map(entry -> quote(entry.getKey()) + ":" + entry.getValue().print())
                    .collect(Collectors.joining(",", "{", "}"));
        };
    }

    default Stream<Value> select(String path) {
        if (!path.startsWith("$")) {
            throw HStoreException.invalid("JSON paths start with '$': " + path);
        }
        Stream<Json> current = Stream.of(this);
        for (String step : steps(path)) {
            current = current.flatMap(node -> step(node, step));
        }
        return current.flatMap(Json::scalar);
    }

    private static List<String> steps(String path) {
        List<String> steps = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int i = 1; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.' || c == '[') {
                if (!token.isEmpty()) {
                    steps.add(token.toString());
                    token.setLength(0);
                }
                if (c == '[') {
                    int close = path.indexOf(']', i);
                    steps.add(path.substring(i, close + 1));
                    i = close;
                }
            } else {
                token.append(c);
            }
        }
        if (!token.isEmpty()) {
            steps.add(token.toString());
        }
        return steps;
    }

    private static Stream<Json> step(Json node, String step) {
        if (step.startsWith("[")) {
            if (!(node instanceof Array(List<Json> items))) {
                return Stream.empty();
            }
            String index = step.substring(1, step.length() - 1);
            if (index.equals("*")) {
                return items.stream();
            }
            int position = Integer.parseInt(index);
            return position < items.size() ? Stream.of(items.get(position)) : Stream.empty();
        }
        return node instanceof Obj(Map<String, Json> fields) && fields.containsKey(step) ? Stream.of(fields.get(step)) : Stream.empty();
    }

    private static Stream<Value> scalar(Json node) {
        return switch (node) {
            case Null _ -> Stream.of(Value.NULL);
            case Bool(boolean value) -> Stream.of(new Value.Bool(value));
            case Number(BigDecimal value) -> Stream.of(value.scale() <= 0 && value.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0
                    ? new Value.Int(value.longValue())
                    : new Value.Real(value.doubleValue()));
            case Str(String value) -> Stream.of(new Value.Text(value));
            case Array _, Obj _ -> Stream.empty();
        };
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    final class Parser {
        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        boolean done() {
            return position >= text.length();
        }

        void skipSpace() {
            while (!done() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        HStoreException error(String message) {
            return HStoreException.invalid("invalid JSON at offset " + position + ": " + message);
        }

        Json value() {
            skipSpace();
            if (done()) {
                throw error("unexpected end");
            }
            char c = text.charAt(position);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> new Str(string());
                case 't' -> literal("true", new Bool(true));
                case 'f' -> literal("false", new Bool(false));
                case 'n' -> literal("null", NULL);
                default -> number();
            };
        }

        private Json literal(String word, Json value) {
            if (!text.startsWith(word, position)) {
                throw error("expected " + word);
            }
            position += word.length();
            return value;
        }

        private Json object() {
            position++;
            Map<String, Json> fields = new LinkedHashMap<>();
            skipSpace();
            if (peek('}')) {
                position++;
                return new Obj(fields);
            }
            while (true) {
                skipSpace();
                String key = string();
                skipSpace();
                expect(':');
                fields.put(key, value());
                skipSpace();
                if (peek(',')) {
                    position++;
                    continue;
                }
                expect('}');
                return new Obj(fields);
            }
        }

        private Json array() {
            position++;
            List<Json> items = new ArrayList<>();
            skipSpace();
            if (peek(']')) {
                position++;
                return new Array(items);
            }
            while (true) {
                items.add(value());
                skipSpace();
                if (peek(',')) {
                    position++;
                    continue;
                }
                expect(']');
                return new Array(items);
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (!done()) {
                char c = text.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escape = text.charAt(position++);
                switch (escape) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                    }
                    default -> out.append(escape);
                }
            }
            throw error("unterminated string");
        }

        private Json number() {
            int start = position;
            while (!done() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
            if (start == position) {
                throw error("unexpected character '" + text.charAt(position) + "'");
            }
            try {
                return new Number(new BigDecimal(text.substring(start, position)));
            } catch (NumberFormatException e) {
                throw error("malformed number");
            }
        }

        private boolean peek(char c) {
            return !done() && text.charAt(position) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("expected '" + c + "'");
            }
            position++;
        }
    }
}
