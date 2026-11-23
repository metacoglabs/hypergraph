// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusteringTest {

    private static float[] around(Random random, float[] centre) {
        float[] point = new float[centre.length];
        for (int i = 0; i < centre.length; i++) {
            point[i] = centre[i] + (float) random.nextGaussian() * 0.05f;
        }
        return point;
    }

    @Test
    void separatedGroupsLandInSeparateClusters() {
        Random random = new Random(1);
        float[][] centres = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        List<float[]> points = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            points.add(around(random, centres[i % 3]));
        }
        int[] assignment = Clustering.cluster(points, 3, 42);
        for (int group = 0; group < 3; group++) {
            Set<Integer> labels = new HashSet<>();
            for (int i = group; i < 90; i += 3) {
                labels.add(assignment[i]);
            }
            assertEquals(1, labels.size(), "group " + group);
        }
        assertEquals(3, Set.of(assignment[0], assignment[1], assignment[2]).size());
        assertArrayEquals(assignment, Clustering.cluster(points, 3, 42));
    }

    @Test
    void degenerateInputStillSplitsIntoNonEmptyParts() {
        List<float[]> same = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            same.add(new float[]{1, 1});
        }
        int[] assignment = Clustering.cluster(same, 2, 7);
        long zeros = java.util.Arrays.stream(assignment).filter(label -> label == 0).count();
        assertTrue(zeros > 0 && zeros < 10, "both parts used");
        assertEquals(1, Clustering.cluster(List.of(new float[]{1, 0}), 3, 1).length);
    }

    @Test
    void meanOfUnitVectorsIsKept() {
        float[] mean = Clustering.mean(List.of(new float[]{2, 0}, new float[]{0, 3}), List.of(1L, 1L));
        assertArrayEquals(new float[]{0.5f, 0.5f}, mean, 1e-6f);
        float[] weighted = Clustering.mean(List.of(new float[]{1, 0}, new float[]{0, 1}), List.of(3L, 1L));
        assertArrayEquals(new float[]{0.75f, 0.25f}, weighted, 1e-6f);
        assertEquals(1.0, Clustering.cosine(new float[]{1, 1}, new float[]{2, 2}), 1e-6);
    }
}
