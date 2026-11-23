// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.hora.Budget;
import io.hstore.db.retrieve.EditDistance.Shape;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditDistanceTest {

    private static Shape random(RandomGenerator random, int maxNodes, int maxEdges, long idBase, int idPool) {
        int n = random.nextInt(maxNodes + 1);
        int k = random.nextInt(maxEdges + 1);
        int[] types = new int[n];
        long[] ids = new long[n];
        Set<Long> used = new HashSet<>();
        for (int i = 0; i < n; i++) {
            types[i] = random.nextInt(2);
            long id;
            do {
                id = idBase + random.nextInt(idPool);
            } while (!used.add(id));
            ids[i] = id;
        }
        int[] edgeTypes = new int[k];
        int[][] members = new int[k][];
        for (int e = 0; e < k; e++) {
            edgeTypes[e] = random.nextInt(2);
            members[e] = n == 0 ? new int[0] : random.ints(random.nextInt(Math.min(n, 3) + 1), 0, n).distinct().toArray();
        }
        return new Shape(types, edgeTypes, members, ids);
    }

    private static List<int[]> injections(int from, int to) {
        List<int[]> all = new ArrayList<>();
        extend(new int[from], 0, new boolean[to], all);
        return all;
    }

    private static void extend(int[] map, int at, boolean[] used, List<int[]> all) {
        if (at == map.length) {
            all.add(map.clone());
            return;
        }
        map[at] = -1;
        extend(map, at + 1, used, all);
        for (int j = 0; j < used.length; j++) {
            if (!used[j]) {
                used[j] = true;
                map[at] = j;
                extend(map, at + 1, used, all);
                used[j] = false;
            }
        }
    }

    private static int cost(Shape q, Shape c, int[] nodes, int[] edges) {
        int cost = 0;
        int mappedNodes = 0;
        for (int v = 0; v < q.nodes(); v++) {
            if (nodes[v] >= 0) {
                mappedNodes++;
                cost += q.nodeTypes()[v] == c.nodeTypes()[nodes[v]] ? 0 : 1;
            }
        }
        cost += q.nodes() + c.nodes() - 2 * mappedNodes;
        int mappedEdges = 0;
        for (int e = 0; e < q.edges(); e++) {
            if (edges[e] >= 0) {
                mappedEdges++;
                cost += q.edgeTypes()[e] == c.edgeTypes()[edges[e]] ? 0 : 1;
            }
        }
        cost += q.edges() + c.edges() - 2 * mappedEdges;
        int preserved = 0;
        for (int e = 0; e < q.edges(); e++) {
            for (int v : q.members()[e]) {
                if (edges[e] >= 0 && nodes[v] >= 0 && Arrays.binarySearch(c.members()[edges[e]], nodes[v]) >= 0) {
                    preserved++;
                }
            }
        }
        return cost + q.incidences() + c.incidences() - 2 * preserved;
    }

    static int bruteForce(Shape q, Shape c) {
        int best = Integer.MAX_VALUE;
        List<int[]> edgeMaps = injections(q.edges(), c.edges());
        for (int[] nodes : injections(q.nodes(), c.nodes())) {
            for (int[] edges : edgeMaps) {
                best = Math.min(best, cost(q, c, nodes, edges));
            }
        }
        return best;
    }

    static int identityBruteForce(Shape q, Shape c) {
        int[] nodes = new int[q.nodes()];
        for (int v = 0; v < q.nodes(); v++) {
            nodes[v] = -1;
            for (int u = 0; u < c.nodes(); u++) {
                if (q.nodeIds()[v] == c.nodeIds()[u]) {
                    nodes[v] = u;
                }
            }
        }
        Shape sameTypes = new Shape(new int[q.nodes()], q.edgeTypes(), q.members(), q.nodeIds());
        Shape otherTypes = new Shape(new int[c.nodes()], c.edgeTypes(), c.members(), c.nodeIds());
        int best = Integer.MAX_VALUE;
        for (int[] edges : injections(q.edges(), c.edges())) {
            best = Math.min(best, cost(sameTypes, otherTypes, nodes, edges));
        }
        return best;
    }

    @Test
    void exactDistanceMatchesEveryNodeAndEdgeMap() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(90L);
        for (int trial = 0; trial < 300; trial++) {
            Shape q = random(random, 4, 3, 0, 4);
            Shape c = random(random, 4, 3, 0, 4);
            int truth = bruteForce(q, c);
            assertEquals(truth, EditDistance.exact(q, c, Double.POSITIVE_INFINITY, Budget.UNBOUNDED.start()), 1e-9, "trial " + trial);
            assertTrue(EditDistance.lower(q, c) <= truth + 1e-9, "lower bound above the distance in trial " + trial);
            assertTrue(EditDistance.upper(q, c) >= truth - 1e-9, "upper bound below the distance in trial " + trial);
            double threshold = random.nextInt(8);
            double cut = EditDistance.exact(q, c, threshold, Budget.UNBOUNDED.start());
            assertEquals(truth <= threshold ? truth : Double.POSITIVE_INFINITY, cut, 1e-9, "threshold " + threshold + " in trial " + trial);
            EditDistance.Interval interval = EditDistance.measure(q, c, threshold, Budget.UNBOUNDED.start());
            assertTrue(interval.lower() <= Math.max(truth, interval.lower()) && interval.upper() >= truth - 1e-9);
            if (truth <= threshold) {
                assertTrue(interval.exact() && interval.lower() == truth);
            } else {
                assertTrue(interval.lower() > threshold);
            }
        }
    }

    @Test
    void identityDistanceFixesTheAtoms() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(91L);
        for (int trial = 0; trial < 300; trial++) {
            Shape q = random(random, 5, 3, 100, 7);
            Shape c = random(random, 5, 3, 100, 7);
            assertEquals(identityBruteForce(q, c), EditDistance.identity(q, c), 1e-9, "trial " + trial);
        }
    }

    @Test
    void assignmentIsOptimal() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(92L);
        for (int trial = 0; trial < 200; trial++) {
            int n = 1 + random.nextInt(6);
            double[][] cost = new double[n][n];
            for (double[] row : cost) {
                for (int j = 0; j < n; j++) {
                    row[j] = random.nextInt(10);
                }
            }
            double best = Double.POSITIVE_INFINITY;
            for (int[] permutation : injections(n, n)) {
                if (Arrays.stream(permutation).allMatch(j -> j >= 0)) {
                    double total = 0;
                    for (int i = 0; i < n; i++) {
                        total += cost[i][permutation[i]];
                    }
                    best = Math.min(best, total);
                }
            }
            assertEquals(best, Assignment.minimum(cost), 1e-9);
        }
    }

    @Test
    void theRunningExampleHasTheDesignedDistances() {
        int person = 0;
        int doc = 1;
        int meeting = 0;
        int call = 1;
        Shape q = new Shape(new int[]{person, person, person, doc}, new int[]{meeting, meeting}, new int[][]{{0, 1, 3}, {1, 2}}, new long[]{1, 2, 3, 4});
        Shape x = new Shape(new int[]{person, person, person, person, doc}, new int[]{meeting, meeting}, new int[][]{{0, 1, 4}, {1, 2, 3}},
                new long[]{5, 6, 7, 8, 9});
        Shape y = new Shape(new int[]{person, doc, person, person}, new int[]{meeting, call}, new int[][]{{0, 1}, {2, 3}}, new long[]{10, 11, 12, 13});
        Shape z = new Shape(new int[]{person, person, person, person}, new int[]{call, call, call}, new int[][]{{0, 1}, {1, 2}, {2, 3}},
                new long[]{14, 15, 16, 17});
        Budget.Meter meter = Budget.UNBOUNDED.start();
        assertEquals(2, EditDistance.exact(q, x, Double.POSITIVE_INFINITY, meter));
        assertEquals(2, EditDistance.exact(q, y, Double.POSITIVE_INFINITY, meter));
        assertEquals(7, EditDistance.exact(q, z, Double.POSITIVE_INFINITY, meter));
        assertEquals(2, EditDistance.lower(q, x));
        assertEquals(2, EditDistance.lower(q, y));
    }
}
