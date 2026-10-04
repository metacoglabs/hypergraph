package io.hstore.bench;

import io.hstore.db.value.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class Report {

    private record Run(String store, String version, String durability, Map<String, Json> header, Map<String, Map<String, Json>> results) {
    }

    private Report() {
    }

    static String markdown(String[] args) {
        List<Run> runs = Arrays.stream(args).skip(1).map(Path::of).map(Report::load).toList();
        if (runs.size() < 2) {
            throw new IllegalArgumentException("report needs at least two result files");
        }
        Run baseline = runs.getLast();
        Run subject = runs.getFirst();
        StringBuilder out = new StringBuilder();
        Map<String, Json> header = subject.header();
        out.append("Dataset: %s nodes, %s hyperedges, %s incidences; durability %s; %s threads.%n"
                .formatted(text(header.get("nodes")), text(header.get("edges")), text(header.get("incidences")), subject.durability(),
                        text(header.get("threads"))));
        out.append("Environment: %s, %s processors, %s, max heap %s MiB.%n%n".formatted(text(header.get("os")), text(header.get("processors")),
                text(header.get("java")), number(header.get("maxHeap")) / (1 << 20)));
        out.append("Caches: %s uses a %s; %s uses a %s.%n%n".formatted(subject.store(), text(subject.header().get("cache")),
                baseline.store(), text(baseline.header().get("cache"))));
        out.append("| Workload | %s | %s | Ratio | Results agree |%n".formatted(subject.store(), baseline.store()));
        out.append("|---|---:|---:|---:|:---:|%n".formatted());
        for (Map.Entry<String, Map<String, Json>> entry : subject.results().entrySet()) {
            String workload = entry.getKey();
            Map<String, Json> mine = entry.getValue();
            Map<String, Json> theirs = baseline.results().get(workload);
            if (theirs == null) {
                continue;
            }
            boolean disk = workload.equals("disk");
            boolean single = number(mine.get("operations")) == 1;
            double a = disk ? number(mine.get("operations")) : single ? number(mine.get("millis")) : rate(mine);
            double b = disk ? number(theirs.get("operations")) : single ? number(theirs.get("millis")) : rate(theirs);
            double ratio = disk || single ? b / a : a / b;
            boolean agree = disk || workload.startsWith("ingest") || workload.equals("reopen")
                    || Objects.equals(number(mine.get("checksum")), number(theirs.get("checksum")));
            out.append("| `%s` — %s | %s | %s | **%.2f×** | %s |%n".formatted(workload, text(mine.get("description")),
                    format(a, disk, single), format(b, disk, single), ratio, agree ? "yes" : "**no**"));
        }
        out.append("%nRatios above 1 favour %s: throughput ratios divide %s by %s; latency and size ratios divide %s by %s.%n"
                .formatted(subject.store(), subject.store(), baseline.store(), baseline.store(), subject.store()));
        return out.toString();
    }

    private static String format(double value, boolean disk, boolean single) {
        if (disk) {
            return "%.1f MiB".formatted(value / (1 << 20));
        }
        if (single) {
            return "%,.0f ms".formatted(value);
        }
        return "%,.0f ops/s".formatted(value);
    }

    private static double rate(Map<String, Json> result) {
        return number(result.get("operations")) / (number(result.get("millis")) / 1000.0);
    }

    private static double number(Json json) {
        return json instanceof Json.Number(var value) ? value.doubleValue() : Double.NaN;
    }

    private static String text(Json json) {
        return switch (json) {
            case Json.Str(String value) -> value;
            case Json.Number(var value) -> "%,d".formatted(value.longValue());
            case null -> "?";
            default -> json.print();
        };
    }

    private static Run load(Path file) {
        try {
            Map<String, Json> fields = ((Json.Obj) Json.parse(Files.readString(file))).fields();
            Map<String, Map<String, Json>> results = new LinkedHashMap<>();
            List<Json> items = new ArrayList<>(((Json.Array) fields.get("results")).items());
            for (Json item : items) {
                Map<String, Json> result = ((Json.Obj) item).fields();
                results.put(text(result.get("workload")), result);
            }
            return new Run(text(fields.get("store")), text(fields.get("version")), text(fields.get("durability")), fields, results);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
