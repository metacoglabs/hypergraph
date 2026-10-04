package io.hstore.server;

import io.hstore.engine.EngineOptions;
import io.hstore.engine.EngineStats;
import io.hstore.engine.StorageEngine;
import io.hstore.engine.page.IoTrace;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Hyperedge;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.tree.Keyset;
import io.hstore.engine.tree.TreeAlgebra;
import io.hstore.engine.tree.TreeDiff;
import io.hstore.engine.txn.Snapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.LongStream;
import java.util.stream.Stream;

final class Bench {

    record Measurement(String scenario, long operations, double p50, double p95, double p99,
                       long pagesRead, long pagesWritten, long walBytes, double writeAmplification, String note) {
    }

    private static final int EDGE = 1;
    private static final long LOGICAL_INCIDENCE_BYTES = 32;

    private final Path root;
    private final EngineOptions options;
    private final int scale;
    private final RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(42);

    private final Map<String, Function<StorageEngine, Measurement>> scenarios = Map.of(
            "small-edges", this::smallEdges,
            "medium-edges", this::mediumEdges,
            "giant-edge", this::giantEdge,
            "cardinality-skew", this::cardinalitySkew,
            "near-identical", this::nearIdentical,
            "disjoint", this::disjoint,
            "reverse-hotspot", this::reverseHotspot,
            "small-consolidation", this::smallConsolidation,
            "mvcc-stress", this::mvccStress);

    Bench(Path root, EngineOptions options, int scale) {
        this.root = root;
        this.options = options;
        this.scale = Math.max(1, scale);
    }

    List<String> names() {
        return scenarios.keySet().stream().sorted().toList();
    }

    List<Measurement> run(String selection) {
        List<String> chosen = selection.equals("all") ? names() : List.of(selection);
        List<Measurement> results = new ArrayList<>();
        for (String name : chosen) {
            Function<StorageEngine, Measurement> scenario = Optional.ofNullable(scenarios.get(name))
                    .orElseThrow(() -> new IllegalArgumentException("unknown scenario " + name + ", expected one of " + names()));
            Path directory = root.resolve(name);
            delete(directory);
            try (StorageEngine engine = StorageEngine.open(directory, options)) {
                Measurement measurement = scenario.apply(engine);
                results.add(measurement);
                IO.println(format(measurement));
            }
        }
        return results;
    }

    static String header() {
        return "%-20s %10s %10s %10s %10s %10s %10s %12s %8s  %s".formatted(
                "scenario", "ops", "p50 µs", "p95 µs", "p99 µs", "pg read", "pg write", "wal bytes", "WA", "notes");
    }

    static String format(Measurement m) {
        return "%-20s %10d %10.1f %10.1f %10.1f %10d %10d %12d %8.2f  %s".formatted(m.scenario(), m.operations(), m.p50(), m.p95(),
                m.p99(), m.pagesRead(), m.pagesWritten(), m.walBytes(), m.writeAmplification(), m.note());
    }

    private Measurement smallEdges(StorageEngine engine) {
        int edges = 20_000 * scale;
        int batch = 1_000;
        long[] incidences = {0};
        return measure(engine, "small-edges", edges / batch, () -> {
            long started = System.nanoTime();
            engine.write(txn -> {
                for (int i = 0; i < batch; i++) {
                    long edge = txn.createEdge(EDGE, EdgeKind.SET);
                    int size = 2 + random.nextInt(4);
                    for (int k = 0; k < size; k++) {
                        txn.upsert(edge, 1_000_000 + random.nextLong(50_000L * scale), incidence -> incidence);
                        incidences[0]++;
                    }
                }
                return null;
            });
            return System.nanoTime() - started;
        }, () -> incidences[0], "batches of " + batch + " edges with 2-5 members");
    }

    private Measurement mediumEdges(StorageEngine engine) {
        int edges = 1_000 * scale;
        List<Long> ids = engine.write(txn -> {
            List<Long> created = new ArrayList<>();
            for (int i = 0; i < edges; i++) {
                long edge = txn.createEdge(EDGE, EdgeKind.SET);
                txn.load(edge, LongStream.range(0, 100).mapToObj(k -> Incidence.of(1_000_000 + random.nextLong(100_000))).distinct().toList());
                created.add(edge);
            }
            return created;
        });
        return measure(engine, "medium-edges", 2_000, () -> {
            long edge = ids.get(random.nextInt(ids.size()));
            long member = 1_000_000 + random.nextLong(100_000);
            long started = System.nanoTime();
            engine.write(txn -> {
                if (txn.contains(edge, member)) {
                    txn.remove(edge, member);
                } else {
                    txn.insert(edge, member);
                }
                return null;
            });
            return System.nanoTime() - started;
        }, () -> 2_000, "single-member mutations on 100-member edges");
    }

    private Measurement giantEdge(StorageEngine engine) {
        int members = 1_000_000 * scale;
        long edge = engine.write(txn -> {
            long created = txn.createEdge(EDGE, EdgeKind.ORDERED);
            txn.load(created, LongStream.range(0, members).mapToObj(i -> Incidence.of(10_000_000 + i * 3)).toList());
            return created;
        });
        try (Snapshot snapshot = engine.snapshot()) {
            Hyperedge giant = snapshot.requireEdge(edge);
            return measure(engine, "giant-edge", 20_000, () -> {
                long index = random.nextLong(members);
                long started = System.nanoTime();
                Incidence at = giant.at(index);
                if (giant.indexOf(at.member()) != index) {
                    throw new IllegalStateException("rank access disagrees with the AtomID index");
                }
                return System.nanoTime() - started;
            }, () -> members, "E[i] + indexOf over " + members + " members, height " + giant.members().height());
        }
    }

    private Measurement cardinalitySkew(StorageEngine engine) {
        long[] edges = engine.write(txn -> {
            long small = txn.createEdge(EDGE, EdgeKind.SET);
            long large = txn.createEdge(EDGE, EdgeKind.SET);
            txn.load(small, LongStream.range(0, 100).mapToObj(i -> Incidence.of(5_000_000 + i * 997L * scale)).toList());
            txn.load(large, LongStream.range(0, 1_000_000L * scale).mapToObj(i -> Incidence.of(5_000_000 + i)).toList());
            return new long[]{small, large};
        });
        try (Snapshot snapshot = engine.snapshot()) {
            Hyperedge small = snapshot.requireEdge(edges[0]);
            Hyperedge large = snapshot.requireEdge(edges[1]);
            String strategy = TreeAlgebra.resolve(TreeAlgebra.Strategy.AUTO, small.membership(), large.membership()).name();
            IoTrace probe = IoTrace.unbounded();
            probe.run(() -> TreeAlgebra.countIntersect(small.membership(), large.membership(), TreeAlgebra.Strategy.PROBE));
            IoTrace sync = IoTrace.unbounded();
            sync.run(() -> TreeAlgebra.countIntersect(small.membership(), large.membership(), TreeAlgebra.Strategy.SYNCHRONIZED));
            return measure(engine, "cardinality-skew", 200, () -> {
                long started = System.nanoTime();
                TreeAlgebra.countIntersect(small.membership(), large.membership());
                return System.nanoTime() - started;
            }, () -> 0, "planner chose " + strategy + "; pages probe=" + probe.pageVisits() + " sync=" + sync.pageVisits());
        }
    }

    private Measurement nearIdentical(StorageEngine engine) {
        int members = 200_000 * scale;
        long original = engine.write(txn -> {
            long edge = txn.createEdge(EDGE, EdgeKind.SET);
            txn.load(edge, LongStream.range(0, members).mapToObj(i -> Incidence.of(20_000_000 + i)).toList());
            return edge;
        });
        long copy = engine.write(txn -> {
            long edge = txn.createEdge(EDGE, EdgeKind.SET);
            txn.load(edge, txn.requireEdge(original).stream().toList());
            return edge;
        });
        engine.write(txn -> {
            for (int i = 0; i < members / 100; i++) {
                txn.remove(copy, 20_000_000 + random.nextLong(members));
                txn.upsert(copy, 30_000_000 + i, incidence -> incidence);
            }
            return null;
        });
        try (Snapshot snapshot = engine.snapshot()) {
            Hyperedge a = snapshot.requireEdge(original);
            Hyperedge b = snapshot.requireEdge(copy);
            IoTrace diffTrace = IoTrace.unbounded();
            long deltas = diffTrace.call(() -> TreeDiff.diff(a.members(), b.members()).count());
            return measure(engine, "near-identical", 50, () -> {
                long started = System.nanoTime();
                TreeAlgebra.countIntersect(a.membership(), b.membership());
                return System.nanoTime() - started;
            }, () -> 0, deltas + " deltas found visiting " + diffTrace.pageVisits() + " pages");
        }
    }

    private Measurement disjoint(StorageEngine engine) {
        int members = 500_000 * scale;
        long[] edges = engine.write(txn -> {
            long low = txn.createEdge(EDGE, EdgeKind.SET);
            long high = txn.createEdge(EDGE, EdgeKind.SET);
            txn.load(low, LongStream.range(0, members).mapToObj(i -> Incidence.of(40_000_000 + i)).toList());
            txn.load(high, LongStream.range(0, members).mapToObj(i -> Incidence.of(40_000_000 + members + i)).toList());
            return new long[]{low, high};
        });
        try (Snapshot snapshot = engine.snapshot()) {
            Hyperedge a = snapshot.requireEdge(edges[0]);
            Hyperedge b = snapshot.requireEdge(edges[1]);
            IoTrace trace = IoTrace.unbounded();
            trace.run(() -> TreeAlgebra.countIntersect(a.membership(), b.membership(), TreeAlgebra.Strategy.SYNCHRONIZED));
            return measure(engine, "disjoint", 10_000, () -> {
                long started = System.nanoTime();
                TreeAlgebra.countIntersect(a.membership(), b.membership());
                return System.nanoTime() - started;
            }, () -> 0, "range pruning visited " + trace.pageVisits() + " pages");
        }
    }

    private Measurement reverseHotspot(StorageEngine engine) {
        int edges = 100_000 * scale;
        long hub = 99_000_000;
        engine.write(txn -> {
            for (int i = 0; i < edges; i++) {
                long edge = txn.createEdge(EDGE, EdgeKind.SET);
                txn.insert(edge, hub);
                txn.insert(edge, 50_000_000 + i);
            }
            return null;
        });
        try (Snapshot snapshot = engine.snapshot()) {
            Keyset[] cursor = {Keyset.ascending()};
            return measure(engine, "reverse-hotspot", 2_000, () -> {
                long started = System.nanoTime();
                var page = snapshot.incidentTree(hub).slice(cursor[0], 100);
                cursor[0] = page.next().orElse(Keyset.ascending());
                return System.nanoTime() - started;
            }, () -> 0, "degree " + snapshot.degree(hub) + ", keyset pages of 100");
        }
    }

    private Measurement smallConsolidation(StorageEngine engine) {
        int members = 500_000 * scale;
        long edge = engine.write(txn -> {
            long created = txn.createEdge(EDGE, EdgeKind.SET);
            txn.load(created, LongStream.range(0, members).mapToObj(i -> Incidence.of(60_000_000 + i * 2)).toList());
            return created;
        });
        return measure(engine, "small-consolidation", 1_000, () -> {
            long started = System.nanoTime();
            engine.write(txn -> {
                txn.upsert(edge, 60_000_000 + random.nextLong(members) * 2, incidence -> incidence.withWeight(random.nextDouble()));
                return null;
            });
            return System.nanoTime() - started;
        }, () -> 1_000, "one-member CoW edits on a " + members + "-member edge");
    }

    private Measurement mvccStress(StorageEngine engine) {
        long edge = engine.write(txn -> {
            long created = txn.createEdge(EDGE, EdgeKind.SET);
            txn.load(created, LongStream.range(0, 100_000L * scale).mapToObj(i -> Incidence.of(70_000_000 + i)).toList());
            return created;
        });
        try (Snapshot pinned = engine.snapshot()) {
            long before = pinned.cardinality(edge);
            Measurement measurement = measure(engine, "mvcc-stress", 200, () -> {
                long started = System.nanoTime();
                engine.write(txn -> {
                    for (int i = 0; i < 100; i++) {
                        txn.remove(edge, 70_000_000 + random.nextLong(100_000L * scale));
                    }
                    return null;
                });
                return System.nanoTime() - started;
            }, () -> 20_000, "");
            long retained = engine.liveness().values().stream().mapToLong(Long::longValue).sum();
            if (pinned.cardinality(edge) != before) {
                throw new IllegalStateException("long-lived snapshot observed later commits");
            }
            return new Measurement(measurement.scenario(), measurement.operations(), measurement.p50(), measurement.p95(), measurement.p99(),
                    measurement.pagesRead(), measurement.pagesWritten(), measurement.walBytes(), measurement.writeAmplification(),
                    "pinned snapshot stable; " + retained + " live pages retained across history");
        }
    }

    private Measurement measure(StorageEngine engine, String name, int operations, LongSupplier operation, LongSupplier logicalIncidences, String note) {
        EngineStats before = engine.stats();
        long[] samples = LongStream.generate(operation).limit(operations).toArray();
        EngineStats after = engine.stats();
        Arrays.sort(samples);
        long written = (after.dataBytesWritten() - before.dataBytesWritten()) + (after.walBytes() - before.walBytes());
        long logical = logicalIncidences.getAsLong() * LOGICAL_INCIDENCE_BYTES;
        return new Measurement(name, operations, percentile(samples, 0.50), percentile(samples, 0.95), percentile(samples, 0.99),
                after.pagesRead() - before.pagesRead(), after.pagesWritten() - before.pagesWritten(), after.walBytes() - before.walBytes(),
                logical == 0 ? 0 : (double) written / logical, note);
    }

    private static double percentile(long[] sorted, double quantile) {
        return sorted.length == 0 ? 0 : sorted[(int) Math.min(sorted.length - 1, Math.floor(quantile * sorted.length))] / 1_000.0;
    }

    private static void delete(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
