// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

public final class Clustering {

    public static final int ITERATIONS = 10;

    private Clustering() {
    }

    public static float[] unit(float[] vector) {
        double norm = 0;
        for (float component : vector) {
            norm += component * component;
        }
        float[] unit = new float[vector.length];
        if (norm == 0) {
            return unit;
        }
        double scale = 1 / Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            unit[i] = (float) (vector[i] * scale);
        }
        return unit;
    }

    public static double cosine(float[] a, float[] b) {
        double dot = 0;
        double left = 0;
        double right = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            left += a[i] * a[i];
            right += b[i] * b[i];
        }
        return left == 0 || right == 0 ? 0 : dot / Math.sqrt(left * right);
    }

    public static float[] mean(List<float[]> vectors, List<Long> weights) {
        return combine(vectors.stream().map(Clustering::unit).toList(), weights);
    }

    public static float[] combine(List<float[]> means, List<Long> counts) {
        float[] sum = new float[means.getFirst().length];
        double total = 0;
        for (int i = 0; i < means.size(); i++) {
            long weight = counts.get(i);
            total += weight;
            for (int d = 0; d < sum.length; d++) {
                sum[d] += means.get(i)[d] * weight;
            }
        }
        if (total > 0) {
            for (int d = 0; d < sum.length; d++) {
                sum[d] = (float) (sum[d] / total);
            }
        }
        return sum;
    }

    public static int[] cluster(List<float[]> vectors, int k, long seed) {
        int n = vectors.size();
        int clusters = Math.max(1, Math.min(k, n));
        int[] assignment = new int[n];
        if (clusters == 1) {
            return assignment;
        }
        List<float[]> units = vectors.stream().map(Clustering::unit).toList();
        float[][] centres = seeds(units, clusters, new Random(seed));
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            boolean moved = false;
            for (int i = 0; i < n; i++) {
                int best = nearest(units.get(i), centres);
                if (best != assignment[i] || iteration == 0) {
                    moved |= best != assignment[i];
                    assignment[i] = best;
                }
            }
            centres = recentre(units, assignment, clusters, centres);
            if (!moved && iteration > 0) {
                break;
            }
        }
        return balanced(units, assignment, clusters);
    }

    private static float[][] seeds(List<float[]> units, int k, Random random) {
        int n = units.size();
        float[][] centres = new float[k][];
        centres[0] = units.get(random.nextInt(n));
        double[] distance = new double[n];
        Arrays.fill(distance, Double.MAX_VALUE);
        for (int c = 1; c < k; c++) {
            double total = 0;
            for (int i = 0; i < n; i++) {
                distance[i] = Math.min(distance[i], 1 - cosine(units.get(i), centres[c - 1]));
                total += Math.max(distance[i], 0);
            }
            int pick = random.nextInt(n);
            if (total > 0) {
                double target = random.nextDouble() * total;
                for (int i = 0; i < n; i++) {
                    target -= Math.max(distance[i], 0);
                    if (target <= 0) {
                        pick = i;
                        break;
                    }
                }
            }
            centres[c] = units.get(pick);
        }
        return centres;
    }

    private static int nearest(float[] unit, float[][] centres) {
        int best = 0;
        double score = Double.NEGATIVE_INFINITY;
        for (int c = 0; c < centres.length; c++) {
            double similarity = cosine(unit, centres[c]);
            if (similarity > score) {
                score = similarity;
                best = c;
            }
        }
        return best;
    }

    private static float[][] recentre(List<float[]> units, int[] assignment, int k, float[][] previous) {
        float[][] sums = new float[k][units.getFirst().length];
        int[] sizes = new int[k];
        for (int i = 0; i < units.size(); i++) {
            sizes[assignment[i]]++;
            for (int d = 0; d < sums[assignment[i]].length; d++) {
                sums[assignment[i]][d] += units.get(i)[d];
            }
        }
        for (int c = 0; c < k; c++) {
            if (sizes[c] == 0) {
                sums[c] = previous[c];
            }
        }
        return sums;
    }

    private static int[] balanced(List<float[]> units, int[] assignment, int k) {
        int[] sizes = new int[k];
        for (int label : assignment) {
            sizes[label]++;
        }
        int empty = 0;
        for (int size : sizes) {
            empty += size == 0 ? 1 : 0;
        }
        if (empty == 0) {
            return assignment;
        }
        Integer[] order = new Integer[units.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        float[] axis = units.getFirst();
        Arrays.sort(order, (a, b) -> Double.compare(projection(units.get(a), axis), projection(units.get(b), axis)) != 0
                ? Double.compare(projection(units.get(a), axis), projection(units.get(b), axis)) : Integer.compare(a, b));
        int[] even = new int[units.size()];
        for (int rank = 0; rank < order.length; rank++) {
            even[order[rank]] = (int) ((long) rank * k / order.length);
        }
        return even;
    }

    private static double projection(float[] vector, float[] axis) {
        double dot = 0;
        for (int i = 0; i < vector.length; i++) {
            dot += vector[i] * axis[i];
        }
        return dot;
    }
}
