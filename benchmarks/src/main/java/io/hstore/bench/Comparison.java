package io.hstore.bench;

import io.hstore.db.value.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;

public final class Comparison {

    record Result(String workload, String description, long operations, double millis, long checksum) {

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
            return new Json.Obj(fields);
        }
    }

    @FunctionalInterface
    private interface Slice {
        long run(int from, int to);
    }

    private static final int BATCH = 1_000;
    private static final long SEED = 20_260_904L;

    private Comparison() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        switch (args.length == 0 ? "" : args[0]) {
            case "run" -> run(options);
            case "report" -> IO.println(Report.markdown(args));
            default -> {
                IO.println("""
                        usage: Comparison run --store hstore|hypergraphdb [--scale N] [--durability async|sync] [--threads N] [--history N] --out FILE
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
        Store store = switch (storeName) {
            case "hstore" -> new HStoreStore(directory, sync, Integer.parseInt(options.getOrDefault("history", "64")));
            case "hypergraphdb" -> new HyperGraphDbStore(directory, sync);
            default -> throw new IllegalArgumentException("unknown store " + storeName);
        };
        IO.println("%s: %,d nodes, %,d hyperedges, %,d incidences, durability %s, %d threads"
                .formatted(store.name(), dataset.nodes(), dataset.edges().length, dataset.incidences(), sync ? "sync" : "async", threads));
        try {
            results.add(measure("ingest.nodes", "insert nodes in transactions of 1,000", dataset.nodes(), () -> {
                store.ingestNodes(dataset, BATCH);
                return dataset.nodes();
            }));
            results.add(measure("ingest.edges", "insert hyperedges (mean cardinality %.1f) in transactions of 1,000"
                    .formatted((double) dataset.incidences() / dataset.edges().length), dataset.edges().length, () -> {
                store.ingestEdges(dataset, BATCH);
                return dataset.incidences();
            }));
            int[] nodes = dataset.probeNodes();
            results.add(read("read.incidence", "enumerate the incidence set of a node", nodes.length,
                    (from, to) -> store.incidence(nodes, from, to), true));
            results.add(read("read.members", "enumerate the members of a hyperedge", dataset.probeEdges().length,
                    (from, to) -> store.members(dataset.probeEdges(), from, to), true));
            results.add(read("read.comembership", "count hyperedges containing both atoms of a pair", dataset.probePairs().length,
                    (from, to) -> store.coMembership(dataset.probePairs(), from, to), true));
            results.add(concurrent(store, dataset, threads));
            int[] round = {0};
            results.add(measure("write.update", "replace a node value in transactions of 1,000", dataset.updates().length,
                    () -> batched(dataset.updates().length, (from, to) -> {
                        store.update(dataset.updates(), from, to, round[0]++);
                        return to - from;
                    })));
            int largeSize = dataset.nodes();
            results.add(measure("large.ingest", "create one hyperedge with %,d members".formatted(largeSize), largeSize, () -> {
                store.largeEdge(largeSize);
                return largeSize;
            }));
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
        } finally {
            store.close();
        }
        long disk = store.diskBytes();
        results.add(new Result("disk", "bytes on disk after a clean shutdown", disk, 1000.0, disk));
        write(out, store, scale, sync, threads, dataset, results);
        results.forEach(result -> IO.println("  %-22s %,14.0f ops/s  %,10.1f ms  checksum %d"
                .formatted(result.workload(), result.rate(), result.millis(), result.checksum())));
    }

    private static Result measure(String workload, String description, long operations, LongSupplier work) {
        long started = System.nanoTime();
        long checksum = work.getAsLong();
        return new Result(workload, description, operations, (System.nanoTime() - started) / 1e6, checksum);
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

    private static Result concurrent(Store store, Dataset dataset, int threads) throws Exception {
        int[] probes = dataset.probeNodes();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
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
                    (long) threads * probes.length, millis, checksum);
        }
    }

    private static void write(Path out, Store store, int scale, boolean sync, int threads, Dataset dataset, List<Result> results) {
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
        fields.put("results", new Json.Array(results.stream().map(Result::json).toList()));
        try {
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, new Json.Obj(fields).print());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
