// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.db.hora.Budget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.PriorityQueue;
import java.util.Set;

public final class Router {

    public interface Tree {
        long root();

        boolean leaf(long region);

        int level(long region);

        List<Long> children(long region);

        List<float[]> keys(long region);

        float[] leafVector(long leaf);
    }

    public record Policy(double mass, int minChildren, int maxChildren, int maxDepth, int maxRegions, double temperature,
                         double fallbackEntropy, int top, boolean recall) {

        public static final Policy DEFAULT = new Policy(0.9, 1, 32, 64, 256, Double.NaN, 0.95, 10, false);
        public static final double MIN_TEMPERATURE = 0.001;

        public Policy {
            if (!(mass > 0 && mass <= 1)) {
                throw new IllegalArgumentException("the routing mass must be in (0, 1], not " + mass);
            }
            if (minChildren < 1 || maxChildren < minChildren || maxDepth < 1 || maxRegions < 1 || top < 1 || top > 1000) {
                throw new IllegalArgumentException("routing limits must be positive, MIN at most MAX, and TOP at most 1000");
            }
            if (!Double.isNaN(temperature) && !(temperature >= MIN_TEMPERATURE && temperature <= 100)) {
                throw new IllegalArgumentException("the routing temperature must be between " + MIN_TEMPERATURE + " and 100");
            }
        }

        public Policy withMass(double value) {
            return new Policy(value, minChildren, maxChildren, maxDepth, maxRegions, temperature, fallbackEntropy, top, recall);
        }

        public Policy withMinChildren(int value) {
            return new Policy(mass, value, Math.max(value, maxChildren), maxDepth, maxRegions, temperature, fallbackEntropy, top, recall);
        }

        public Policy withMaxChildren(int value) {
            return new Policy(mass, Math.min(minChildren, value), value, maxDepth, maxRegions, temperature, fallbackEntropy, top, recall);
        }

        public Policy withMaxDepth(int value) {
            return new Policy(mass, minChildren, maxChildren, value, maxRegions, temperature, fallbackEntropy, top, recall);
        }

        public Policy withMaxRegions(int value) {
            return new Policy(mass, minChildren, maxChildren, maxDepth, value, temperature, fallbackEntropy, top, recall);
        }

        public Policy withTemperature(double value) {
            return new Policy(mass, minChildren, maxChildren, maxDepth, maxRegions, value, fallbackEntropy, top, recall);
        }

        public Policy withFallbackEntropy(double value) {
            return new Policy(mass, minChildren, maxChildren, maxDepth, maxRegions, temperature, value, top, recall);
        }

        public Policy withTop(int value) {
            return new Policy(mass, minChildren, maxChildren, maxDepth, maxRegions, temperature, fallbackEntropy, value, recall);
        }

        public Policy withRecall() {
            return new Policy(mass, minChildren, maxChildren, maxDepth, maxRegions, temperature, fallbackEntropy, top, true);
        }
    }

    public record Calibration(double temperature, List<double[]> levels) {

        public static final double TEMPERATURE = 0.1;
        public static final Calibration IDENTITY = new Calibration(TEMPERATURE, List.of());

        public Calibration {
            levels = List.copyOf(levels);
        }

        public static Calibration uniform(double scale, double shift) {
            return new Calibration(TEMPERATURE, List.of(new double[]{scale, shift}));
        }

        double logit(int level, double score) {
            if (levels.isEmpty()) {
                return score;
            }
            double[] line = levels.get(Math.min(level, levels.size() - 1));
            return line[0] * score + line[1];
        }
    }

    public record Leaf(long id, double score, double probability) {
    }

    public record Stats(int regionsScored, int regionsExpanded, int leavesExamined, double entropy, double mass, boolean fallback,
                        OptionalDouble recall) {
    }

    public record Result(List<Leaf> leaves, Stats stats) {
        public Result {
            leaves = List.copyOf(leaves);
        }
    }

    private record Frontier(long region, double probability, int depth) {
    }

    private Router() {
    }

    public static Result route(Tree tree, float[] query, Policy policy, Calibration calibration, Budget.Meter meter, Collection<Long> allLeaves) {
        double temperature = Double.isNaN(policy.temperature()) ? calibration.temperature() : policy.temperature();
        PriorityQueue<Frontier> frontier = new PriorityQueue<>(Comparator.comparingDouble(Frontier::probability).reversed()
                .thenComparingLong(Frontier::region));
        List<Leaf> candidates = new ArrayList<>();
        int scored = 0;
        int expanded = 0;
        int entropies = 0;
        double entropySum = 0;
        double massSum = 0;
        boolean fallback = false;
        if (tree.root() != 0) {
            frontier.add(new Frontier(tree.root(), 1.0, 0));
        }
        while (!frontier.isEmpty() && expanded < policy.maxRegions()) {
            Frontier next = frontier.poll();
            expanded++;
            List<Long> children = tree.children(next.region());
            meter.consume(children.size());
            if (tree.leaf(next.region())) {
                for (long leaf : children) {
                    candidates.add(new Leaf(leaf, Clustering.cosine(query, tree.leafVector(leaf)), next.probability()));
                }
                continue;
            }
            if (next.depth() + 1 > policy.maxDepth() || children.isEmpty()) {
                continue;
            }
            int n = children.size();
            double[] logits = new double[n];
            for (int i = 0; i < n; i++) {
                double best = Double.NEGATIVE_INFINITY;
                for (float[] key : tree.keys(children.get(i))) {
                    best = Math.max(best, Clustering.cosine(query, key));
                }
                logits[i] = calibration.logit(tree.level(next.region()), best == Double.NEGATIVE_INFINITY ? 0 : best) / temperature;
            }
            scored += n;
            double top = Double.NEGATIVE_INFINITY;
            for (double logit : logits) {
                top = Math.max(top, logit);
            }
            double total = 0;
            double[] probability = new double[n];
            for (int i = 0; i < n; i++) {
                probability[i] = Math.exp(logits[i] - top);
                total += probability[i];
            }
            double entropy = 0;
            for (int i = 0; i < n; i++) {
                probability[i] /= total;
                entropy -= probability[i] > 0 ? probability[i] * Math.log(probability[i]) : 0;
            }
            double normalized = n > 1 ? entropy / Math.log(n) : 0;
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            Arrays.sort(order, Comparator.<Integer>comparingDouble(i -> -probability[i]).thenComparingLong(children::get));
            int take;
            if (normalized > policy.fallbackEntropy()) {
                take = Math.min(n, policy.maxChildren());
                fallback = true;
            } else {
                double cumulative = 0;
                take = 0;
                while (take < n && (cumulative < policy.mass() - 1e-12 || take < policy.minChildren())) {
                    cumulative += probability[order[take]];
                    take++;
                }
                take = Math.min(take, Math.min(n, policy.maxChildren()));
            }
            double selected = 0;
            for (int i = 0; i < take; i++) {
                int child = order[i];
                selected += probability[child];
                frontier.add(new Frontier(children.get(child), next.probability() * probability[child], next.depth() + 1));
            }
            entropies++;
            entropySum += normalized;
            massSum += selected;
        }
        candidates.sort(Comparator.comparingDouble(Leaf::score).reversed().thenComparingLong(Leaf::id));
        List<Leaf> best = candidates.subList(0, Math.min(policy.top(), candidates.size()));
        OptionalDouble recall = OptionalDouble.empty();
        if (policy.recall()) {
            List<Long> exact = allLeaves.stream()
                    .sorted(Comparator.<Long>comparingDouble(leaf -> -Clustering.cosine(query, tree.leafVector(leaf))).thenComparingLong(leaf -> leaf))
                    .limit(policy.top()).toList();
            meter.consume(allLeaves.size());
            Set<Long> found = new HashSet<>();
            best.forEach(leaf -> found.add(leaf.id()));
            recall = OptionalDouble.of(exact.isEmpty() ? 1.0 : exact.stream().filter(found::contains).count() / (double) exact.size());
        }
        Stats stats = new Stats(scored, expanded, candidates.size(), entropies == 0 ? 0 : entropySum / entropies,
                entropies == 0 ? 1 : massSum / entropies, fallback, recall);
        return new Result(best, stats);
    }
}
