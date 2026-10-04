package io.hstore.bench;

import java.util.SplittableRandom;

record Dataset(int nodes, int[][] edges, int[] probeNodes, int[] probeEdges, int[][] probePairs, int[] updates) {

    private static final int NODES_PER_SCALE = 50_000;
    private static final int EDGES_PER_SCALE = 100_000;
    private static final int PROBES = 100_000;
    private static final int PAIRS = 20_000;
    private static final int UPDATES = 20_000;
    private static final int MAX_CARDINALITY = 32;

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
        return new Dataset(nodes, edges, probeNodes, probeEdges, pairs, updates);
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
