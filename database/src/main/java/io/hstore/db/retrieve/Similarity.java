// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.Reader;
import io.hstore.db.hora.Budget;
import io.hstore.db.retrieve.EditDistance.Shape;
import io.hstore.engine.HStoreException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

public final class Similarity {

    public static final int MAX_AROUND = 6;

    public sealed interface Space {
        record Children(int containerType) implements Space {
        }

        record Around(long anchor, int maxEdges, Set<Integer> edgeTypes) implements Space {
            public Around {
                if (maxEdges < 1 || maxEdges > MAX_AROUND) {
                    throw HStoreException.invalid("SIZE must be between 1 and " + MAX_AROUND);
                }
                edgeTypes = Set.copyOf(edgeTypes);
            }
        }

        record Given(List<long[]> edgeSets) implements Space {
            public Given {
                edgeSets = edgeSets.stream().map(long[]::clone).toList();
            }
        }
    }

    public record Hit(long key, long[] edges, double lower, double upper) {
        public boolean exact() {
            return lower == upper;
        }

        long smallest() {
            return edges.length == 0 ? key : Math.min(key, Arrays.stream(edges).min().orElse(key));
        }
    }

    public record Result(List<Hit> hits, boolean exact, boolean complete) {
        public Result {
            hits = List.copyOf(hits);
        }
    }

    private record Candidate(long key, long[] edges) {
    }

    private static final Comparator<Hit> ORDER = Comparator.comparingDouble(Hit::upper).thenComparingDouble(Hit::lower)
            .thenComparingLong(Hit::smallest).thenComparing(Hit::edges, Arrays::compare).thenComparingLong(Hit::key);
    private static final Comparator<Hit> BY_LOWER = Comparator.comparingDouble(Hit::lower).thenComparingLong(Hit::smallest)
            .thenComparing(Hit::edges, Arrays::compare).thenComparingLong(Hit::key);
    private static final int EDGE_CACHE = 1_000_000;

    private record Read(int type, long[] members) {
    }

    private record Bounded(Hit hit, Shape shape) {
    }

    private final Reader reader;
    private final Shape query;
    private final boolean identity;
    private final Budget.Meter generation;
    private final Budget.Meter verification;
    private final Map<Long, Read> reads = new HashMap<>();
    private boolean complete = true;

    private Similarity(Reader reader, Shape query, boolean identity, Budget budget) {
        this.reader = reader;
        this.query = query;
        this.identity = identity;
        Budget half = new Budget(Math.max(1, budget.maxRows() / 2), budget.maxDepth(), budget.maxTime().dividedBy(2));
        this.generation = half.start();
        this.verification = half.start();
    }

    public static Shape shapeOf(Reader reader, long[] edges) {
        return new Similarity(reader, new Shape(new int[0], new int[0], new int[0][], new long[0]), false, Budget.DEFAULT.withRows(2 * Budget.DEFAULT.maxRows()))
                .shape(edges);
    }

    private Read read(long edge) {
        Read known = reads.get(edge);
        if (known != null) {
            return known;
        }
        int type = reader.typeOf(edge).orElseThrow(() -> HStoreException.invalid("atom " + edge + " does not exist"));
        long[] members = reader.memberIds(edge).filter(reader::owns).distinct().sorted().toArray();
        generation.consume(1 + members.length);
        Read found = new Read(type, members);
        if (reads.size() < EDGE_CACHE) {
            reads.put(edge, found);
        }
        return found;
    }

    private Shape shape(long[] edges) {
        long[] sorted = Arrays.stream(edges).distinct().sorted().toArray();
        Map<Long, Integer> nodes = new LinkedHashMap<>();
        List<Integer> nodeTypes = new ArrayList<>();
        int[] edgeTypes = new int[sorted.length];
        int[][] members = new int[sorted.length][];
        for (int e = 0; e < sorted.length; e++) {
            Read edge = read(sorted[e]);
            edgeTypes[e] = edge.type();
            members[e] = Arrays.stream(edge.members()).mapToInt(member -> nodes.computeIfAbsent(member, id -> {
                nodeTypes.add(reader.typeOf(id).orElse(-1));
                return nodeTypes.size() - 1;
            })).toArray();
        }
        return new Shape(nodeTypes.stream().mapToInt(Integer::intValue).toArray(), edgeTypes, members,
                nodes.keySet().stream().mapToLong(Long::longValue).toArray());
    }

    public static long[] children(Reader reader, long container) {
        reader.require(container);
        if (!reader.isEdge(container)) {
            throw HStoreException.invalid("atom " + container + " is not a hyperedge, so it has no child hyperedges");
        }
        return reader.memberIds(container).filter(member -> reader.owns(member) && reader.isEdge(member)).distinct().sorted().toArray();
    }

    public static Result within(Reader reader, Shape query, boolean identity, Space space, double threshold, Budget budget) {
        if (!(threshold >= 0) || Double.isInfinite(threshold)) {
            throw HStoreException.invalid("WITHIN needs a distance of at least 0");
        }
        return new Similarity(reader, query, identity, budget).within(space, threshold);
    }

    public static Result top(Reader reader, Shape query, boolean identity, Space space, int k, Budget budget) {
        if (k < 1) {
            throw HStoreException.invalid("TOP must be at least 1");
        }
        return new Similarity(reader, query, identity, budget).top(space, k);
    }

    private List<Bounded> bounded(Space space, OptionalDouble threshold) {
        List<Bounded> bounded = new ArrayList<>();
        candidates(space, threshold, candidate -> {
            generation.consume(1);
            Shape shape = shape(candidate.edges());
            double lower = identity ? EditDistance.identity(query, shape, generation).lower() : EditDistance.lower(query, shape);
            if (threshold.isEmpty() || lower <= threshold.getAsDouble()) {
                bounded.add(new Bounded(new Hit(candidate.key(), candidate.edges(), lower, Double.POSITIVE_INFINITY), shape));
            }
        });
        return bounded;
    }

    private EditDistance.Interval verify(Bounded entry, double cutoff) {
        if (identity) {
            return EditDistance.identity(query, entry.shape(), verification);
        }
        return EditDistance.measure(query, entry.shape(), entry.hit().lower(), cutoff, verification);
    }

    private Result within(Space space, double threshold) {
        List<Hit> hits = new ArrayList<>();
        try {
            for (Bounded entry : bounded(space, OptionalDouble.of(threshold))) {
                EditDistance.Interval interval = verify(entry, threshold);
                if (interval.lower() <= threshold) {
                    hits.add(new Hit(entry.hit().key(), entry.hit().edges(), interval.lower(), interval.upper()));
                }
            }
        } catch (HStoreException.ResourceLimit exhausted) {
            complete = false;
        }
        hits.sort(ORDER);
        return new Result(hits, complete && hits.stream().allMatch(hit -> hit.exact() || hit.upper() <= threshold), complete);
    }

    private Result top(Space space, int k) {
        List<Bounded> bounded = bounded(space, OptionalDouble.empty());
        bounded.sort(Comparator.comparing(Bounded::hit, BY_LOWER));
        List<Hit> best = new ArrayList<>();
        List<Hit> uncertain = new ArrayList<>();
        try {
            for (Bounded entry : bounded) {
                double kth = best.size() < k ? Double.POSITIVE_INFINITY : best.get(k - 1).upper();
                if (entry.hit().lower() > kth) {
                    break;
                }
                EditDistance.Interval interval = verify(entry, kth);
                Hit hit = new Hit(entry.hit().key(), entry.hit().edges(), interval.lower(), interval.upper());
                if (!hit.exact()) {
                    uncertain.add(hit);
                }
                if (interval.lower() > kth) {
                    continue;
                }
                best.add(hit);
                best.sort(ORDER);
                if (best.size() > k) {
                    best.removeLast();
                }
            }
        } catch (HStoreException.ResourceLimit exhausted) {
            complete = false;
        }
        double kth = best.size() < k ? Double.POSITIVE_INFINITY : best.get(k - 1).upper();
        boolean exact = complete && best.stream().allMatch(Hit::exact)
                && uncertain.stream().filter(hit -> !best.contains(hit)).noneMatch(hit -> hit.lower() <= kth);
        return new Result(best, exact, complete);
    }

    private void candidates(Space space, OptionalDouble threshold, java.util.function.Consumer<Candidate> found) {
        if (space instanceof Space.Given(List<long[]> sets)) {
            for (long[] set : sets) {
                if (set.length == 0) {
                    throw HStoreException.invalid("a listed set needs at least one hyperedge");
                }
                for (long edge : set) {
                    reader.require(edge);
                    if (!reader.isEdge(edge)) {
                        throw HStoreException.invalid("atom " + edge + " is not a hyperedge");
                    }
                }
            }
        }
        try {
            switch (space) {
                case Space.Children(int containerType) -> reader.atomsOfType(containerType).forEach(container -> {
                    generation.consume(1 + reader.cardinality(container));
                    found.accept(new Candidate(container, children(reader, container)));
                });
                case Space.Given(List<long[]> sets) -> {
                    Set<List<Long>> seen = new HashSet<>();
                    for (long[] set : sets) {
                        long[] sorted = Arrays.stream(set).distinct().sorted().toArray();
                        if (seen.add(Arrays.stream(sorted).boxed().toList())) {
                            found.accept(new Candidate(sorted[0], sorted));
                        }
                    }
                }
                case Space.Around around -> around(around, threshold, found);
            }
        } catch (HStoreException.ResourceLimit exhausted) {
            complete = false;
        }
    }

    private boolean admitted(Space.Around around, long edge) {
        if (!reader.isEdge(edge)) {
            return false;
        }
        OptionalInt type = reader.typeOf(edge);
        return type.isPresent() && (around.edgeTypes().isEmpty() || around.edgeTypes().contains(type.getAsInt()));
    }

    private void around(Space.Around around, OptionalDouble threshold, java.util.function.Consumer<Candidate> found) {
        long anchor = around.anchor();
        reader.require(anchor);
        if (!admitted(around, anchor)) {
            throw HStoreException.invalid("atom " + anchor + " is not a hyperedge of the searched types");
        }
        Map<Integer, Integer> queryTypes = new LinkedHashMap<>();
        for (int type : query.edgeTypes()) {
            queryTypes.merge(type, 1, Integer::sum);
        }
        Set<List<Long>> seen = new HashSet<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        queue.add(new long[]{anchor});
        seen.add(List.of(anchor));
        while (!queue.isEmpty()) {
            long[] set = queue.poll();
            generation.consume(1);
            if (threshold.isPresent() && prunable(set, queryTypes, threshold.getAsDouble())) {
                continue;
            }
            found.accept(new Candidate(anchor, set));
            if (set.length >= around.maxEdges()) {
                continue;
            }
            TreeSet<Long> next = new TreeSet<>();
            for (long edge : set) {
                for (long member : read(edge).members()) {
                    reader.forEachIncidentEdge(member, other -> {
                        generation.consume(1);
                        if (Arrays.binarySearch(set, other) < 0 && admitted(around, other)) {
                            next.add(other);
                        }
                    });
                }
            }
            for (long other : next) {
                long[] grown = Arrays.copyOf(set, set.length + 1);
                grown[set.length] = other;
                Arrays.sort(grown);
                if (seen.add(Arrays.stream(grown).boxed().toList())) {
                    queue.add(grown);
                }
            }
        }
    }

    private boolean prunable(long[] set, Map<Integer, Integer> queryTypes, double threshold) {
        if (set.length > query.edges() + threshold) {
            return true;
        }
        Map<Integer, Integer> left = new LinkedHashMap<>(queryTypes);
        int shared = 0;
        long incidences = 0;
        for (long edge : set) {
            Read read = read(edge);
            Integer count = left.get(read.type());
            if (count != null && count > 0) {
                shared++;
                left.put(read.type(), count - 1);
            }
            incidences += read.members().length;
        }
        return set.length - shared > threshold || incidences - query.incidences() > threshold;
    }
}
