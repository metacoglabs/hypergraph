// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.hora.Budget;
import io.hstore.engine.HStoreException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class Selection {

    public record Candidate(long edge, double relevance, double[] cover, long cost, long[] members, long parent) {
        public Candidate {
            if (!(relevance >= 0 && relevance <= 1)) {
                throw HStoreException.invalid("candidate relevance must lie in [0, 1]");
            }
            if (cost < 1) {
                throw HStoreException.invalid("candidate cost must be at least 1");
            }
            cover = cover.clone();
            members = Arrays.stream(members).distinct().sorted().toArray();
            for (double value : cover) {
                if (!(value >= 0 && value <= 1)) {
                    throw HStoreException.invalid("slot cover must lie in [0, 1]");
                }
            }
        }
    }

    public record Options(int maxEdges, long maxCost, double kappa, double lambda, double[] slotWeights, boolean connected) {
        public Options {
            if (maxEdges < 1 || maxCost < 1) {
                throw HStoreException.invalid("a selection needs room for at least one hyperedge");
            }
            if (!(kappa > 0) || !(lambda >= 0) || Double.isInfinite(kappa) || Double.isInfinite(lambda)) {
                throw HStoreException.invalid("KAPPA must be positive and LAMBDA at least 0");
            }
            slotWeights = slotWeights.clone();
            for (double weight : slotWeights) {
                if (!(weight >= 0) || Double.isInfinite(weight)) {
                    throw HStoreException.invalid("slot weights must be finite and at least 0");
                }
            }
        }
    }

    public record Selected(long edge, double gain, double relevance) {
    }

    public record Outcome(List<Selected> selected, double objective, double bound, boolean singleton, boolean exhausted) {
        public Outcome {
            selected = List.copyOf(selected);
        }
    }

    public static final long UNREACHABLE = -2;

    private Selection() {
    }

    public static double objective(Collection<Candidate> set, Options options) {
        State state = new State(options);
        set.forEach(state::add);
        return state.value();
    }

    private static final class State {
        private final Options options;
        private final Map<Long, Double> atoms = new HashMap<>();
        private final double[] uncovered;
        private double relevance;
        private long cost;
        private int count;

        State(Options options) {
            this.options = options;
            this.uncovered = new double[options.slotWeights().length];
            Arrays.fill(uncovered, 1.0);
        }

        State(State other) {
            this.options = other.options;
            this.atoms.putAll(other.atoms);
            this.uncovered = other.uncovered.clone();
            this.relevance = other.relevance;
            this.cost = other.cost;
            this.count = other.count;
        }

        double gain(Collection<Candidate> unit) {
            Map<Long, Double> added = new HashMap<>();
            double relevanceGain = 0;
            double[] missing = uncovered.clone();
            for (Candidate candidate : unit) {
                if (candidate.members().length > 0) {
                    double share = candidate.relevance() / candidate.members().length;
                    for (long member : candidate.members()) {
                        double before = atoms.getOrDefault(member, 0.0) + added.getOrDefault(member, 0.0);
                        relevanceGain += Math.min(options.kappa(), before + share) - Math.min(options.kappa(), before);
                        added.merge(member, share, Double::sum);
                    }
                }
                for (int i = 0; i < missing.length && i < candidate.cover().length; i++) {
                    missing[i] *= 1 - candidate.cover()[i];
                }
            }
            double coverageGain = 0;
            for (int i = 0; i < missing.length; i++) {
                coverageGain += options.slotWeights()[i] * (uncovered[i] - missing[i]);
            }
            return relevanceGain + options.lambda() * coverageGain;
        }

        void add(Candidate candidate) {
            count++;
            cost += candidate.cost();
            if (candidate.members().length > 0) {
                double share = candidate.relevance() / candidate.members().length;
                for (long member : candidate.members()) {
                    double before = atoms.getOrDefault(member, 0.0);
                    double after = before + share;
                    relevance += Math.min(options.kappa(), after) - Math.min(options.kappa(), before);
                    atoms.put(member, after);
                }
            }
            for (int i = 0; i < uncovered.length && i < candidate.cover().length; i++) {
                uncovered[i] *= 1 - candidate.cover()[i];
            }
        }

        double value() {
            double coverage = 0;
            for (int i = 0; i < uncovered.length; i++) {
                coverage += options.slotWeights()[i] * (1 - uncovered[i]);
            }
            return relevance + options.lambda() * coverage;
        }
    }

    private record Key(long edge, double density) {
    }

    public static Outcome select(List<Candidate> candidates, Options options, Budget.Meter meter) {
        Map<Long, Candidate> byEdge = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            byEdge.putIfAbsent(candidate.edge(), candidate);
        }
        boolean budgeted = options.maxCost() < Long.MAX_VALUE;
        Outcome best = greedy(byEdge, options, meter, budgeted);
        if (budgeted && options.maxEdges() < Integer.MAX_VALUE && !best.exhausted()) {
            Outcome byCount = greedy(byEdge, options, meter, false);
            if (byCount.objective() > best.objective() + 1e-12) {
                best = byCount;
            }
        }
        if (budgeted && !best.exhausted()) {
            List<Candidate> ordered = new ArrayList<>(byEdge.values());
            ordered.sort(Comparator.comparingLong(Candidate::edge));
            for (Candidate candidate : ordered) {
                if (!meter.tryConsume(1)) {
                    return new Outcome(best.selected(), best.objective(), best.bound(), best.singleton(), true);
                }
                List<Candidate> unit = unit(candidate, byEdge, Set.of(), options);
                if (unit == null || unit.size() > options.maxEdges() || cost(unit) > options.maxCost()) {
                    continue;
                }
                State alone = new State(options);
                double value = alone.gain(unit);
                if (value > best.objective() + 1e-12) {
                    List<Selected> only = new ArrayList<>();
                    for (Candidate member : unit) {
                        only.add(new Selected(member.edge(), alone.gain(List.of(member)), member.relevance()));
                        alone.add(member);
                    }
                    best = new Outcome(only, value, best.bound(), true, false);
                }
            }
        }
        return best;
    }

    private static Outcome greedy(Map<Long, Candidate> byEdge, Options options, Budget.Meter meter, boolean byCost) {
        Set<Long> selected = new LinkedHashSet<>();
        State state = new State(options);
        List<Selected> picked = new ArrayList<>();
        boolean exhausted = false;
        if (options.connected()) {
            rounds:
            while (state.count < options.maxEdges()) {
                List<Candidate> bestUnit = null;
                double bestDensity = 0;
                for (Candidate candidate : byEdge.values()) {
                    if (selected.contains(candidate.edge())) {
                        continue;
                    }
                    if (!meter.tryConsume(1)) {
                        exhausted = true;
                        break rounds;
                    }
                    List<Candidate> unit = unit(candidate, byEdge, selected, options);
                    if (unit == null || state.count + unit.size() > options.maxEdges() || state.cost + cost(unit) > options.maxCost()) {
                        continue;
                    }
                    double density = density(state.gain(unit), cost(unit), unit.size(), options, byCost);
                    if (density > bestDensity) {
                        bestUnit = unit;
                        bestDensity = density;
                    }
                }
                if (bestUnit == null) {
                    break;
                }
                accept(bestUnit, state, selected, picked);
            }
        } else {
            PriorityQueue<Key> heap = new PriorityQueue<>(Comparator.comparingDouble(Key::density).reversed().thenComparingLong(Key::edge));
            for (Candidate candidate : byEdge.values()) {
                if (!meter.tryConsume(1)) {
                    exhausted = true;
                    break;
                }
                heap.add(new Key(candidate.edge(), density(state.gain(List.of(candidate)), candidate.cost(), 1, options, byCost)));
            }
            while (!exhausted && !heap.isEmpty() && state.count < options.maxEdges()) {
                if (!meter.tryConsume(1)) {
                    exhausted = true;
                    break;
                }
                Key top = heap.poll();
                Candidate candidate = byEdge.get(top.edge());
                if (state.cost + candidate.cost() > options.maxCost()) {
                    continue;
                }
                double gain = state.gain(List.of(candidate));
                double density = density(gain, candidate.cost(), 1, options, byCost);
                Key next = heap.peek();
                if (next != null && (density < next.density() || (density == next.density() && next.edge() < top.edge()))) {
                    heap.add(new Key(top.edge(), density));
                    continue;
                }
                if (gain > 0) {
                    accept(List.of(candidate), state, selected, picked);
                }
            }
        }
        double bound = exhausted ? Double.POSITIVE_INFINITY : bound(byEdge.values(), state, selected, options, meter);
        return new Outcome(picked, state.value(), bound, false, exhausted || Double.isInfinite(bound));
    }

    private static void accept(List<Candidate> unit, State state, Set<Long> selected, List<Selected> picked) {
        for (Candidate member : unit) {
            double single = state.gain(List.of(member));
            state.add(member);
            selected.add(member.edge());
            picked.add(new Selected(member.edge(), single, member.relevance()));
        }
    }

    private static double density(double gain, long cost, int size, Options options, boolean byCost) {
        if (!byCost) {
            return gain / size;
        }
        if (options.maxEdges() == Integer.MAX_VALUE) {
            return gain / cost;
        }
        return gain / Math.max((double) cost / options.maxCost(), (double) size / options.maxEdges());
    }

    private static long cost(List<Candidate> unit) {
        long total = 0;
        for (Candidate candidate : unit) {
            total += candidate.cost();
        }
        return total;
    }

    private static List<Candidate> unit(Candidate candidate, Map<Long, Candidate> byEdge, Set<Long> selected, Options options) {
        List<Candidate> unit = new ArrayList<>();
        unit.add(candidate);
        if (!options.connected()) {
            return unit;
        }
        if (candidate.parent() == UNREACHABLE) {
            return null;
        }
        Set<Long> seen = new LinkedHashSet<>();
        seen.add(candidate.edge());
        long parent = candidate.parent();
        while (parent >= 0 && !selected.contains(parent)) {
            Candidate up = byEdge.get(parent);
            if (up == null || up.parent() == UNREACHABLE || !seen.add(parent)) {
                return null;
            }
            unit.add(up);
            parent = up.parent();
        }
        java.util.Collections.reverse(unit);
        return unit;
    }

    private static double bound(Collection<Candidate> candidates, State state, Set<Long> selected, Options options, Budget.Meter meter) {
        List<double[]> gains = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!meter.tryConsume(1)) {
                return Double.POSITIVE_INFINITY;
            }
            if (!selected.contains(candidate.edge())) {
                double gain = state.gain(List.of(candidate));
                if (gain > 0) {
                    gains.add(new double[]{gain, candidate.cost()});
                }
            }
        }
        double base = state.value();
        gains.sort((a, b) -> Double.compare(b[0], a[0]));
        double byCount = base;
        for (int i = 0; i < Math.min(options.maxEdges(), gains.size()); i++) {
            byCount += gains.get(i)[0];
        }
        if (options.maxCost() == Long.MAX_VALUE) {
            return byCount;
        }
        gains.sort((a, b) -> Double.compare(b[0] / b[1], a[0] / a[1]));
        double byCost = base;
        double room = options.maxCost();
        for (double[] gain : gains) {
            if (room <= 0) {
                break;
            }
            double take = Math.min(1, room / gain[1]);
            byCost += take * gain[0];
            room -= take * gain[1];
        }
        return Math.min(byCount, byCost);
    }
}
