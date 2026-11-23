// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.Reader;
import io.hstore.db.hora.Budget;
import io.hstore.db.retrieve.EditDistance.Interval;
import io.hstore.db.retrieve.EditDistance.Shape;
import io.hstore.engine.HStoreException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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

    private record Bounded(Hit hit, Shape shape) {
    }

    private static final Comparator<Hit> ORDER = Comparator.comparingDouble(Hit::upper).thenComparingDouble(Hit::lower)
            .thenComparingLong(Hit::smallest).thenComparing(Hit::edges, Arrays::compare);

    private final Reader reader;
    private final Shape query;
    private final boolean identity;
    private final Budget.Meter meter;

    private Similarity(Reader reader, Shape query, boolean identity, Budget.Meter meter) {
        this.reader = reader;
        this.query = query;
        this.identity = identity;
        this.meter = meter;
    }

    public static Shape shapeOf(Reader reader, long[] edges) {
        long[] sorted = Arrays.stream(edges).distinct().sorted().toArray();
        Map<Long, Integer> nodes = new LinkedHashMap<>();
        List<Integer> nodeTypes = new ArrayList<>();
        int[] edgeTypes = new int[sorted.length];
        int[][] members = new int[sorted.length][];
        for (int e = 0; e < sorted.length; e++) {
            long edge = sorted[e];
            edgeTypes[e] = reader.typeOf(edge).orElseThrow(() -> HStoreException.invalid("atom " + edge + " does not exist"));
            members[e] = reader.memberIds(edge).filter(reader::owns).sorted().mapToInt(member -> nodes.computeIfAbsent(member, id -> {
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
        return reader.memberIds(container).filter(member -> reader.owns(member) && reader.isEdge(member)).sorted().toArray();
    }

    public static Result within(Reader reader, Shape query, boolean identity, Space space, double threshold, Budget.Meter meter) {
        if (!(threshold >= 0) || Double.isInfinite(threshold)) {
            throw HStoreException.invalid("WITHIN needs a distance of at least 0");
        }
        return new Similarity(reader, query, identity, meter).within(space, threshold);
    }

    public static Result top(Reader reader, Shape query, boolean identity, Space space, int k, Budget.Meter meter) {
        if (k < 1) {
            throw HStoreException.invalid("TOP must be at least 1");
        }
        return new Similarity(reader, query, identity, meter).top(space, k);
    }

    private Result within(Space space, double threshold) {
        List<Hit> hits = new ArrayList<>();
        boolean complete = true;
        try {
            for (Candidate candidate : candidates(space, OptionalDouble.of(threshold))) {
                meter.consume(1);
                Shape shape = shapeOf(reader, candidate.edges());
                if (identity) {
                    double distance = EditDistance.identity(query, shape);
                    if (distance <= threshold) {
                        hits.add(new Hit(candidate.key(), candidate.edges(), distance, distance));
                    }
                    continue;
                }
                if (EditDistance.lower(query, shape) > threshold) {
                    continue;
                }
                Interval interval = EditDistance.measure(query, shape, threshold, meter);
                if (interval.lower() <= threshold) {
                    hits.add(new Hit(candidate.key(), candidate.edges(), interval.lower(), interval.upper()));
                }
            }
        } catch (HStoreException.ResourceLimit exhausted) {
            complete = false;
        }
        hits.sort(ORDER);
        return new Result(hits, hits.stream().allMatch(hit -> hit.exact() || hit.upper() <= threshold), complete);
    }

    private Result top(Space space, int k) {
        List<Bounded> bounded = new ArrayList<>();
        List<Hit> best = new ArrayList<>();
        boolean complete = true;
        boolean exact = true;
        try {
            for (Candidate candidate : candidates(space, OptionalDouble.empty())) {
                meter.consume(1);
                Shape shape = shapeOf(reader, candidate.edges());
                double lower = identity ? EditDistance.identity(query, shape) : EditDistance.lower(query, shape);
                bounded.add(new Bounded(new Hit(candidate.key(), candidate.edges(), lower, Double.POSITIVE_INFINITY), shape));
            }
            bounded.sort(Comparator.comparing(Bounded::hit, Comparator.comparingDouble(Hit::lower).thenComparingLong(Hit::smallest)
                    .thenComparing(Hit::edges, Arrays::compare)));
            for (Bounded entry : bounded) {
                Hit next = entry.hit();
                double kth = best.size() < k ? Double.POSITIVE_INFINITY : best.get(k - 1).upper();
                if (next.lower() > kth) {
                    break;
                }
                Shape shape = entry.shape();
                Hit hit;
                if (identity) {
                    hit = new Hit(next.key(), next.edges(), next.lower(), next.lower());
                } else {
                    Interval interval = EditDistance.measure(query, shape, kth, meter);
                    if (interval.lower() > kth) {
                        continue;
                    }
                    hit = new Hit(next.key(), next.edges(), interval.lower(), interval.upper());
                }
                best.add(hit);
                best.sort(ORDER);
                if (best.size() > k) {
                    best.removeLast();
                }
            }
            exact = best.stream().allMatch(Hit::exact);
        } catch (HStoreException.ResourceLimit exhausted) {
            complete = false;
            exact = false;
        }
        return new Result(best, exact, complete);
    }

    private List<Candidate> candidates(Space space, OptionalDouble threshold) {
        return switch (space) {
            case Space.Children(int containerType) -> {
                List<Candidate> found = new ArrayList<>();
                reader.atomsOfType(containerType).forEach(container -> {
                    meter.consume(1);
                    found.add(new Candidate(container, children(reader, container)));
                });
                yield found;
            }
            case Space.Given(List<long[]> sets) -> sets.stream().map(set -> {
                for (long edge : set) {
                    reader.require(edge);
                    if (!reader.isEdge(edge)) {
                        throw HStoreException.invalid("atom " + edge + " is not a hyperedge");
                    }
                }
                long[] sorted = Arrays.stream(set).distinct().sorted().toArray();
                return new Candidate(sorted.length == 0 ? 0 : sorted[0], sorted);
            }).toList();
            case Space.Around around -> around(around, threshold);
        };
    }

    private boolean admitted(Space.Around around, long edge) {
        if (!reader.isEdge(edge)) {
            return false;
        }
        OptionalInt type = reader.typeOf(edge);
        return type.isPresent() && (around.edgeTypes().isEmpty() || around.edgeTypes().contains(type.getAsInt()));
    }

    private List<Candidate> around(Space.Around around, OptionalDouble threshold) {
        long anchor = around.anchor();
        reader.require(anchor);
        if (!admitted(around, anchor)) {
            throw HStoreException.invalid("atom " + anchor + " is not a hyperedge of the searched types");
        }
        Map<Integer, Integer> queryTypes = new LinkedHashMap<>();
        for (int type : query.edgeTypes()) {
            queryTypes.merge(type, 1, Integer::sum);
        }
        List<Candidate> found = new ArrayList<>();
        Set<List<Long>> seen = new HashSet<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        queue.add(new long[]{anchor});
        seen.add(List.of(anchor));
        while (!queue.isEmpty()) {
            long[] set = queue.poll();
            meter.consume(1);
            if (threshold.isPresent() && prunable(set, queryTypes, threshold.getAsDouble())) {
                continue;
            }
            found.add(new Candidate(anchor, set));
            if (set.length >= around.maxEdges()) {
                continue;
            }
            TreeSet<Long> next = new TreeSet<>();
            for (long edge : set) {
                reader.memberIds(edge).filter(reader::owns).forEach(member -> reader.forEachIncidentEdge(member, other -> {
                    meter.consume(1);
                    if (Arrays.binarySearch(set, other) < 0 && admitted(around, other)) {
                        next.add(other);
                    }
                }));
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
        return found;
    }

    private boolean prunable(long[] set, Map<Integer, Integer> queryTypes, double threshold) {
        if (set.length > query.edges() + threshold) {
            return true;
        }
        Map<Integer, Integer> left = new LinkedHashMap<>(queryTypes);
        int shared = 0;
        long incidences = 0;
        for (long edge : set) {
            int type = reader.typeOf(edge).orElse(-1);
            Integer count = left.get(type);
            if (count != null && count > 0) {
                shared++;
                left.put(type, count - 1);
            }
            incidences += reader.memberIds(edge).filter(reader::owns).count();
        }
        return set.length - shared > threshold || incidences - query.incidences() > threshold;
    }
}
