// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.MemberSpec;
import io.hstore.db.Reader;
import io.hstore.db.hora.Budget;
import io.hstore.db.query.Session;
import io.hstore.db.retrieve.EditDistance.Shape;
import io.hstore.db.retrieve.Similarity.Hit;
import io.hstore.db.retrieve.Similarity.Result;
import io.hstore.db.retrieve.Similarity.Space;
import io.hstore.engine.EngineOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimilarityTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;
    List<Long> containers = new ArrayList<>();
    List<Long> children = new ArrayList<>();

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        try (Session admin = new Session(database)) {
            admin.executeAll("""
                    CREATE NODE TYPE Person;
                    CREATE NODE TYPE Doc;
                    CREATE SET EDGE TYPE Meeting;
                    CREATE SET EDGE TYPE Call;
                    CREATE SET EDGE TYPE Episode;
                    """);
        }
    }

    @AfterEach
    void close() {
        database.close();
    }

    private void episodes(RandomGenerator random, int count) {
        database.write(writer -> {
            List<Long> shared = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                shared.add(writer.node(i % 3 == 0 ? "Doc" : "Person", "shared" + i));
            }
            for (int c = 0; c < count; c++) {
                List<Long> pool = new ArrayList<>();
                for (int i = 0; i < 1 + random.nextInt(4); i++) {
                    pool.add(random.nextInt(3) == 0 ? shared.get(random.nextInt(shared.size())) : writer.node(random.nextInt(3) == 0 ? "Doc" : "Person",
                            "n" + c + "_" + i));
                }
                List<Long> parts = new ArrayList<>();
                for (int e = 0; e < random.nextInt(4); e++) {
                    long edge = writer.edge(random.nextBoolean() ? "Meeting" : "Call");
                    Set<Long> members = new HashSet<>();
                    for (int m = 0; m < 1 + random.nextInt(3); m++) {
                        members.add(pool.get(random.nextInt(pool.size())));
                    }
                    members.forEach(member -> writer.add(edge, member, MemberSpec.PLAIN));
                    parts.add(edge);
                    children.add(edge);
                }
                long container = writer.edge("Episode");
                parts.forEach(part -> writer.add(container, part, MemberSpec.PLAIN));
                containers.add(container);
            }
            return null;
        });
    }

    private record Truth(long key, long[] edges, double distance) {
        long smallest() {
            return edges.length == 0 ? key : Math.min(key, Arrays.stream(edges).min().orElse(key));
        }
    }

    private static final Comparator<Truth> ORDER = Comparator.comparingDouble(Truth::distance).thenComparingLong(Truth::smallest)
            .thenComparing(Truth::edges, Arrays::compare);

    private List<Truth> truths(Reader reader, Shape query, boolean identity, List<long[]> sets, List<Long> keys) {
        List<Truth> all = new ArrayList<>();
        for (int i = 0; i < sets.size(); i++) {
            Shape shape = Similarity.shapeOf(reader, sets.get(i));
            all.add(new Truth(keys.get(i), Arrays.stream(sets.get(i)).sorted().toArray(),
                    identity ? EditDistanceTest.identityBruteForce(query, shape) : EditDistanceTest.bruteForce(query, shape)));
        }
        return all;
    }

    @Test
    void childrenSearchesMatchTheBruteForce() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(93L);
        episodes(random, 25);
        database.read(reader -> {
            int episode = reader.requireReadableType("Episode").id();
            List<long[]> sets = containers.stream().map(container -> Similarity.children(reader, container)).toList();
            for (int q = 0; q < 40; q++) {
                long example = containers.get(random.nextInt(containers.size()));
                Shape query = Similarity.shapeOf(reader, Similarity.children(reader, example));
                boolean identity = q % 4 == 3;
                List<Truth> truth = truths(reader, query, identity, sets, containers);
                double threshold = random.nextInt(7);
                Result within = Similarity.within(reader, query, identity, new Space.Children(episode), threshold, Budget.UNBOUNDED);
                assertTrue(within.exact() && within.complete());
                List<Truth> inside = truth.stream().filter(t -> t.distance() <= threshold).sorted(ORDER).toList();
                assertEquals(inside.stream().map(Truth::key).toList(), within.hits().stream().map(Hit::key).toList(), "within query " + q);
                assertEquals(inside.stream().map(Truth::distance).toList(), within.hits().stream().map(Hit::lower).toList());
                int k = 1 + random.nextInt(5);
                Result top = Similarity.top(reader, query, identity, new Space.Children(episode), k, Budget.UNBOUNDED);
                List<Truth> best = truth.stream().sorted(ORDER).limit(k).toList();
                assertEquals(best.stream().map(Truth::key).toList(), top.hits().stream().map(Hit::key).toList(), "top query " + q);
                assertEquals(best.stream().map(Truth::distance).toList(), top.hits().stream().map(Hit::upper).toList());
                assertTrue(top.exact());
            }
            return null;
        });
    }

    private static void connected(Reader reader, long[] set, int max, Set<List<Long>> found) {
        if (!found.add(Arrays.stream(set).boxed().toList()) || set.length == max) {
            return;
        }
        TreeSet<Long> next = new TreeSet<>();
        for (long edge : set) {
            reader.memberIds(edge).forEach(member -> reader.forEachIncidentEdge(member, other -> {
                if (Arrays.binarySearch(set, other) < 0 && reader.typeOf(other).orElse(-1) != reader.requireReadableType("Episode").id()) {
                    next.add(other);
                }
            }));
        }
        for (long other : next) {
            long[] grown = Arrays.copyOf(set, set.length + 1);
            grown[set.length] = other;
            Arrays.sort(grown);
            connected(reader, grown, max, found);
        }
    }

    @Test
    void aroundSearchesMatchTheBruteForce() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(94L);
        episodes(random, 12);
        database.read(reader -> {
            Set<Integer> types = Set.of(reader.requireReadableType("Meeting").id(), reader.requireReadableType("Call").id());
            for (int q = 0; q < 25; q++) {
                long anchor = children.get(random.nextInt(children.size()));
                int size = 1 + random.nextInt(3);
                Set<List<Long>> found = new java.util.LinkedHashSet<>();
                connected(reader, new long[]{anchor}, size, found);
                List<long[]> sets = found.stream().map(list -> list.stream().mapToLong(Long::longValue).toArray()).toList();
                long example = containers.get(random.nextInt(containers.size()));
                Shape query = Similarity.shapeOf(reader, Similarity.children(reader, example));
                List<Truth> truth = truths(reader, query, false, sets, sets.stream().map(_ -> anchor).toList());
                double threshold = random.nextInt(6);
                Result within = Similarity.within(reader, query, false, new Space.Around(anchor, size, types), threshold, Budget.UNBOUNDED);
                List<Truth> inside = truth.stream().filter(t -> t.distance() <= threshold).sorted(ORDER).toList();
                assertEquals(inside.stream().map(t -> Arrays.stream(t.edges()).boxed().toList()).toList(),
                        within.hits().stream().map(hit -> Arrays.stream(hit.edges()).boxed().toList()).toList(), "around query " + q);
                int k = 1 + random.nextInt(4);
                Result top = Similarity.top(reader, query, false, new Space.Around(anchor, size, types), k, Budget.UNBOUNDED);
                assertEquals(truth.stream().sorted(ORDER).limit(k).map(Truth::distance).toList(), top.hits().stream().map(Hit::upper).toList(),
                        "around top query " + q);
            }
            return null;
        });
    }

    @Test
    void topKBreaksTiesLikeTheBruteForce() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(96L);
        episodes(random, 14);
        database.read(reader -> {
            List<long[]> sets = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                long[] set = random.longs(1 + random.nextInt(2), 0, children.size()).map(index -> children.get((int) index)).distinct().sorted().toArray();
                if (sets.stream().noneMatch(other -> Arrays.equals(other, set))) {
                    sets.add(set);
                }
            }
            List<Long> keys = sets.stream().map(set -> set[0]).toList();
            for (int q = 0; q < 150; q++) {
                Shape query = Similarity.shapeOf(reader, sets.get(random.nextInt(sets.size())));
                List<Truth> truth = truths(reader, query, false, sets, keys);
                int k = 1 + random.nextInt(3);
                Result top = Similarity.top(reader, query, false, new Space.Given(sets), k, Budget.UNBOUNDED);
                assertEquals(truth.stream().sorted(ORDER).limit(k).map(t -> Arrays.stream(t.edges()).boxed().toList()).toList(),
                        top.hits().stream().map(hit -> Arrays.stream(hit.edges()).boxed().toList()).toList(), "query " + q);
            }
            return null;
        });
    }

    @Test
    void aroundFindsAnExactCopyAtDistanceZero() {
        episodes(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(97L), 12);
        database.read(reader -> {
            Set<Integer> types = Set.of(reader.requireReadableType("Meeting").id(), reader.requireReadableType("Call").id());
            int checked = 0;
            for (long anchor : children) {
                Set<List<Long>> found = new java.util.LinkedHashSet<>();
                connected(reader, new long[]{anchor}, 2, found);
                for (List<Long> set : found) {
                    long[] edges = set.stream().mapToLong(Long::longValue).toArray();
                    Shape query = Similarity.shapeOf(reader, edges);
                    Result within = Similarity.within(reader, query, false, new Space.Around(anchor, 2, types), 0, Budget.UNBOUNDED);
                    assertTrue(within.hits().stream().anyMatch(hit -> Arrays.equals(hit.edges(), edges)), "anchor " + anchor + " set " + set);
                    checked++;
                }
            }
            assertTrue(checked > 10);
            return null;
        });
    }

    @Test
    void aHugeCandidateGetsAnIntervalInsteadOfAnAssignment() {
        long[] big = database.write(writer -> {
            long edge = writer.edge("Meeting");
            for (int i = 0; i < 3_000; i++) {
                writer.add(edge, writer.node("Person", "crowd" + i), MemberSpec.PLAIN);
            }
            return new long[]{edge};
        });
        database.read(reader -> {
            Shape query = new Shape(new int[]{reader.requireReadableType("Person").id()}, new int[]{reader.requireReadableType("Meeting").id()},
                    new int[][]{{0}}, new long[]{-1});
            Result result = Similarity.top(reader, query, false, new Space.Given(List.of(big)), 1, Budget.DEFAULT);
            Hit hit = result.hits().getFirst();
            assertTrue(hit.lower() <= 5_998 && hit.upper() >= 5_998, hit.toString());
            assertFalse(result.exact());
            return null;
        });
    }

    @Test
    void anExactAnswerIsNeverClaimedWhenAnIntervalWasDropped() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(98L);
        episodes(random, 20);
        database.read(reader -> {
            List<long[]> sets = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                int size = random.nextInt(3) == 0 ? 6 + random.nextInt(2) : 1 + random.nextInt(2);
                long[] set = random.longs(size * 2L, 0, children.size()).map(index -> children.get((int) index)).distinct().limit(size).sorted().toArray();
                if (sets.stream().noneMatch(other -> Arrays.equals(other, set))) {
                    sets.add(set);
                }
            }
            int claimed = 0;
            for (int q = 0; q < 60; q++) {
                Shape query = Similarity.shapeOf(reader, sets.get(random.nextInt(sets.size())));
                int k = 1 + random.nextInt(3);
                Result top = Similarity.top(reader, query, false, new Space.Given(sets), k, Budget.UNBOUNDED);
                if (top.exact()) {
                    claimed++;
                    List<Double> truth = sets.stream().map(set -> EditDistance.exact(query, Similarity.shapeOf(reader, set), Double.POSITIVE_INFINITY,
                            Budget.UNBOUNDED.start())).sorted().limit(k).toList();
                    assertEquals(truth, top.hits().stream().map(Hit::upper).toList(), "query " + q);
                }
            }
            assertTrue(claimed > 10, "claimed " + claimed);
            return null;
        });
    }

    @Test
    void runningOutOfBudgetKeepsWhatWasVerified() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(99L);
        episodes(random, 5);
        long anchor = database.write(writer -> {
            long hub = writer.node("Person", "hub");
            long first = -1;
            for (int i = 0; i < 40; i++) {
                long edge = writer.edge("Meeting");
                writer.add(edge, hub, MemberSpec.PLAIN);
                writer.add(edge, writer.node("Person", "spoke" + i), MemberSpec.PLAIN);
                first = first < 0 ? edge : first;
            }
            return first;
        });
        database.read(reader -> {
            Set<Integer> types = Set.of(reader.requireReadableType("Meeting").id(), reader.requireReadableType("Call").id());
            Shape query = Similarity.shapeOf(reader, new long[]{anchor});
            Result top = Similarity.top(reader, query, false, new Space.Around(anchor, 6, types), 3, Budget.DEFAULT.withRows(60));
            assertFalse(top.complete());
            assertFalse(top.hits().isEmpty(), top.toString());
            Result within = Similarity.within(reader, query, false, new Space.Around(anchor, 6, types), 50, Budget.DEFAULT.withRows(60));
            assertFalse(within.complete());
            assertFalse(within.hits().isEmpty());
            Result twice = Similarity.top(reader, query, false, new Space.Given(List.of(new long[]{anchor}, new long[]{anchor})), 5, Budget.UNBOUNDED);
            assertEquals(1, twice.hits().size());
            return null;
        });
    }

    @Test
    void aSmallBudgetGivesAnIncompleteAnswer() {
        episodes(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(95L), 30);
        database.read(reader -> {
            Shape query = Similarity.shapeOf(reader, Similarity.children(reader, containers.getFirst()));
            Result result = Similarity.top(reader, query, false, new Space.Children(reader.requireReadableType("Episode").id()), 3,
                    Budget.DEFAULT.withRows(10));
            assertFalse(result.complete());
            assertFalse(result.exact());
            return null;
        });
    }
}
