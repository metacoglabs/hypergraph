package io.hstore.db.hora;

import io.hstore.db.Member;
import io.hstore.db.Reader;
import io.hstore.db.temporal.StateBindings;
import io.hstore.db.value.Value;
import io.hstore.engine.HStoreException;
import io.hstore.engine.topology.Hyperedge;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.Validity;
import io.hstore.engine.tree.Summary;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeAlgebra;
import io.hstore.engine.txn.IncidentEdge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class Hora {

    public record IncidenceTuple(long edge, long atom, List<String> roles, double weight, Validity validity, long position) {
    }

    public record Applied<R>(long edge, R result) {
    }

    public record OverlapPair(long left, long right, long overlap) {
    }

    public record Expansion(List<Long> path, long atom, Status status) {
        public enum Status { EMIT, CYCLE, BUDGET_EXHAUSTED }
    }

    private final Reader reader;

    private Hora(Reader reader) {
        this.reader = reader;
    }

    public static Hora on(Reader reader) {
        return new Hora(reader);
    }

    public Reader reader() {
        return reader;
    }

    public Stream<IncidenceTuple> incidence(long atom) {
        if (reader.require(atom).isEdge()) {
            return reader.members(atom).map(member -> tuple(atom, member));
        }
        return reader.view().incident(atom).flatMap(incident -> reader.view().incidence(incident.edge(), atom).stream()
                .map(found -> new IncidenceTuple(incident.edge(), atom, reader.roles(found.roleSet()), found.weightValue(),
                        found.validity(), reader.edge(incident.edge()).positionOfLocator(incident.locator()))));
    }

    private static IncidenceTuple tuple(long edge, Member member) {
        return new IncidenceTuple(edge, member.atom(), member.roles(), member.weight(), member.validity(), member.position());
    }

    public Stream<Gathered> gather(long edge, Field field) {
        return reader.edge(edge).stream().map(incidence -> new Gathered(edge, incidence.member(), read(incidence, field),
                incidence.weightValue(), reader.roles(incidence.roleSet())));
    }

    private Value read(Incidence incidence, Field field) {
        return switch (field) {
            case Field.Property(String name) -> reader.property(incidence.member(), name).orElse(Value.NULL);
            case Field.State _ -> StateBindings.state(reader, incidence.member()).map(StateBindings.Binding::value).orElse(Value.NULL);
            case Field.Weight _ -> new Value.Real(incidence.weightValue());
            case Field.Degree _ -> new Value.Int(reader.degree(incidence.member()));
        };
    }

    public OptionalDouble reduce(long edge, Field field, Reducer reducer, boolean weighted) {
        if (field instanceof Field.Weight && !weighted) {
            Summary summary = reader.edge(edge).summary();
            if (summary.isEmpty()) {
                return reducer == Reducer.SUM || reducer == Reducer.COUNT ? OptionalDouble.of(0) : OptionalDouble.empty();
            }
            return switch (reducer) {
                case SUM -> OptionalDouble.of(summary.weightSum() / 1e9);
                case MIN -> OptionalDouble.of(summary.weightMin() / 1e9);
                case MAX -> OptionalDouble.of(summary.weightMax() / 1e9);
                case COUNT -> OptionalDouble.of(summary.count());
                case MEAN -> OptionalDouble.of(summary.weightSum() / 1e9 / summary.count());
            };
        }
        return reducer.reduce(gather(edge, field)
                .filter(gathered -> !(gathered.value() instanceof Value.Null))
                .mapToDouble(gathered -> weighted ? gathered.numeric() * gathered.weight() : gathered.numeric()));
    }

    public <R> Stream<Applied<R>> apply(LongStream edges, Field field, GroupKernel<R> kernel, Budget budget) {
        Budget.Meter meter = budget.start();
        return edges.mapToObj(edge -> {
            long cardinality = reader.cardinality(edge);
            R result = switch (kernel) {
                case GroupKernel.Streaming<R>(var function) -> function.apply(gather(edge, field).peek(_ -> meter.consume(1)));
                case GroupKernel.TwoPass<?, R> twoPass -> twoPass(edge, field, twoPass, meter);
                case GroupKernel.Materialize<R>(var function) -> {
                    if (cardinality > budget.maxRows()) {
                        throw HStoreException.limit("MATERIALIZE kernel over hyperedge " + edge + " with " + cardinality
                                + " members exceeds the budget of " + budget.maxRows());
                    }
                    meter.consume(cardinality);
                    yield function.apply(gather(edge, field).toList());
                }
            };
            return new Applied<>(edge, result);
        });
    }

    private <S, R> R twoPass(long edge, Field field, GroupKernel.TwoPass<S, R> kernel, Budget.Meter meter) {
        S summary = kernel.summarize().apply(gather(edge, field).peek(_ -> meter.consume(1)));
        return kernel.finish().apply(summary, gather(edge, field).peek(_ -> meter.consume(1)));
    }

    public Map<Long, Double> edgeValues(LongStream edges, ToDoubleFunction<Long> nodeValue, boolean weighted) {
        Map<Long, Double> result = new TreeMap<>();
        edges.forEach(edge -> result.put(edge, reader.edge(edge).stream()
                .mapToDouble(incidence -> nodeValue.applyAsDouble(incidence.member()) * (weighted ? incidence.weightValue() : 1))
                .sum()));
        return result;
    }

    public Map<Long, Double> nodeValues(LongStream edges, ToDoubleFunction<Long> edgeValue, boolean weighted) {
        Map<Long, Double> result = new TreeMap<>();
        edges.forEach(edge -> {
            double value = edgeValue.applyAsDouble(edge);
            reader.edge(edge).stream().forEach(incidence ->
                    result.merge(incidence.member(), value * (weighted ? incidence.weightValue() : 1), Double::sum));
        });
        return result;
    }

    public Map<Long, Double> propagate(List<Long> edges, ToDoubleFunction<Long> x, ToDoubleFunction<Long> edgeWeight) {
        Map<Long, Double> gathered = edgeValues(edges.stream().mapToLong(Long::longValue), x, true);
        Map<Long, Double> weighted = new HashMap<>();
        gathered.forEach((edge, value) -> weighted.put(edge, value * edgeWeight.applyAsDouble(edge)));
        return nodeValues(edges.stream().mapToLong(Long::longValue), weighted::get, true);
    }

    public long overlap(long left, long right) {
        return TreeAlgebra.countIntersect(reader.edge(left).membership(), reader.edge(right).membership());
    }

    public double weightedOverlap(long left, long right) {
        Hyperedge a = reader.edge(left);
        Hyperedge b = reader.edge(right);
        return TreeAlgebra.intersectKeys(a.membership(), b.membership())
                .mapToDouble(member -> Math.min(a.get(member).orElseThrow().weightValue(), b.get(member).orElseThrow().weightValue()))
                .sum();
    }

    public double jaccard(long left, long right) {
        return TreeAlgebra.jaccard(reader.edge(left).membership(), reader.edge(right).membership());
    }

    public boolean adjacent(long u, long v, int threshold) {
        reader.require(u);
        reader.require(v);
        Tree<?> left = reader.view().incidentTree(u);
        Tree<?> right = reader.view().incidentTree(v);
        return TreeAlgebra.intersectKeys(left, right).limit(threshold).count() >= threshold;
    }

    public LongStream neighbors(long node, int threshold, Budget budget) {
        reader.require(node);
        Budget.Meter meter = budget.start();
        Map<Long, Integer> counts = new HashMap<>();
        reader.view().incident(node).map(IncidentEdge::edge).forEach(edge -> reader.edge(edge).stream()
                .mapToLong(Incidence::member)
                .filter(member -> member != node)
                .forEach(member -> {
                    meter.consume(1);
                    counts.merge(member, 1, Integer::sum);
                }));
        return counts.entrySet().stream().filter(entry -> entry.getValue() >= threshold).mapToLong(Map.Entry::getKey).sorted();
    }

    public LongStream edgeNeighbors(long edge, int threshold, Budget budget) {
        Budget.Meter meter = budget.start();
        Tree<?> membership = reader.edge(edge).membership();
        Set<Long> candidates = new HashSet<>();
        reader.edge(edge).stream().forEach(incidence -> reader.view().incident(incidence.member())
                .mapToLong(IncidentEdge::edge)
                .filter(other -> other != edge)
                .forEach(other -> {
                    meter.consume(1);
                    candidates.add(other);
                }));
        return candidates.stream()
                .filter(other -> TreeAlgebra.intersectKeys(membership, reader.edge(other).membership()).limit(threshold).count() >= threshold)
                .mapToLong(Long::longValue)
                .sorted();
    }

    public Stream<OverlapPair> overlapJoin(List<Long> edges, long threshold, Budget budget) {
        Budget.Meter meter = budget.start();
        Set<Long> scope = new HashSet<>(edges);
        List<OverlapPair> pairs = new ArrayList<>();
        for (long left : edges) {
            Hyperedge a = reader.edge(left);
            if (a.size() < threshold) {
                continue;
            }
            Set<Long> partners = new HashSet<>();
            a.stream().forEach(incidence -> reader.view().incident(incidence.member())
                    .mapToLong(IncidentEdge::edge)
                    .filter(right -> right > left && scope.contains(right))
                    .forEach(partners::add));
            for (long right : partners) {
                meter.consume(1);
                Hyperedge b = reader.edge(right);
                if (Math.min(a.size(), b.size()) < threshold || a.summary().disjointFrom(b.summary())) {
                    continue;
                }
                long shared = TreeAlgebra.intersectKeys(a.membership(), b.membership()).limit(threshold).count();
                if (shared >= threshold) {
                    pairs.add(new OverlapPair(left, right, TreeAlgebra.countIntersect(a.membership(), b.membership())));
                }
            }
        }
        return pairs.stream();
    }

    public Stream<long[]> closure(long edge, int dimension, Budget budget) {
        long[] members = reader.edge(edge).stream().mapToLong(Incidence::member).toArray();
        if (members.length > budget.maxRows()) {
            throw HStoreException.limit("closure over " + members.length + " members exceeds the budget");
        }
        Budget.Meter meter = budget.start();
        int largest = Math.min(dimension + 1, members.length);
        Iterator<long[]> subsets = new Iterator<>() {
            private int size = 1;
            private int[] indices = firstOf(1);

            private int[] firstOf(int k) {
                int[] first = new int[k];
                for (int i = 0; i < k; i++) {
                    first[i] = i;
                }
                return first;
            }

            @Override
            public boolean hasNext() {
                return indices != null && size <= largest;
            }

            @Override
            public long[] next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                meter.consume(1);
                long[] subset = new long[size];
                for (int i = 0; i < size; i++) {
                    subset[i] = members[indices[i]];
                }
                advance();
                return subset;
            }

            private void advance() {
                int i = size - 1;
                while (i >= 0 && indices[i] == members.length - size + i) {
                    i--;
                }
                if (i < 0) {
                    size++;
                    indices = size <= largest ? firstOf(size) : null;
                    return;
                }
                indices[i]++;
                for (int j = i + 1; j < size; j++) {
                    indices[j] = indices[j - 1] + 1;
                }
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(subsets, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    public Stream<Expansion> expand(long atom, int depth, Budget budget) {
        reader.require(atom);
        Budget.Meter meter = budget.start();
        List<Expansion> out = new ArrayList<>();
        expand(atom, Math.min(depth, budget.maxDepth()), new HashSet<>(), new ArrayList<>(List.of(atom)), meter, out);
        return out.stream();
    }

    private void expand(long atom, int depth, Set<Long> visited, List<Long> path, Budget.Meter meter, List<Expansion> out) {
        if (depth == 0 || !meter.tryConsume(1)) {
            out.add(new Expansion(List.copyOf(path), atom, Expansion.Status.BUDGET_EXHAUSTED));
            return;
        }
        if (!visited.add(atom)) {
            out.add(new Expansion(List.copyOf(path), atom, Expansion.Status.CYCLE));
            return;
        }
        for (Incidence member : reader.edge(atom).stream().toList()) {
            path.add(member.member());
            boolean nested = reader.view().edge(member.member()).isPresent();
            if (nested) {
                expand(member.member(), depth - 1, visited, path, meter, out);
            } else {
                out.add(new Expansion(List.copyOf(path), member.member(), Expansion.Status.EMIT));
            }
            path.removeLast();
        }
    }
}
