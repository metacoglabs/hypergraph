// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.db.hora.Budget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterTest {

    private static final class Memory implements Router.Tree {
        final Map<Long, List<Long>> children = new HashMap<>();
        final Map<Long, List<float[]>> keys = new HashMap<>();
        final Map<Long, Integer> levels = new HashMap<>();
        final Map<Long, float[]> leaves = new HashMap<>();
        long next = 1_000_000;
        long root;

        long build(List<Long> ids, int fanout, int capacity, long seed) {
            long region = next++;
            List<float[]> vectors = ids.stream().map(leaves::get).toList();
            keys.put(region, List.of(Clustering.mean(vectors, ids.stream().map(_ -> 1L).toList())));
            if (ids.size() <= capacity) {
                children.put(region, ids);
                levels.put(region, 0);
                return region;
            }
            int[] labels = Clustering.cluster(vectors, Math.min(fanout, (ids.size() + capacity - 1) / capacity), seed);
            Map<Integer, List<Long>> groups = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                groups.computeIfAbsent(labels[i], _ -> new ArrayList<>()).add(ids.get(i));
            }
            List<Long> kids = new ArrayList<>();
            int level = 0;
            for (List<Long> group : groups.values()) {
                long child = build(group, fanout, capacity, seed + 1);
                kids.add(child);
                level = Math.max(level, levels.get(child) + 1);
            }
            children.put(region, kids);
            levels.put(region, level);
            return region;
        }

        @Override
        public long root() {
            return root;
        }

        @Override
        public boolean leaf(long region) {
            return levels.get(region) == 0;
        }

        @Override
        public int level(long region) {
            return levels.get(region);
        }

        @Override
        public List<Long> children(long region) {
            return children.get(region);
        }

        @Override
        public List<float[]> keys(long region) {
            return keys.get(region);
        }

        @Override
        public float[] leafVector(long leaf) {
            return leaves.get(leaf);
        }
    }

    private static Memory tree(Random random, int count) {
        Memory memory = new Memory();
        float[][] topics = new float[6][8];
        for (float[] topic : topics) {
            for (int d = 0; d < 8; d++) {
                topic[d] = (float) random.nextGaussian();
            }
        }
        List<Long> ids = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            float[] topic = topics[random.nextInt(topics.length)];
            float[] vector = new float[8];
            for (int d = 0; d < 8; d++) {
                vector[d] = topic[d] + (float) random.nextGaussian() * 0.3f;
            }
            memory.leaves.put(id, vector);
            ids.add(id);
        }
        memory.root = memory.build(ids, 4, 8, 11);
        return memory;
    }

    private static List<Long> exact(Memory memory, float[] query, int k) {
        return memory.leaves.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Long, float[]>>comparingDouble(entry -> -Clustering.cosine(query, entry.getValue()))
                        .thenComparing(Map.Entry::getKey))
                .limit(k).map(Map.Entry::getKey).toList();
    }

    private static float[] query(Random random) {
        float[] query = new float[8];
        for (int d = 0; d < 8; d++) {
            query[d] = (float) random.nextGaussian();
        }
        return query;
    }

    @Test
    void fullMassVisitsEverythingAndMatchesTheExactAnswer() {
        Random random = new Random(2);
        Memory memory = tree(random, 300);
        Router.Policy all = Router.Policy.DEFAULT.withMass(1.0).withMaxRegions(10_000).withMaxChildren(64).withTop(10).withRecall();
        for (int probe = 0; probe < 25; probe++) {
            float[] query = query(random);
            Router.Result result = Router.route(memory, query, all, Router.Calibration.IDENTITY, Budget.DEFAULT.start(), memory.leaves.keySet());
            assertEquals(exact(memory, query, 10), result.leaves().stream().map(Router.Leaf::id).toList());
            assertEquals(300, result.stats().leavesExamined());
            assertEquals(1.0, result.stats().recall().orElseThrow(), 1e-12);
        }
    }

    @Test
    void lowerMassExaminesFewerLeavesAndReportsItsRecall() {
        Random random = new Random(4);
        Memory memory = tree(random, 400);
        double[] masses = {0.5, 0.9, 0.99, 1.0};
        double[] recall = new double[masses.length];
        double[] examined = new double[masses.length];
        for (int probe = 0; probe < 30; probe++) {
            float[] near = memory.leaves.get(1L + random.nextInt(400));
            float[] query = new float[8];
            for (int d = 0; d < 8; d++) {
                query[d] = near[d] + (float) random.nextGaussian() * 0.2f;
            }
            List<Long> want = exact(memory, query, 10);
            for (int m = 0; m < masses.length; m++) {
                Router.Result result = Router.route(memory, query, Router.Policy.DEFAULT.withMass(masses[m]).withTop(10).withRecall()
                        .withFallbackEntropy(1.1).withMaxRegions(10_000).withMaxChildren(64), Router.Calibration.IDENTITY, Budget.DEFAULT.start(),
                        memory.leaves.keySet());
                long overlap = result.leaves().stream().map(Router.Leaf::id).filter(want::contains).count();
                assertEquals(overlap / 10.0, result.stats().recall().orElseThrow(), 1e-12);
                assertTrue(result.stats().mass() >= Math.min(masses[m], 1.0) - 1e-9 || result.stats().mass() >= 0.999);
                recall[m] += overlap / 10.0;
                examined[m] += result.stats().leavesExamined();
            }
        }
        for (int m = 1; m < masses.length; m++) {
            assertTrue(examined[m] >= examined[m - 1], "leaves examined grow with the mass");
            assertTrue(recall[m] >= recall[m - 1], "recall grows with the mass");
        }
        assertTrue(examined[0] < 400 * 30);
        assertEquals(30.0, recall[masses.length - 1], 1e-9);
    }

    @Test
    void flatDistributionsFallBackToTheWidestChoice() {
        Memory memory = new Memory();
        for (long leaf = 1; leaf <= 12; leaf++) {
            memory.leaves.put(leaf, new float[]{1, leaf});
        }
        List<Long> kids = new ArrayList<>();
        for (int c = 0; c < 4; c++) {
            long region = memory.next++;
            memory.children.put(region, List.of(1L + 3 * c, 2L + 3 * c, 3L + 3 * c));
            memory.keys.put(region, List.of(new float[]{1, 1}));
            memory.levels.put(region, 0);
            kids.add(region);
        }
        memory.root = memory.next++;
        memory.children.put(memory.root, kids);
        memory.keys.put(memory.root, List.of(new float[]{1, 1}));
        memory.levels.put(memory.root, 1);
        Router.Result flat = Router.route(memory, new float[]{1, 2}, Router.Policy.DEFAULT.withMass(0.3).withMaxChildren(3),
                Router.Calibration.IDENTITY, Budget.DEFAULT.start(), memory.leaves.keySet());
        assertTrue(flat.stats().fallback());
        assertEquals(1.0, flat.stats().entropy(), 1e-9);
        assertEquals(9, flat.stats().leavesExamined());
        Router.Result strict = Router.route(memory, new float[]{1, 2}, Router.Policy.DEFAULT.withMass(0.3).withMaxChildren(3).withFallbackEntropy(1.1),
                Router.Calibration.IDENTITY, Budget.DEFAULT.start(), memory.leaves.keySet());
        assertEquals(6, strict.stats().leavesExamined());
        Router.Result single = Router.route(memory, new float[]{1, 2}, Router.Policy.DEFAULT.withMass(0.01).withFallbackEntropy(1.1).withMinChildren(1),
                Router.Calibration.IDENTITY, Budget.DEFAULT.start(), memory.leaves.keySet());
        assertEquals(3, single.stats().leavesExamined());
    }

    @Test
    void calibrationCanReverseTheOrder() {
        Random random = new Random(8);
        Memory memory = tree(random, 200);
        float[] query = query(random);
        Router.Policy one = Router.Policy.DEFAULT.withMass(0.01).withFallbackEntropy(1.1).withTop(5).withRecall();
        Router.Result straight = Router.route(memory, query, one, Router.Calibration.IDENTITY, Budget.DEFAULT.start(), memory.leaves.keySet());
        Router.Result reversed = Router.route(memory, query, one, Router.Calibration.uniform(-1, 0), Budget.DEFAULT.start(), memory.leaves.keySet());
        assertTrue(reversed.stats().recall().orElseThrow() < straight.stats().recall().orElseThrow()
                || straight.stats().recall().orElseThrow() == 0, straight.stats() + " " + reversed.stats());
    }
}
