// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.bench;

import io.hstore.db.value.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntToLongFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.concurrent.locks.LockSupport;

public final class Comparison {

    record Result(String workload, String description, long operations, double millis, long checksum, Map<String, Json> extra) {

        Result(String workload, String description, long operations, double millis, long checksum) {
            this(workload, description, operations, millis, checksum, Map.of());
        }

        Result using(JvmUsage usage) {
            return with("gcCount", number(usage.collections())).with("gcMillis", number(usage.pauseMillis()))
                    .with("allocated", number(usage.allocated()));
        }

        Result with(String key, Json value) {
            Map<String, Json> fields = new LinkedHashMap<>(extra);
            fields.put(key, value);
            return new Result(workload, description, operations, millis, checksum, fields);
        }

        double rate() {
            return operations / (millis / 1000.0);
        }

        Json json() {
            Map<String, Json> fields = new LinkedHashMap<>();
            fields.put("workload", new Json.Str(workload));
            fields.put("description", new Json.Str(description));
            fields.put("operations", new Json.Number(BigDecimal.valueOf(operations)));
            fields.put("millis", new Json.Number(BigDecimal.valueOf(millis)));
            fields.put("checksum", new Json.Number(BigDecimal.valueOf(checksum)));
            fields.putAll(extra);
            return new Json.Obj(fields);
        }
    }

    @FunctionalInterface
    private interface Slice {
        long run(int from, int to);
    }

    private static final int BATCH = 1_000;
    private static final int COMMITS = 5_000;
    private static final String COMMITTED = "committed ";
    private static final long MIXED_NANOS = 10_000_000_000L;
    private static final int MIXED_BATCH = 10;
    private static final int MIXED_UPDATES_PER_SECOND = 1_000;
    private static final int SAMPLES_PER_THREAD = 200_000;
    private static final long SEED = 20_260_904L;

    private Comparison() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        switch (args.length == 0 ? "" : args[0]) {
            case "run" -> run(options);
            case "crash" -> crash(options);
            case "report" -> IO.println(Report.markdown(args));
            default -> {
                IO.println("""
                        usage: Comparison run --store hstore|hypergraphdb [--scale N] [--durability async|sync] [--threads N] [--history N] [--cache-mb N] --out FILE
                               Comparison crash --store hstore|hypergraphdb --dir DIRECTORY (used by run; ingests until killed)
                               Comparison report FILE... (runs of two stores; cells show the median and range)""");
                System.exit(2);
            }
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i++) {
            if (args[i].startsWith("--")) {
                options.put(args[i].substring(2), args[++i]);
            }
        }
        return options;
    }

    private static void run(Map<String, String> options) throws Exception {
        String storeName = options.getOrDefault("store", "hstore");
        int scale = Integer.parseInt(options.getOrDefault("scale", "1"));
        boolean sync = options.getOrDefault("durability", "async").equals("sync");
        int threads = Integer.parseInt(options.getOrDefault("threads", String.valueOf(Runtime.getRuntime().availableProcessors())));
        Path out = Path.of(options.getOrDefault("out", "results/" + storeName + ".json"));
        Path directory = Files.createTempDirectory("hstore-bench-" + storeName);
        Dataset dataset = Dataset.generate(scale, SEED);
        List<Result> results = new ArrayList<>();
        Map<String, Json> totals = new LinkedHashMap<>();
        long baselineHeap = JvmUsage.retainedHeap();
        Store store = open(options, directory, true);
        IO.println("%s: %,d nodes, %,d hyperedges, %,d incidences, durability %s, %d threads"
                .formatted(store.name(), dataset.nodes(), dataset.edges().length, dataset.incidences(), sync ? "sync" : "async", threads));
        try {
            results.add(writing(store, () -> measure("ingest.nodes", "insert nodes in transactions of 1,000", dataset.nodes(), () -> {
                store.ingestNodes(dataset, BATCH);
                return dataset.nodes();
            })));
            results.add(writing(store, () -> measure("ingest.edges", "insert hyperedges (mean cardinality %.1f) in transactions of 1,000"
                    .formatted((double) dataset.incidences() / dataset.edges().length), dataset.edges().length, () -> {
                store.ingestEdges(dataset, BATCH);
                return dataset.incidences();
            })));
            totals.put("retainedHeap", number(JvmUsage.retainedHeap() - baselineHeap));
            int[] nodes = dataset.probeNodes();
            results.add(read("read.incidence", "enumerate the incidence set of a node", nodes.length,
                    (from, to) -> store.incidence(nodes, from, to), true));
            results.add(read("read.members", "enumerate the members of a hyperedge", dataset.probeEdges().length,
                    (from, to) -> store.members(dataset.probeEdges(), from, to), true));
            results.add(read("read.comembership", "count hyperedges containing both atoms of a pair", dataset.probePairs().length,
                    (from, to) -> store.coMembership(dataset.probePairs(), from, to), true));
            int[] twoHop = dataset.probeTwoHop();
            results.add(read("read.twohop", "count the distinct nodes that share a hyperedge with a node", twoHop.length,
                    (from, to) -> store.twoHop(twoHop, from, to), true));
            results.add(concurrent(store, dataset, threads));
            results.add(latency("latency.read", "one incidence lookup per read transaction", nodes.length,
                    i -> store.incidence(nodes, i, i + 1), true));
            int[] round = {0};
            results.add(writing(store, () -> measure("write.update", "replace a node value in transactions of 1,000", dataset.updates().length,
                    () -> batched(dataset.updates().length, (from, to) -> {
                        store.update(dataset.updates(), from, to, round[0]++);
                        return to - from;
                    }))));
            results.add(writing(store, () -> latency("latency.commit", "one node update per write transaction", COMMITS, i -> {
                store.update(dataset.updates(), i, i + 1, round[0]++);
                return 1;
            }, false)));
            results.addAll(mixed(store, dataset, threads));
            int largeSize = dataset.nodes();
            results.add(writing(store, () -> measure("large.ingest", "create one hyperedge with %,d members".formatted(largeSize), largeSize, () -> {
                store.largeEdge(largeSize);
                return largeSize;
            })));
            store.scanLargeEdge();
            results.add(measure("large.scan", "enumerate all members of the large hyperedge (x20)", 20L * largeSize, () -> {
                long total = 0;
                for (int i = 0; i < 20; i++) {
                    total += store.scanLargeEdge();
                }
                return total;
            }));
            results.add(read("large.probe", "test whether a node belongs to the large hyperedge", nodes.length,
                    (from, to) -> store.probeLargeEdge(nodes, from, to), true));
            results.add(measure("reopen", "close and reopen the database, including recovery", 1, () -> {
                store.reopen();
                return 1;
            }));
            results.add(read("read.incidence.cold", "incidence sets immediately after reopening", nodes.length,
                    (from, to) -> store.incidence(nodes, from, to), false));
            long[] loaded = new long[1];
            store.reopen(() -> loaded[0] = store.diskBytes());
            results.add(new Result("disk", "bytes on disk after a clean shutdown", loaded[0], 1000.0, loaded[0]));
            int[] deletions = dataset.deletions();
            results.add(writing(store, () -> measure("churn.delete", "delete %,d hyperedges (%d%%) in transactions of 1,000"
                    .formatted(deletions.length, Math.round(100.0 * deletions.length / dataset.edges().length)), deletions.length,
                    () -> batched(deletions.length, (from, to) -> {
                        store.deleteEdges(deletions, from, to);
                        return to - from;
                    }))));
            int[][] removals = dataset.removals();
            results.add(writing(store, () -> measure("churn.remove", "remove one member from each of %,d other hyperedges, 1,000 per transaction"
                    .formatted(removals.length), removals.length, () -> batched(removals.length, (from, to) -> {
                        store.removeMembers(removals, from, to);
                        return to - from;
                    }))));
            results.add(read("read.incidence.churned", "enumerate incidence sets after the deletes", nodes.length,
                    (from, to) -> store.incidence(nodes, from, to), true));
            store.flush();
            totals.put("bytesWritten", number(store.bytesWritten()));
        } finally {
            store.close();
        }
        long churned = store.diskBytes();
        results.add(new Result("disk.churned", "bytes on disk after the deletes and a clean shutdown", churned, 1000.0, churned));
        results.add(recover(options, dataset));
        write(out, store, scale, sync, threads, dataset, totals, results);
        results.forEach(result -> IO.println("  %-22s %,14.0f ops/s  %,10.1f ms  checksum %d"
                .formatted(result.workload(), result.rate(), result.millis(), result.checksum())));
    }

    private static Store open(Map<String, String> options, Path directory, boolean create) {
        boolean sync = options.getOrDefault("durability", "async").equals("sync");
        long cacheBytes = Long.parseLong(options.getOrDefault("cache-mb", "0")) << 20;
        return switch (options.getOrDefault("store", "hstore")) {
            case "hstore" -> new HStoreStore(directory, sync, Integer.parseInt(options.getOrDefault("history", "64")), cacheBytes, create);
            case "hypergraphdb" -> new HyperGraphDbStore(directory, sync, cacheBytes);
            case String other -> throw new IllegalArgumentException("unknown store " + other);
        };
    }

    private static void crash(Map<String, String> options) throws InterruptedException {
        Dataset dataset = Dataset.generate(Integer.parseInt(options.getOrDefault("scale", "1")), SEED);
        Store store = open(options, Path.of(options.get("dir")), true);
        store.ingestNodes(dataset, BATCH, committed -> {
            System.out.println(COMMITTED + committed);
            System.out.flush();
        });
        Thread.sleep(Long.MAX_VALUE);
    }

    private static Result recover(Map<String, String> options, Dataset dataset) throws Exception {
        Path directory = Files.createTempDirectory("hstore-bench-crash");
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), Comparison.class.getName(), "crash", "--dir", directory.toString()));
        for (String option : List.of("store", "scale", "durability", "history", "cache-mb")) {
            if (options.containsKey(option)) {
                command.addAll(List.of("--" + option, options.get(option)));
            }
        }
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
        int target = dataset.nodes() / 2;
        int acknowledged = 0;
        try (BufferedReader lines = child.inputReader()) {
            for (String line = lines.readLine(); line != null && acknowledged < target; line = lines.readLine()) {
                if (line.startsWith(COMMITTED)) {
                    acknowledged = Integer.parseInt(line.substring(COMMITTED.length()));
                }
            }
        } finally {
            child.destroyForcibly();
            child.waitFor();
        }
        if (acknowledged < target) {
            throw new IllegalStateException("the crash child exited after %,d acknowledged nodes".formatted(acknowledged));
        }
        Store[] recovered = new Store[1];
        Result result = measure("recover", "open the store after killing the ingest process with SIGKILL", 1, () -> {
            recovered[0] = open(options, directory, false);
            return 1;
        });
        long present;
        try {
            present = recovered[0].countNodes();
        } finally {
            recovered[0].close();
        }
        return result.with("checked", new Json.Bool(false)).with("acknowledged", number(acknowledged))
                .with("lost", number(Math.max(0, acknowledged - present)));
    }

    private static Json number(long value) {
        return new Json.Number(BigDecimal.valueOf(value));
    }

    private static Result measure(String workload, String description, long operations, LongSupplier work) {
        JvmUsage usage = JvmUsage.now();
        long started = System.nanoTime();
        long checksum = work.getAsLong();
        return new Result(workload, description, operations, (System.nanoTime() - started) / 1e6, checksum).using(JvmUsage.now().since(usage));
    }

    private static long batched(int length, Slice slice) {
        long total = 0;
        for (int from = 0; from < length; from += BATCH) {
            total += slice.run(from, Math.min(length, from + BATCH));
        }
        return total;
    }

    private static Result read(String workload, String description, int operations, Slice slice, boolean warm) {
        if (warm) {
            batched(Math.max(BATCH, operations / 10), slice);
        }
        return measure(workload, description, operations, () -> batched(operations, slice));
    }

    private static Result writing(Store store, Supplier<Result> workload) {
        long before = store.bytesWritten();
        Result result = workload.get();
        store.flush();
        return result.with("bytesWritten", new Json.Number(BigDecimal.valueOf(store.bytesWritten() - before)));
    }

    private static Result latency(String workload, String description, int operations, IntToLongFunction operation, boolean warm) {
        if (warm) {
            for (int i = 0; i < operations / 10; i++) {
                operation.applyAsLong(i);
            }
        }
        long[] nanos = new long[operations];
        long checksum = 0;
        JvmUsage usage = JvmUsage.now();
        long started = System.nanoTime();
        for (int i = 0; i < operations; i++) {
            long begin = System.nanoTime();
            checksum += operation.applyAsLong(i);
            nanos[i] = System.nanoTime() - begin;
        }
        return new Result(workload, description, operations, (System.nanoTime() - started) / 1e6, checksum)
                .using(JvmUsage.now().since(usage)).with("latency", Latency.of(nanos).json());
    }

    private static List<Result> mixed(Store store, Dataset dataset, int threads) throws Exception {
        int[] probes = dataset.probeNodes();
        int[] updates = dataset.updates();
        int readers = Math.max(1, threads - 1);
        Reservoir[] reads = new Reservoir[readers];
        Reservoir commits = new Reservoir(SAMPLES_PER_THREAD, SEED);
        long interval = 1_000_000_000L * MIXED_BATCH / MIXED_UPDATES_PER_SECOND;
        long before = store.bytesWritten();
        JvmUsage usage = JvmUsage.now();
        long started;
        long finished;
        long committed = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(readers)) {
            started = System.nanoTime();
            long deadline = started + MIXED_NANOS;
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < readers; t++) {
                Reservoir reservoir = reads[t] = new Reservoir(SAMPLES_PER_THREAD, SEED + t + 1);
                int offset = t * BATCH;
                futures.add(pool.submit(() -> {
                    for (int i = offset; System.nanoTime() < deadline; i = (i + 1) % probes.length) {
                        long begin = System.nanoTime();
                        store.incidence(probes, i, i + 1);
                        reservoir.add(System.nanoTime() - begin);
                    }
                }));
            }
            for (int k = 0; ; k++) {
                long intended = started + k * interval;
                if (intended >= deadline || System.nanoTime() >= deadline) {
                    break;
                }
                for (long wait = intended - System.nanoTime(); wait > 0; wait = intended - System.nanoTime()) {
                    LockSupport.parkNanos(wait);
                }
                int from = k * MIXED_BATCH % updates.length;
                store.update(updates, from, Math.min(updates.length, from + MIXED_BATCH), 1_000_000 + k);
                commits.add(System.nanoTime() - intended);
                committed += Math.min(updates.length, from + MIXED_BATCH) - from;
            }
            for (Future<?> future : futures) {
                future.get();
            }
            finished = System.nanoTime();
        }
        JvmUsage used = JvmUsage.now().since(usage);
        store.flush();
        double millis = (finished - started) / 1e6;
        long readCount = Arrays.stream(reads).mapToLong(Reservoir::seen).sum();
        String load = "%,d readers while one writer commits %d updates at a time, targeting %,d updates/s"
                .formatted(readers, MIXED_BATCH, MIXED_UPDATES_PER_SECOND);
        Result read = new Result("mixed.read", "single incidence lookups from " + load, readCount, millis, 0)
                .using(used).with("latency", Reservoir.merge(reads).json()).with("checked", new Json.Bool(false));
        Result write = new Result("mixed.write", "updates committed by the writer alongside the readers", committed, millis, 0)
                .with("latency", Reservoir.merge(commits).json()).with("checked", new Json.Bool(false))
                .with("bytesWritten", number(store.bytesWritten() - before));
        return List.of(read, write);
    }

    private static Result concurrent(Store store, Dataset dataset, int threads) throws Exception {
        int[] probes = dataset.probeNodes();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            JvmUsage usage = JvmUsage.now();
            long started = System.nanoTime();
            List<Future<Long>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int offset = t * BATCH;
                futures.add(pool.submit(() -> batched(probes.length, (from, to) -> {
                    int shiftedFrom = (from + offset) % probes.length;
                    int shiftedTo = Math.min(probes.length, shiftedFrom + (to - from));
                    return store.incidence(probes, shiftedFrom, shiftedTo);
                })));
            }
            long checksum = 0;
            for (Future<Long> future : futures) {
                checksum += future.get();
            }
            double millis = (System.nanoTime() - started) / 1e6;
            return new Result("read.incidence.parallel", "incidence sets from %d threads, %,d probes each".formatted(threads, probes.length),
                    (long) threads * probes.length, millis, checksum).using(JvmUsage.now().since(usage));
        }
    }

    private static void write(Path out, Store store, int scale, boolean sync, int threads, Dataset dataset, Map<String, Json> totals, List<Result> results) {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("store", new Json.Str(store.name()));
        fields.put("version", new Json.Str(store.version()));
        fields.put("cache", new Json.Str(store.cache()));
        fields.put("scale", new Json.Number(BigDecimal.valueOf(scale)));
        fields.put("durability", new Json.Str(sync ? "sync" : "async"));
        fields.put("threads", new Json.Number(BigDecimal.valueOf(threads)));
        fields.put("nodes", new Json.Number(BigDecimal.valueOf(dataset.nodes())));
        fields.put("edges", new Json.Number(BigDecimal.valueOf(dataset.edges().length)));
        fields.put("incidences", new Json.Number(BigDecimal.valueOf(dataset.incidences())));
        fields.put("java", new Json.Str(System.getProperty("java.vm.name") + " " + Runtime.version()));
        fields.put("os", new Json.Str(System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch")));
        fields.put("processors", new Json.Number(BigDecimal.valueOf(Runtime.getRuntime().availableProcessors())));
        fields.put("maxHeap", new Json.Number(BigDecimal.valueOf(Runtime.getRuntime().maxMemory())));
        fields.putAll(totals);
        fields.put("results", new Json.Array(results.stream().map(Result::json).toList()));
        try {
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, new Json.Obj(fields).print());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
