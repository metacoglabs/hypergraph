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
import java.util.function.DoubleFunction;
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
            boolean checked = !(entry.getValue().get("checked") instanceof Json.Bool(boolean value)) || value;
            boolean agree = disk || workload.startsWith("ingest") || workload.equals("reopen")
                    || Stream.concat(subjects.stream(), baselines.stream())
                    .map(run -> number(run.results().get(workload).get("checksum"))).distinct().count() == 1;
            out.append("| `%s`: %s | %s | %s | **%.2f×** | %s |%n".formatted(workload, text(entry.getValue().get("description")),
                    cell(mine, disk, single), cell(theirs, disk, single), ratio, !checked ? "n/a" : agree ? "yes" : "**no**"));
        }
        out.append("%nRatios above 1 favour %s: throughput ratios divide %s by %s; latency and size ratios divide %s by %s.%n"
                .formatted(subject.store(), subject.store(), baseline.store(), baseline.store(), subject.store()));
        latency(out, subjects, baselines);
        resources(out, subjects, baselines);
        return out.toString();
    }

    private record Metric(String key, String name, boolean perOperation, DoubleFunction<String> format) {
    }

    private static final List<Metric> METRICS = List.of(new Metric("bytesWritten", "bytes written per operation", true, Report::bytes),
            new Metric("allocated", "heap allocated per operation", true, Report::bytes),
            new Metric("gcMillis", "GC pause time", false, value -> "%,.0f ms".formatted(value)));

    private static void resources(StringBuilder out, List<Run> subjects, List<Run> baselines) {
        List<Run> all = Stream.concat(subjects.stream(), baselines.stream()).toList();
        StringBuilder rows = new StringBuilder();
        for (Metric metric : METRICS) {
            for (String workload : subjects.getFirst().results().keySet()) {
                if (all.stream().allMatch(run -> run.results().containsKey(workload) && run.results().get(workload).containsKey(metric.key()))) {
                    double mine = median(metric(subjects, workload, metric));
                    double theirs = median(metric(baselines, workload, metric));
                    if (mine == 0 && theirs == 0) {
                        continue;
                    }
                    double ratio = theirs / mine;
                    rows.append("| `%s` | %s | %s | %s | %s |%n".formatted(workload, metric.name(), metric.format().apply(mine),
                            metric.format().apply(theirs), Double.isFinite(ratio) ? "**%.2f×**".formatted(ratio) : "n/a"));
                }
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        out.append("%nResources used by each workload, median of the runs:%n%n".formatted());
        out.append("| Workload | Measure | %s | %s | Ratio |%n".formatted(subjects.getFirst().store(), baselines.getFirst().store()));
        out.append("|---|---|---:|---:|---:|%n".formatted()).append(rows);
        if (all.stream().allMatch(run -> run.header().containsKey("retainedHeap"))) {
            out.append("%nHeap retained by the loaded store after a full GC: %s %s, %s %s.%n".formatted(
                    subjects.getFirst().store(), bytes(median(header(subjects, "retainedHeap"))),
                    baselines.getFirst().store(), bytes(median(header(baselines, "retainedHeap")))));
        }
        if (all.stream().allMatch(run -> run.header().containsKey("bytesWritten"))) {
            out.append("%nBytes written in total, after a final flush: %s %s, %s %s.%n".formatted(
                    subjects.getFirst().store(), bytes(median(header(subjects, "bytesWritten"))),
                    baselines.getFirst().store(), bytes(median(header(baselines, "bytesWritten")))));
        }
    }

    private static double[] metric(List<Run> runs, String workload, Metric metric) {
        return runs.stream().mapToDouble(run -> {
            Map<String, Json> result = run.results().get(workload);
            double value = number(result.get(metric.key()));
            return metric.perOperation() ? value / number(result.get("operations")) : value;
        }).sorted().toArray();
    }

    private static double[] header(List<Run> runs, String key) {
        return runs.stream().mapToDouble(run -> number(run.header().get(key))).sorted().toArray();
    }

    private static String bytes(double value) {
        if (value < 1024) {
            return "%.0f B".formatted(value);
        }
        return value < 1 << 20 ? "%.1f KiB".formatted(value / 1024) : "%,.1f MiB".formatted(value / (1 << 20));
    }

    private static void latency(StringBuilder out, List<Run> subjects, List<Run> baselines) {
        List<Run> all = Stream.concat(subjects.stream(), baselines.stream()).toList();
        List<String> workloads = subjects.getFirst().results().keySet().stream()
                .filter(workload -> all.stream().allMatch(run -> latency(run, workload) != null))
                .toList();
        if (workloads.isEmpty()) {
            return;
        }
        out.append("%nLatency of single operations, median of the runs:%n%n".formatted());
        out.append("| Workload | Percentile | %s | %s | Ratio |%n".formatted(subjects.getFirst().store(), baselines.getFirst().store()));
        out.append("|---|---|---:|---:|---:|%n".formatted());
        for (String workload : workloads) {
            for (Latency.Percentile percentile : Latency.PERCENTILES) {
                double mine = median(percentiles(subjects, workload, percentile));
                double theirs = median(percentiles(baselines, workload, percentile));
                out.append("| `%s` | %s | %s | %s | **%.2f×** |%n".formatted(workload, percentile.name(), micros(mine), micros(theirs), theirs / mine));
            }
        }
    }

    private static Json.Obj latency(Run run, String workload) {
        Map<String, Json> result = run.results().get(workload);
        return result != null && result.get("latency") instanceof Json.Obj latency ? latency : null;
    }

    private static double[] percentiles(List<Run> runs, String workload, Latency.Percentile percentile) {
        return runs.stream().mapToDouble(run -> number(latency(run, workload).fields().get(percentile.name()))).sorted().toArray();
    }

    private static String micros(double value) {
        return value < 1000 ? "%.1f µs".formatted(value) : "%,.2f ms".formatted(value / 1000);
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
