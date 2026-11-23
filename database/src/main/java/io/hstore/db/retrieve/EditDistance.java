// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.hora.Budget;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class EditDistance {

    public static final int EXACT_EDGE_LIMIT = 5;

    public record Shape(int[] nodeTypes, int[] edgeTypes, int[][] members, long[] nodeIds) {
        public Shape {
            nodeTypes = nodeTypes.clone();
            edgeTypes = edgeTypes.clone();
            members = Arrays.stream(members).map(edge -> Arrays.stream(edge).distinct().sorted().toArray()).toArray(int[][]::new);
            nodeIds = nodeIds.clone();
        }

        public int nodes() {
            return nodeTypes.length;
        }

        public int edges() {
            return edgeTypes.length;
        }

        public int incidences() {
            return Arrays.stream(members).mapToInt(edge -> edge.length).sum();
        }

        int[] degrees() {
            int[] degree = new int[nodeTypes.length];
            for (int[] edge : members) {
                for (int node : edge) {
                    degree[node]++;
                }
            }
            return degree;
        }

        int[] arities() {
            return Arrays.stream(members).mapToInt(edge -> edge.length).toArray();
        }
    }

    public record Interval(double lower, double upper) {
        public boolean exact() {
            return lower == upper;
        }
    }

    private EditDistance() {
    }

    static double withEdgeMap(Shape q, Shape c, int[] map) {
        int[] qDegree = q.degrees();
        int[] cDegree = c.degrees();
        double[][] substitute = new double[q.nodes()][c.nodes()];
        for (int v = 0; v < q.nodes(); v++) {
            for (int u = 0; u < c.nodes(); u++) {
                int kept = 0;
                for (int e = 0; e < map.length; e++) {
                    if (map[e] >= 0 && Arrays.binarySearch(q.members()[e], v) >= 0 && Arrays.binarySearch(c.members()[map[e]], u) >= 0) {
                        kept++;
                    }
                }
                substitute[v][u] = (q.nodeTypes()[v] == c.nodeTypes()[u] ? 0 : 1) + qDegree[v] + cDegree[u] - 2.0 * kept;
            }
        }
        double[] delete = Arrays.stream(qDegree).mapToDouble(degree -> 1.0 + degree).toArray();
        double[] insert = Arrays.stream(cDegree).mapToDouble(degree -> 1.0 + degree).toArray();
        double nodes = q.nodes() + c.nodes() == 0 ? 0 : Assignment.minimum(Assignment.withDeletions(substitute, delete, insert));
        return edgeCost(q, c, map) + nodes;
    }

    private static double edgeCost(Shape q, Shape c, int[] map) {
        int mapped = 0;
        double cost = 0;
        for (int e = 0; e < map.length; e++) {
            if (map[e] >= 0) {
                mapped++;
                cost += q.edgeTypes()[e] == c.edgeTypes()[map[e]] ? 0 : 1;
            }
        }
        return cost + (q.edges() - mapped) + (c.edges() - mapped);
    }

    public static double exact(Shape q, Shape c, double cutoff, Budget.Meter meter) {
        double[] best = {cutoff == Double.POSITIVE_INFINITY ? cutoff : Math.nextUp(cutoff)};
        double start = best[0];
        int[] map = new int[q.edges()];
        Arrays.fill(map, -1);
        search(q, c, 0, 0, 0, map, new boolean[c.edges()], best, meter);
        return best[0] < start || start == Double.POSITIVE_INFINITY ? best[0] : Double.POSITIVE_INFINITY;
    }

    private static void search(Shape q, Shape c, int edge, int mapped, double relabels, int[] map, boolean[] used, double[] best, Budget.Meter meter) {
        int remaining = q.edges() - edge;
        double floor = relabels + (edge - mapped) + Math.max(0, c.edges() - mapped - remaining);
        if (floor >= best[0]) {
            return;
        }
        if (edge == q.edges()) {
            meter.consume(1);
            best[0] = Math.min(best[0], withEdgeMap(q, c, map));
            return;
        }
        for (int f = 0; f < c.edges(); f++) {
            if (!used[f]) {
                used[f] = true;
                map[edge] = f;
                search(q, c, edge + 1, mapped + 1, relabels + (q.edgeTypes()[edge] == c.edgeTypes()[f] ? 0 : 1), map, used, best, meter);
                used[f] = false;
            }
        }
        map[edge] = -1;
        search(q, c, edge + 1, mapped, relabels, map, used, best, meter);
    }

    public static double upper(Shape q, Shape c) {
        int k = q.edges();
        int l = c.edges();
        double[][] substitute = new double[k][l];
        for (int e = 0; e < k; e++) {
            for (int f = 0; f < l; f++) {
                substitute[e][f] = (q.edgeTypes()[e] == c.edgeTypes()[f] ? 0 : 1) + Math.abs(q.members()[e].length - c.members()[f].length);
            }
        }
        int[] map = new int[k];
        Arrays.fill(map, -1);
        if (k + l > 0) {
            double[] delete = Arrays.stream(q.members()).mapToDouble(edge -> 1.0 + edge.length).toArray();
            double[] insert = Arrays.stream(c.members()).mapToDouble(edge -> 1.0 + edge.length).toArray();
            int[] assignment = Assignment.solve(Assignment.withDeletions(substitute, delete, insert));
            for (int e = 0; e < k; e++) {
                map[e] = assignment[e] < l ? assignment[e] : -1;
            }
        }
        return withEdgeMap(q, c, map);
    }

    public static double lower(Shape q, Shape c) {
        double nodes = Math.max(q.nodes(), c.nodes()) - common(q.nodeTypes(), c.nodeTypes());
        double edges = Math.max(q.edges(), c.edges()) - common(q.edgeTypes(), c.edgeTypes());
        double incidences = Math.max(sortedDistance(q.arities(), c.arities()), sortedDistance(q.degrees(), c.degrees()));
        return nodes + edges + incidences;
    }

    private static int common(int[] left, int[] right) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int value : left) {
            counts.merge(value, 1, Integer::sum);
        }
        int shared = 0;
        for (int value : right) {
            Integer count = counts.get(value);
            if (count != null && count > 0) {
                shared++;
                counts.put(value, count - 1);
            }
        }
        return shared;
    }

    private static long sortedDistance(int[] left, int[] right) {
        int[] a = Arrays.stream(left).boxed().sorted((x, y) -> y - x).mapToInt(Integer::intValue).toArray();
        int[] b = Arrays.stream(right).boxed().sorted((x, y) -> y - x).mapToInt(Integer::intValue).toArray();
        long total = 0;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            total += Math.abs((i < a.length ? a[i] : 0) - (i < b.length ? b[i] : 0));
        }
        return total;
    }

    public static double identity(Shape q, Shape c) {
        Map<Long, Integer> qNodes = new HashMap<>();
        for (long id : q.nodeIds()) {
            qNodes.merge(id, 1, Integer::sum);
        }
        long sharedNodes = Arrays.stream(c.nodeIds()).filter(qNodes::containsKey).distinct().count();
        double nodes = q.nodes() + c.nodes() - 2.0 * sharedNodes;
        int k = q.edges();
        int l = c.edges();
        if (k + l == 0) {
            return nodes;
        }
        double[][] substitute = new double[k][l];
        for (int e = 0; e < k; e++) {
            long[] left = Arrays.stream(q.members()[e]).mapToLong(node -> q.nodeIds()[node]).sorted().toArray();
            for (int f = 0; f < l; f++) {
                long[] right = Arrays.stream(c.members()[f]).mapToLong(node -> c.nodeIds()[node]).sorted().toArray();
                long both = Arrays.stream(left).filter(id -> Arrays.binarySearch(right, id) >= 0).count();
                substitute[e][f] = (q.edgeTypes()[e] == c.edgeTypes()[f] ? 0 : 1) + left.length + right.length - 2.0 * both;
            }
        }
        double[] delete = Arrays.stream(q.members()).mapToDouble(edge -> 1.0 + edge.length).toArray();
        double[] insert = Arrays.stream(c.members()).mapToDouble(edge -> 1.0 + edge.length).toArray();
        return nodes + Assignment.minimum(Assignment.withDeletions(substitute, delete, insert));
    }

    public static Interval measure(Shape q, Shape c, double cutoff, Budget.Meter meter) {
        if (q.edges() <= EXACT_EDGE_LIMIT && c.edges() <= EXACT_EDGE_LIMIT) {
            double distance = exact(q, c, cutoff, meter);
            return distance <= cutoff ? new Interval(distance, distance) : new Interval(Math.max(lower(q, c), Math.nextUp(cutoff)), upper(q, c));
        }
        return new Interval(lower(q, c), upper(q, c));
    }
}
