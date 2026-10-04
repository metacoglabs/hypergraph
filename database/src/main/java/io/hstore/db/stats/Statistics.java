package io.hstore.db.stats;

import io.hstore.engine.StorageEngine;
import io.hstore.engine.catalog.AtomRecord.EdgeRecord;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.feed.ChangeFeed;
import io.hstore.engine.index.IndexValues.Incident;
import io.hstore.engine.index.Postings;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeAlgebra;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.View;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public final class Statistics implements AutoCloseable {

    public record Catalog(long generation, long atoms, long edges, boolean sampled, Histogram cardinality, Histogram degree,
                          Map<Long, Long> edgesByCardinality, Map<Integer, Long> typeCounts) {

        public long countWithCardinality(long k) {
            return edgesByCardinality.getOrDefault(k, 0L);
        }

        public double averageDegree() {
            return degree.mean();
        }
    }

    private record Feedback(double ratio, long observations) {
        Feedback absorb(double sample) {
            double weight = observations < 8 ? 1.0 / (observations + 1) : 0.2;
            return new Feedback(ratio * (1 - weight) + sample * weight, observations + 1);
        }
    }

    private static final int SAMPLE_LIMIT = 200_000;
    private static final long REFRESH_AFTER_CHANGES = 10_000;

    private final StorageEngine engine;
    private final Map<String, Feedback> feedback = new ConcurrentHashMap<>();
    private final AtomicLong changesSinceRefresh = new AtomicLong();
    private final ChangeFeed.Subscription subscription;
    private volatile Catalog current;

    public Statistics(StorageEngine engine) {
        this.engine = engine;
        this.subscription = engine.feed().subscribe(engine.transactions().current().id(), event -> {
            if (changesSinceRefresh.addAndGet(event.members().size() + event.slots().size()) > REFRESH_AFTER_CHANGES) {
                refresh();
            }
        }, (_, _) -> refresh());
    }

    public Catalog catalog() {
        Catalog snapshot = current;
        return snapshot != null ? snapshot : refresh();
    }

    public Catalog refresh() {
        try (Snapshot snapshot = engine.snapshot()) {
            Tree<AtomRecord> atoms = snapshot.scan(EngineSlots.CATALOG);
            Tree<Postings<Incident>> reverse = snapshot.scan(EngineSlots.REVERSE);
            long total = atoms.size();
            boolean sampled = total > SAMPLE_LIMIT;
            long stride = sampled ? (total + SAMPLE_LIMIT - 1) / SAMPLE_LIMIT : 1;
            long[] cardinalities = atoms.stream()
                    .filter(entry -> entry.value() instanceof EdgeRecord)
                    .filter(entry -> entry.key() % stride == 0)
                    .mapToLong(entry -> ((EdgeRecord) entry.value()).cardinality())
                    .toArray();
            long[] degrees = atoms.keys()
                    .filter(atom -> atom % stride == 0)
                    .map(atom -> EngineSlots.REVERSE_INDEX.count(reverse, atom))
                    .toArray();
            Map<Long, Long> byCardinality = Arrays.stream(cardinalities).boxed()
                    .collect(Collectors.groupingBy(k -> k, TreeMap::new, Collectors.summingLong(_ -> stride)));
            Map<Integer, Long> types = snapshot.scan(EngineSlots.TYPES).stream()
                    .collect(Collectors.toMap(entry -> (int) entry.key(), entry -> entry.value().size(), Long::sum, TreeMap::new));
            long edges = cardinalities.length * stride;
            Catalog refreshed = new Catalog(snapshot.generation(), total, edges, sampled, Histogram.of(cardinalities),
                    Histogram.of(degrees), byCardinality, types);
            current = refreshed;
            changesSinceRefresh.set(0);
            return refreshed;
        }
    }

    public static long degree(View view, long atom) {
        return view.degree(atom);
    }

    public static long cardinality(View view, long edge) {
        return view.cardinality(edge);
    }

    public static double weight(View view, long edge) {
        return view.edge(edge).map(found -> found.summary().weightSum() / 1e9).orElse(0.0);
    }

    public static long overlap(View view, long left, long right) {
        return TreeAlgebra.countIntersect(view.requireEdge(left).membership(), view.requireEdge(right).membership());
    }

    public void observe(String accessPath, double estimated, long actual) {
        double sample = (actual + 1.0) / (Math.max(estimated, 0) + 1.0);
        feedback.merge(accessPath, new Feedback(sample, 1), (existing, _) -> existing.absorb(sample));
    }

    public double correction(String accessPath) {
        Feedback recorded = feedback.get(accessPath);
        return recorded == null ? 1.0 : recorded.ratio();
    }

    public Map<String, Double> corrections() {
        return feedback.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().ratio(), (a, _) -> a, TreeMap::new));
    }

    @Override
    public void close() {
        subscription.close();
    }
}
