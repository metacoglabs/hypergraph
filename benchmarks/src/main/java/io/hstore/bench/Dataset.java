package io.hstore.bench;

import java.util.Arrays;
import java.util.SplittableRandom;

record Dataset(int nodes, int[][] edges, int[] probeNodes, int[] probeEdges, int[][] probePairs, int[] updates, int[] deletions,
               int[][] removals, int[] probeTwoHop) {

    private static final int NODES_PER_SCALE = 50_000;
    private static final int EDGES_PER_SCALE = 100_000;
    private static final int PROBES = 100_000;
    private static final int PAIRS = 20_000;
    private static final int UPDATES = 20_000;
    private static final int MAX_CARDINALITY = 32;
    private static final double DELETED_FRACTION = 0.2;
    private static final int REMOVALS = 20_000;
    private static final int TWO_HOP_PROBES = 10_000;

    static Dataset generate(int scale, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        int nodes = NODES_PER_SCALE * scale;
        int[][] edges = new int[EDGES_PER_SCALE * scale][];
        for (int e = 0; e < edges.length; e++) {
            int cardinality = 2;
            while (cardinality < MAX_CARDINALITY && random.nextDouble() < 0.62) {
                cardinality++;
            }
            edges[e] = distinctMembers(random, nodes, cardinality);
        }
        int[] probeNodes = random.ints(PROBES, 0, nodes).toArray();
        int[] probeEdges = random.ints(PROBES, 0, edges.length).toArray();
        int[][] pairs = new int[PAIRS][];
        for (int p = 0; p < PAIRS; p++) {
            int[] edge = edges[random.nextInt(edges.length)];
            int first = random.nextInt(edge.length);
            int second = (first + 1 + random.nextInt(edge.length - 1)) % edge.length;
            pairs[p] = new int[]{edge[first], edge[second]};
        }
        int[] updates = random.ints(UPDATES, 0, nodes).toArray();
        int[] order = shuffled(random, edges.length);
        int[] deletions = Arrays.copyOf(order, (int) (edges.length * DELETED_FRACTION));
        int[][] removals = Arrays.stream(order, deletions.length, order.length)
                .filter(edge -> edges[edge].length >= 3)
                .limit(REMOVALS)
                .mapToObj(edge -> new int[]{edge, edges[edge][random.nextInt(edges[edge].length)]})
                .toArray(int[][]::new);
        int[] probeTwoHop = random.ints(TWO_HOP_PROBES, 0, nodes).toArray();
        return new Dataset(nodes, edges, probeNodes, probeEdges, pairs, updates, deletions, removals, probeTwoHop);
    }

    private static int[] shuffled(SplittableRandom random, int length) {
        int[] order = new int[length];
        Arrays.setAll(order, i -> i);
        for (int i = length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
        return order;
    }

    private static int[] distinctMembers(SplittableRandom random, int nodes, int cardinality) {
        int[] members = new int[cardinality];
        int filled = 0;
        while (filled < cardinality) {
            int candidate = (int) (nodes * Math.pow(random.nextDouble(), 2.2));
            boolean duplicate = false;
            for (int i = 0; i < filled && !duplicate; i++) {
                duplicate = members[i] == candidate;
            }
            if (!duplicate) {
                members[filled++] = candidate;
            }
        }
        return members;
    }

    long incidences() {
        long total = 0;
        for (int[] edge : edges) {
            total += edge.length;
        }
        return total;
    }
}
