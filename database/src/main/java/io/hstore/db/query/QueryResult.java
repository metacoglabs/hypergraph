// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.query;

import io.hstore.db.value.Json;
import io.hstore.db.value.Value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

public record QueryResult(List<String> columns, List<List<Cell>> rows, String message, Optional<Trace> trace) {

    public sealed interface Cell {
        String render();

        record Scalar(Value value) implements Cell {
            @Override
            public String render() {
                return value instanceof Value.Text(String text) ? text : value.render();
            }
        }

        record AtomCell(long id, String label) implements Cell {
            @Override
            public String render() {
                return label.isEmpty() ? "@" + id : "@" + id + " " + label;
            }
        }

        record Items(List<Cell> items) implements Cell {
            @Override
            public String render() {
                return items.stream().map(Cell::render).collect(Collectors.joining(", ", "[", "]"));
            }
        }
    }

    public record Trace(long txnId, long generation, String plan, double estimatedRows, long outputRows,
                        long pagesRead, long cacheHits, long cpuNanos) {
    }

    public QueryResult {
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
    }

    public static QueryResult message(String text) {
        return new QueryResult(List.of(), List.of(), text, Optional.empty());
    }

    public static QueryResult table(List<String> columns, List<List<Cell>> rows) {
        return new QueryResult(columns, rows, rows.size() + (rows.size() == 1 ? " row" : " rows"), Optional.empty());
    }

    public QueryResult withTrace(Trace value) {
        return new QueryResult(columns, rows, message, Optional.of(value));
    }

    public static Cell scalar(Value value) {
        return new Cell.Scalar(value);
    }

    public static Cell number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? new Cell.Scalar(new Value.Int((long) value)) : new Cell.Scalar(new Value.Real(value));
    }

    public static Cell text(String value) {
        return new Cell.Scalar(new Value.Text(value));
    }

    public String toJson() {
        return json().print();
    }

    public Json.Obj json() {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("columns", new Json.Array(columns.stream().<Json>map(Json.Str::new).toList()));
        fields.put("rows", new Json.Array(rows.stream().<Json>map(row -> new Json.Array(row.stream().map(QueryResult::json).toList())).toList()));
        fields.put("message", new Json.Str(message));
        trace.ifPresent(value -> {
            Map<String, Json> traced = new LinkedHashMap<>();
            traced.put("generation", integer(value.generation()));
            traced.put("plan", new Json.Str(value.plan()));
            traced.put("estimatedRows", integer(Math.round(value.estimatedRows())));
            traced.put("pagesRead", integer(value.pagesRead()));
            traced.put("cacheHits", integer(value.cacheHits()));
            traced.put("elapsedMicros", integer(value.cpuNanos() / 1_000));
            fields.put("trace", new Json.Obj(traced));
        });
        return new Json.Obj(fields);
    }

    public LongStream atoms() {
        return rows.stream().flatMap(List::stream).flatMapToLong(QueryResult::atoms).distinct();
    }

    private static LongStream atoms(Cell cell) {
        return switch (cell) {
            case Cell.AtomCell(long id, String _) -> LongStream.of(id);
            case Cell.Items(List<Cell> items) -> items.stream().flatMapToLong(QueryResult::atoms);
            case Cell.Scalar _ -> LongStream.empty();
        };
    }

    private static Json integer(long value) {
        return new Json.Number(BigDecimal.valueOf(value));
    }

    public static Json json(Value value) {
        return switch (value) {
            case Value.Null _ -> Json.NULL;
            case Value.Bool(boolean flag) -> new Json.Bool(flag);
            case Value.Int(long number) -> integer(number);
            case Value.Real(double number) -> Double.isFinite(number) ? new Json.Number(BigDecimal.valueOf(number)) : new Json.Str(Double.toString(number));
            case Value.Decimal(BigDecimal number) -> new Json.Number(number);
            case Value.Text(String text) -> new Json.Str(text);
            default -> new Json.Str(value.render());
        };
    }

    private static Json json(Cell cell) {
        return switch (cell) {
            case Cell.Scalar(Value value) -> json(value);
            case Cell.AtomCell(long id, String label) -> new Json.Obj(Map.of("id", integer(id), "label", new Json.Str(label)));
            case Cell.Items(List<Cell> items) -> new Json.Array(items.stream().map(QueryResult::json).toList());
        };
    }

    public String render() {
        if (columns.isEmpty()) {
            return message;
        }
        List<List<String>> cells = new ArrayList<>();
        cells.add(columns);
        rows.forEach(row -> cells.add(row.stream().map(Cell::render).toList()));
        int[] widths = new int[columns.size()];
        for (List<String> row : cells) {
            for (int i = 0; i < row.size(); i++) {
                widths[i] = Math.min(60, Math.max(widths[i], row.get(i).length()));
            }
        }
        StringBuilder out = new StringBuilder();
        String rule = separator(widths);
        out.append(rule).append(line(cells.getFirst(), widths)).append(rule);
        cells.stream().skip(1).forEach(row -> out.append(line(row, widths)));
        out.append(rule).append(message);
        trace.ifPresent(value -> out.append("\n").append("generation ").append(value.generation())
                .append(", pages read ").append(value.pagesRead()).append(", cache hits ").append(value.cacheHits())
                .append(", ").append(value.cpuNanos() / 1_000_000.0).append(" ms"));
        return out.toString();
    }

    private static String separator(int[] widths) {
        StringBuilder out = new StringBuilder("+");
        for (int width : widths) {
            out.repeat("-", width + 2).append('+');
        }
        return out.append('\n').toString();
    }

    private static String line(List<String> row, int[] widths) {
        StringBuilder out = new StringBuilder("|");
        for (int i = 0; i < widths.length; i++) {
            String value = row.get(i).length() > widths[i] ? row.get(i).substring(0, widths[i] - 1) + "…" : row.get(i);
            out.append(' ').append(value).repeat(" ", widths[i] - value.length()).append(" |");
        }
        return out.append('\n').toString();
    }
}
