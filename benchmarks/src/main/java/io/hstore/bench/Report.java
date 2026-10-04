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
import java.util.stream.Stream;

final class Report {

    private record Run(String store, String version, String durability, Map<String, Json> header, Map<String, Map<String, Json>> results) {
    }

    private Report() {
    }

    static String markdown(String[] args) {
        Map<String, List<Run>> byStore = new LinkedHashMap<>();
        Arrays.stream(args).skip(1).map(Path::of).map(Report::load)
                .forEach(run -> byStore.computeIfAbsent(run.store(), _ -> new ArrayList<>()).add(run));
        if (byStore.size() != 2) {
            throw new IllegalArgumentException("report needs runs of exactly two stores, got " + byStore.keySet());
        }
        List<List<Run>> stores = List.copyOf(byStore.values());
        List<Run> subjects = stores.getFirst();
        List<Run> baselines = stores.getLast();
        Run subject = subjects.getFirst();
        Run baseline = baselines.getFirst();
        StringBuilder out = new StringBuilder();
        Map<String, Json> header = subject.header();
        out.append("Dataset: %s nodes, %s hyperedges, %s incidences; durability %s; %s threads.%n"
                .formatted(text(header.get("nodes")), text(header.get("edges")), text(header.get("incidences")), subject.durability(),
                        text(header.get("threads"))));
        out.append("Environment: %s, %s processors, %s, max heap %s MiB.%n%n".formatted(text(header.get("os")), text(header.get("processors")),
                text(header.get("java")), number(header.get("maxHeap")) / (1 << 20)));
        out.append("Caches: %s uses a %s; %s uses a %s.%n%n".formatted(subject.store(), text(subject.header().get("cache")),
                baseline.store(), text(baseline.header().get("cache"))));
        out.append("Each cell is the median of %d runs of %s and %d runs of %s, with the range in brackets.%n%n"
                .formatted(subjects.size(), subject.store(), baselines.size(), baseline.store()));
        out.append("| Workload | %s | %s | Ratio of medians | Results agree |%n".formatted(subject.store(), baseline.store()));
        out.append("|---|---:|---:|---:|:---:|%n".formatted());
        for (Map.Entry<String, Map<String, Json>> entry : subject.results().entrySet()) {
            String workload = entry.getKey();
            if (baselines.stream().anyMatch(run -> !run.results().containsKey(workload))) {
                continue;
            }
            boolean disk = workload.equals("disk");
            boolean single = number(entry.getValue().get("operations")) == 1;
            double[] mine = values(subjects, workload, disk, single);
            double[] theirs = values(baselines, workload, disk, single);
            double ratio = disk || single ? median(theirs) / median(mine) : median(mine) / median(theirs);
            boolean agree = disk || workload.startsWith("ingest") || workload.equals("reopen")
                    || Stream.concat(subjects.stream(), baselines.stream())
                    .map(run -> number(run.results().get(workload).get("checksum"))).distinct().count() == 1;
            out.append("| `%s`: %s | %s | %s | **%.2f×** | %s |%n".formatted(workload, text(entry.getValue().get("description")),
                    cell(mine, disk, single), cell(theirs, disk, single), ratio, agree ? "yes" : "**no**"));
        }
        out.append("%nRatios above 1 favour %s: throughput ratios divide %s by %s; latency and size ratios divide %s by %s.%n"
                .formatted(subject.store(), subject.store(), baseline.store(), baseline.store(), subject.store()));
        return out.toString();
    }

    private static double[] values(List<Run> runs, String workload, boolean disk, boolean single) {
        return runs.stream().mapToDouble(run -> {
            Map<String, Json> result = run.results().get(workload);
            return disk ? number(result.get("operations")) : single ? number(result.get("millis")) : rate(result);
        }).sorted().toArray();
    }

    private static double median(double[] sorted) {
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    private static String cell(double[] sorted, boolean disk, boolean single) {
        String median = format(median(sorted), disk, single);
        return sorted.length == 1 ? median
                : median + " (" + bare(sorted[0], disk) + " to " + bare(sorted[sorted.length - 1], disk) + ")";
    }

    private static String bare(double value, boolean disk) {
        return disk ? "%.1f".formatted(value / (1 << 20)) : "%,.0f".formatted(value);
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
