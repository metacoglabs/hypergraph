// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.hora.Budget;
import io.hstore.db.retrieve.Selection.Candidate;
import io.hstore.db.retrieve.Selection.Options;
import io.hstore.db.retrieve.Selection.Outcome;
import io.hstore.db.retrieve.Selection.Selected;
import io.hstore.engine.HStoreException;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionTest {

    private static double oracle(List<Candidate> set, Options options) {
        Map<Long, Double> atoms = new HashMap<>();
        for (Candidate candidate : set) {
            for (long member : candidate.members()) {
                atoms.merge(member, candidate.relevance() / candidate.members().length, Double::sum);
            }
        }
        double relevance = atoms.values().stream().mapToDouble(value -> Math.min(options.kappa(), value)).sum();
        double coverage = 0;
        for (int i = 0; i < options.slotWeights().length; i++) {
            double missing = 1;
            for (Candidate candidate : set) {
                missing *= 1 - (i < candidate.cover().length ? candidate.cover()[i] : 0);
            }
            coverage += options.slotWeights()[i] * (1 - missing);
        }
        return relevance + options.lambda() * coverage;
    }

    private static boolean feasible(List<Candidate> set, Options options, Map<Long, Candidate> byEdge) {
        if (set.size() > options.maxEdges() || set.stream().mapToLong(Candidate::cost).sum() > options.maxCost()) {
            return false;
        }
        if (!options.connected()) {
            return true;
        }
        Set<Long> edges = new HashSet<>();
        set.forEach(candidate -> edges.add(candidate.edge()));
        for (Candidate candidate : set) {
            if (candidate.parent() >= 0 && !edges.contains(candidate.parent())) {
                return false;
            }
        }
        return true;
    }

    private static List<Candidate> randomCandidates(RandomGenerator random, int n, int slots, boolean chains) {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long[] members = new long[1 + random.nextInt(4)];
            for (int m = 0; m < members.length; m++) {
                members[m] = random.nextInt(12);
            }
            double[] cover = new double[slots];
            for (int s = 0; s < slots; s++) {
                cover[s] = List.of(0.0, 0.0, 0.5, 1.0).get(random.nextInt(4));
            }
            long parent = chains && i > 0 && random.nextBoolean() ? 100 + random.nextInt(i) : -1;
            candidates.add(new Candidate(100 + i, random.nextInt(11) / 10.0, cover, 1 + random.nextInt(5), members, parent));
        }
        return candidates;
    }

    private static double optimum(List<Candidate> candidates, Options options) {
        Map<Long, Candidate> byEdge = new HashMap<>();
        candidates.forEach(candidate -> byEdge.put(candidate.edge(), candidate));
        double best = 0;
        for (int mask = 0; mask < (1 << candidates.size()); mask++) {
            List<Candidate> set = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                if ((mask & (1 << i)) != 0) {
                    set.add(candidates.get(i));
                }
            }
            if (feasible(set, options, byEdge)) {
                best = Math.max(best, oracle(set, options));
            }
        }
        return best;
    }

    private void check(boolean budgeted, boolean connected, long seed) {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(seed);
        for (int q = 0; q < 150; q++) {
            int slots = random.nextInt(4);
            List<Candidate> candidates = randomCandidates(random, 4 + random.nextInt(9), slots, connected);
            double[] weights = new double[slots];
            for (int s = 0; s < slots; s++) {
                weights[s] = 0.5 + random.nextInt(3) / 2.0;
            }
            Options options = new Options(budgeted ? 100 : 1 + random.nextInt(5), budgeted ? 2 + random.nextInt(12) : Long.MAX_VALUE,
                    List.of(0.1, 0.25, 1.0).get(random.nextInt(3)), random.nextInt(3) * 0.5, weights, connected);
            Outcome outcome = Selection.select(candidates, options, Budget.UNBOUNDED.start());
            Map<Long, Candidate> byEdge = new HashMap<>();
            candidates.forEach(candidate -> byEdge.put(candidate.edge(), candidate));
            List<Candidate> chosen = outcome.selected().stream().map(Selected::edge).map(byEdge::get).toList();
            assertTrue(feasible(chosen, options, byEdge), "infeasible selection " + outcome);
            double value = oracle(chosen, options);
            assertEquals(value, outcome.objective(), 1e-9, "objective");
            double best = optimum(candidates, options);
            assertTrue(value <= best + 1e-9, "selection above the optimum");
            assertTrue(best <= outcome.bound() + 1e-9, "bound " + outcome.bound() + " below the optimum " + best);
            if (!connected) {
                double ratio = budgeted ? (1 - 1 / Math.E) / 2 : 1 - 1 / Math.E;
                assertTrue(value >= ratio * best - 1e-9, "value " + value + " below the guarantee of " + best);
            }
        }
    }

    @Test
    void lazyGreedyUnderACountLimitMeetsItsGuarantee() {
        check(false, false, 61L);
    }

    @Test
    void costBenefitGreedyUnderABudgetMeetsItsGuarantee() {
        check(true, false, 62L);
    }

    @Test
    void connectedSelectionsAreClosedUnderTheirChainsAndBounded() {
        check(false, true, 63L);
        check(true, true, 64L);
    }

    private static double eagerGreedy(List<Candidate> candidates, Options options) {
        return eagerGreedy(candidates, options, options.maxCost() != Long.MAX_VALUE);
    }

    private static double eagerGreedy(List<Candidate> candidates, Options options, boolean byCost) {
        List<Candidate> chosen = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        while (true) {
            Candidate best = null;
            double bestDensity = 0;
            double base = oracle(chosen, options);
            long cost = chosen.stream().mapToLong(Candidate::cost).sum();
            for (Candidate candidate : candidates) {
                if (used.contains(candidate.edge()) || chosen.size() + 1 > options.maxEdges() || cost + candidate.cost() > options.maxCost()) {
                    continue;
                }
                List<Candidate> with = new ArrayList<>(chosen);
                with.add(candidate);
                double gain = oracle(with, options) - base;
                double density = byCost ? gain / candidate.cost() : gain;
                if (density > bestDensity) {
                    best = candidate;
                    bestDensity = density;
                }
            }
            if (best == null) {
                return oracle(chosen, options);
            }
            chosen.add(best);
            used.add(best.edge());
        }
    }

    @Test
    void lazyGreedyMatchesEagerGreedy() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(65L);
        for (int q = 0; q < 300; q++) {
            int slots = random.nextInt(4);
            List<Candidate> candidates = new ArrayList<>();
            for (int i = 0; i < 6 + random.nextInt(20); i++) {
                long[] members = new long[1 + random.nextInt(4)];
                for (int m = 0; m < members.length; m++) {
                    members[m] = random.nextInt(15);
                }
                double[] cover = new double[slots];
                for (int c = 0; c < slots; c++) {
                    cover[c] = random.nextDouble();
                }
                candidates.add(new Candidate(100 + i, random.nextDouble(), cover, 1 + random.nextInt(5), members, -1));
            }
            double[] weights = new double[slots];
            for (int c = 0; c < slots; c++) {
                weights[c] = 0.1 + random.nextDouble();
            }
            boolean budgeted = random.nextBoolean();
            Options options = new Options(budgeted ? Integer.MAX_VALUE : 1 + random.nextInt(8), budgeted ? 3 + random.nextInt(15) : Long.MAX_VALUE,
                    1 + random.nextDouble(), random.nextDouble(), weights, false);
            Outcome outcome = Selection.select(candidates, options, Budget.UNBOUNDED.start());
            double eager = eagerGreedy(candidates, options);
            if (!outcome.singleton()) {
                assertEquals(eager, outcome.objective(), 1e-9, "query " + q);
            } else {
                assertTrue(outcome.objective() >= eager - 1e-9);
            }
        }
    }

    @Test
    void bothLimitsTogetherNeverLoseToCountOnlyGreedy() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(67L);
        for (int q = 0; q < 500; q++) {
            List<Candidate> candidates = randomCandidates(random, 4 + random.nextInt(9), 0, false);
            Options options = new Options(1 + random.nextInt(4), 2 + random.nextInt(12), List.of(0.1, 0.25, 1.0).get(random.nextInt(3)), 0,
                    new double[0], false);
            Outcome outcome = Selection.select(candidates, options, Budget.UNBOUNDED.start());
            assertTrue(outcome.objective() >= eagerGreedy(candidates, options, false) - 1e-9, "query " + q);
        }
    }

    @Test
    void theBestSingleCandidateRescuesABadGreedyPrefix() {
        Options options = new Options(Integer.MAX_VALUE, 10, 1.0, 0, new double[0], false);
        Candidate cheap = new Candidate(1, 0.1, new double[0], 1, new long[]{1}, -1);
        Candidate big = new Candidate(2, 0.9, new double[0], 10, new long[]{2}, -1);
        Outcome outcome = Selection.select(List.of(cheap, big), options, Budget.UNBOUNDED.start());
        assertEquals(List.of(2L), outcome.selected().stream().map(Selected::edge).toList());
        assertTrue(outcome.singleton());
    }

    @Test
    void aCountLimitAndABudgetTogetherKeepAGuarantee() {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            candidates.add(new Candidate(i, 0.02, new double[0], 1, new long[]{i}, -1));
            candidates.add(new Candidate(100 + i, 1.0, new double[0], 100, new long[]{100 + i}, -1));
        }
        Outcome outcome = Selection.select(candidates, new Options(10, 1000, 1.0, 0, new double[0], false), Budget.UNBOUNDED.start());
        assertTrue(outcome.objective() >= (1 - 1 / Math.E) / 2 * 10, outcome.toString());
    }

    @Test
    void connectedUnitsArePricedByTheSlotsTheyUse() {
        List<Candidate> candidates = List.of(
                new Candidate(1, 0.2, new double[0], 1, new long[]{1}, -1),
                new Candidate(2, 0.2, new double[0], 1, new long[]{2}, 1),
                new Candidate(3, 0.2, new double[0], 1, new long[]{3}, 2),
                new Candidate(4, 0.5, new double[0], 1, new long[]{4}, -1),
                new Candidate(5, 0.5, new double[0], 1, new long[]{5}, -1),
                new Candidate(6, 0.5, new double[0], 1, new long[]{6}, -1));
        Outcome outcome = Selection.select(candidates, new Options(3, Long.MAX_VALUE, 1.0, 0, new double[0], true), Budget.UNBOUNDED.start());
        assertEquals(1.5, outcome.objective(), 1e-12, outcome.toString());
    }

    @Test
    void runningOutOfBudgetReturnsTheGreedyPrefix() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(66L);
        List<Candidate> candidates = randomCandidates(random, 200, 2, true);
        for (boolean connected : new boolean[]{false, true}) {
            Options options = new Options(20, 60, 0.25, 1, new double[]{1, 1}, connected);
            Outcome outcome = Selection.select(candidates, options, Budget.DEFAULT.withRows(300).start());
            assertTrue(outcome.exhausted(), outcome.toString());
            Map<Long, Candidate> byEdge = new HashMap<>();
            candidates.forEach(candidate -> byEdge.put(candidate.edge(), candidate));
            List<Candidate> chosen = outcome.selected().stream().map(Selected::edge).map(byEdge::get).toList();
            assertTrue(feasible(chosen, options, byEdge));
            assertEquals(oracle(chosen, options), outcome.objective(), 1e-9);
        }
    }

    @Test
    void aDuplicateAddsNothingOnceItsMembersAreCapped() {
        Options options = new Options(5, Long.MAX_VALUE, 0.25, 0, new double[0], false);
        Candidate first = new Candidate(1, 0.5, new double[0], 1, new long[]{10, 11}, -1);
        Candidate copy = new Candidate(2, 0.5, new double[0], 1, new long[]{10, 11}, -1);
        Outcome outcome = Selection.select(List.of(first, copy), options, Budget.UNBOUNDED.start());
        assertEquals(1, outcome.selected().size());
        assertEquals(0.5, outcome.objective(), 1e-12);
    }

    @Test
    void invalidInputsAreRefused() {
        assertThrows(HStoreException.class, () -> new Candidate(1, 1.5, new double[0], 1, new long[0], -1));
        assertThrows(HStoreException.class, () -> new Candidate(1, 0.5, new double[]{2}, 1, new long[0], -1));
        assertThrows(HStoreException.class, () -> new Options(0, 10, 0.1, 1, new double[0], false));
        assertThrows(HStoreException.class, () -> new Options(3, 10, 0, 1, new double[0], false));
    }
}
