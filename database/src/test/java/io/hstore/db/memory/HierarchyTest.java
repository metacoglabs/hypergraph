// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Reader;
import io.hstore.db.hora.Budget;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchyTest {

    private static final String[] WORDS = {"claims", "hstore", "postgres", "alice", "bob", "decision", "budget", "migration", "router",
            "memory", "graph", "search", "vector", "meeting", "project", "review"};

    @TempDir
    Path directory;

    HypergraphDatabase database;
    Random random = new Random(17);
    String model;

    @BeforeEach
    void open() {
        database = reopen();
        model = database.semantic().encoder().model();
        database.write(writer -> {
            writer.defineNode("Note", List.of());
            writer.defineNode("Log", List.of());
            return null;
        });
    }

    private HypergraphDatabase reopen() {
        return HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
    }

    @AfterEach
    void close() {
        database.close();
    }

    private String text() {
        StringBuilder markdown = new StringBuilder();
        for (int section = 0; section < 1 + random.nextInt(3); section++) {
            markdown.append("# ").append(WORDS[random.nextInt(WORDS.length)]).append('\n');
            for (int word = 0; word < 3 + random.nextInt(6); word++) {
                markdown.append(WORDS[random.nextInt(WORDS.length)]).append(' ');
            }
            markdown.append('\n');
        }
        return markdown.toString();
    }

    private long note(String type, String key) {
        return database.write(writer -> {
            long node = writer.find(type, key).orElseGet(() -> writer.node(type, key));
            writer.appendRevision(node, text(), RevisionSpec.DEFAULT.embedded());
            return node;
        });
    }

    private Hierarchies.Spec spec(String name, boolean continuous) {
        return new Hierarchies.Spec(name, model, "content", Optional.empty(), 4, 5, continuous);
    }

    private Map<Long, float[]> expectedLeaves(Reader reader, Optional<String> type) {
        Map<Long, float[]> leaves = new HashMap<>();
        for (String name : type.map(List::of).orElse(List.of("Note", "Log"))) {
            reader.atoms(name).forEach(node -> reader.latestRevision(node).ifPresent(revision -> reader.sections(revision.id())
                    .forEach(section -> reader.vectors(section.id()).stream()
                            .filter(vector -> vector.view().equals("content"))
                            .findFirst()
                            .ifPresent(vector -> leaves.put(section.id(), database.semantic().vectorOf(vector))))));
        }
        return leaves;
    }

    private void assertSound(String name, Optional<String> type) {
        database.read(reader -> {
            Hierarchy hierarchy = Hierarchies.find(reader, name).orElseThrow();
            Map<Long, float[]> expected = expectedLeaves(reader, type);
            Map<Long, Long> leaves = Hierarchies.leaves(reader, hierarchy);
            assertEquals(expected.keySet(), leaves.keySet(), "every current section with a vector is one leaf");
            assertEquals(expected.size(), hierarchy.leaves());
            Map<Long, Region> regions = new HashMap<>();
            Hierarchies.regions(reader, hierarchy).forEach(region -> regions.put(region.id(), region));
            if (expected.isEmpty()) {
                assertEquals(0, hierarchy.root());
                return null;
            }
            Region root = regions.get(hierarchy.root());
            assertEquals(0, root.parent());
            for (Region region : regions.values()) {
                List<Long> children = Hierarchies.children(reader, region.id());
                if (region.level() == 0) {
                    assertTrue(children.size() <= hierarchy.capacity(), "leaf region within capacity");
                    assertEquals(children.size(), region.count());
                    children.forEach(leaf -> assertEquals(region.id(), leaves.get(leaf)));
                    float[] mean = Clustering.mean(children.stream().map(expected::get).toList(), children.stream().map(_ -> 1L).toList());
                    assertArrayEquals(mean, Hierarchies.centroid(reader, hierarchy, region.id()), 1e-4f);
                } else {
                    assertTrue(children.size() <= hierarchy.fanout() && !children.isEmpty(), "internal region within fanout");
                    assertEquals(children.stream().mapToLong(child -> regions.get(child).count()).sum(), region.count());
                    children.forEach(child -> assertEquals(region.id(), regions.get(child).parent()));
                    children.forEach(child -> assertTrue(regions.get(child).level() < region.level()));
                }
                assertTrue(region.count() > 0, "no empty regions");
            }
            assertEquals(expected.size(), root.count());
            return null;
        });
    }

    private List<Long> exact(float[] query, int k) {
        return database.read(reader -> expectedLeaves(reader, Optional.empty()).entrySet().stream()
                .sorted(Comparator.<Map.Entry<Long, float[]>>comparingDouble(entry -> -Clustering.cosine(query, entry.getValue()))
                        .thenComparing(Map.Entry::getKey))
                .limit(k).map(Map.Entry::getKey).toList());
    }

    @Test
    void aBuiltHierarchyHoldsEveryCurrentSectionOnce() {
        for (int i = 0; i < 40; i++) {
            note(i % 3 == 0 ? "Log" : "Note", "n" + i);
        }
        for (int i = 0; i < 40; i += 4) {
            note(i % 3 == 0 ? "Log" : "Note", "n" + i);
        }
        database.write(writer -> Hierarchies.create(writer, spec("memory", false)));
        assertSound("memory", Optional.empty());
        database.write(writer -> Hierarchies.create(writer, new Hierarchies.Spec("logs", model, "content", Optional.of("Log"), 3, 4, false)));
        assertSound("logs", Optional.of("Log"));
        assertThrows(HStoreException.class, () -> database.write(writer -> Hierarchies.create(writer, spec("memory", false))));
    }

    @Test
    void routingAtFullMassFindsTheExactNeighbours() {
        for (int i = 0; i < 50; i++) {
            note("Note", "n" + i);
        }
        database.write(writer -> Hierarchies.create(writer, spec("memory", false)));
        Router.Policy all = Router.Policy.DEFAULT.withMass(1.0).withMaxRegions(100_000).withMaxChildren(64).withTop(8).withRecall();
        for (int probe = 0; probe < 15; probe++) {
            String query = WORDS[random.nextInt(WORDS.length)] + " " + WORDS[random.nextInt(WORDS.length)];
            float[] vector = database.semantic().encoder().encode(query).vector();
            Router.Result result = database.read(reader -> Hierarchies.route(reader, "memory", vector, all, Budget.DEFAULT.start()));
            assertEquals(exact(vector, 8), result.leaves().stream().map(Router.Leaf::id).toList(), query);
            assertEquals(1.0, result.stats().recall().orElseThrow(), 1e-12);
        }
        Router.Result narrow = database.read(reader -> Hierarchies.route(reader, "memory", database.semantic().encoder().encode("alice budget").vector(),
                Router.Policy.DEFAULT.withMass(0.3).withFallbackEntropy(1.1).withRecall(), Budget.DEFAULT.start()));
        assertTrue(narrow.stats().leavesExamined() < database.read(reader -> Hierarchies.find(reader, "memory").orElseThrow().leaves()));
    }

    @Test
    void refreshFollowsNewRevisionsAndPurges() {
        List<Long> nodes = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            nodes.add(note("Note", "n" + i));
        }
        database.write(writer -> Hierarchies.create(writer, spec("memory", false)));
        long built = database.read(reader -> Hierarchies.find(reader, "memory").orElseThrow().generation());
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 10; i++) {
                note("Note", "n" + random.nextInt(45));
            }
            long victim = nodes.remove(random.nextInt(nodes.size()));
            database.write(writer -> writer.purge(victim));
            database.write(writer -> {
                long plain = writer.node("Note", "plain" + System.nanoTime());
                writer.appendRevision(plain, "# no vector\nhere\n", RevisionSpec.DEFAULT);
                return null;
            });
            database.write(writer -> Hierarchies.refresh(writer, "memory"));
            assertSound("memory", Optional.empty());
        }
        assertTrue(database.read(reader -> Hierarchies.find(reader, "memory").orElseThrow().generation()) > built);
        database.write(writer -> Hierarchies.rebuild(writer, "memory"));
        assertSound("memory", Optional.empty());
    }

    @Test
    void continuousHierarchiesKeepUpAndSurviveRestarts() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            note("Note", "n" + i);
        }
        database.write(writer -> Hierarchies.create(writer, spec("live", true)));
        for (int i = 0; i < 15; i++) {
            note("Note", "m" + i);
        }
        long target = database.engine().transactions().current().id();
        long deadline = System.currentTimeMillis() + 10_000;
        while (database.read(reader -> Hierarchies.find(reader, "live").orElseThrow().generation()) < target
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertSound("live", Optional.empty());
        float[] query = database.semantic().encoder().encode("memory graph").vector();
        List<Long> before = database.read(reader -> Hierarchies.route(reader, "live", query, Router.Policy.DEFAULT, Budget.DEFAULT.start()))
                .leaves().stream().map(Router.Leaf::id).toList();
        database.close();
        database = reopen();
        assertSound("live", Optional.empty());
        assertEquals(before, database.read(reader -> Hierarchies.route(reader, "live", query, Router.Policy.DEFAULT, Budget.DEFAULT.start()))
                .leaves().stream().map(Router.Leaf::id).toList());
    }

    @Test
    void referencesAndCalibrationSteerTheRouter() {
        for (int i = 0; i < 40; i++) {
            note("Note", "n" + i);
        }
        database.write(writer -> Hierarchies.create(writer, spec("memory", false)));
        float[] query = database.semantic().encoder().encode("alice decision").vector();
        Router.Policy one = Router.Policy.DEFAULT.withMass(0.01).withFallbackEntropy(1.1).withTop(3);
        Hierarchy hierarchy = database.read(reader -> Hierarchies.find(reader, "memory").orElseThrow());
        List<Long> top = database.read(reader -> Hierarchies.children(reader, hierarchy.root()));
        long first = database.read(reader -> Hierarchies.route(reader, "memory", query, one, Budget.DEFAULT.start())).leaves().getFirst().id();
        long firstRegion = database.read(reader -> topRegionOf(reader, hierarchy, first, top));
        long other = top.stream().filter(region -> region != firstRegion).findFirst().orElseThrow();
        database.write(writer -> {
            Hierarchies.references(writer, "memory", other, List.of(query));
            return null;
        });
        long steered = database.read(reader -> Hierarchies.route(reader, "memory", query, one, Budget.DEFAULT.start())).leaves().getFirst().id();
        assertEquals(other, (long) database.read(reader -> topRegionOf(reader, hierarchy, steered, top)));
        database.write(writer -> Hierarchies.calibrate(writer, "memory", java.util.OptionalDouble.of(0.5), Map.of(0, new double[]{-1, 0})));
        Hierarchy calibrated = database.read(reader -> Hierarchies.find(reader, "memory").orElseThrow());
        assertEquals(0.5, calibrated.temperature());
        assertEquals(hierarchy.version() + 1, calibrated.version());
        assertThrows(HStoreException.class, () -> database.write(writer -> {
            Hierarchies.references(writer, "memory", 123456789L, List.of(query));
            return null;
        }));
    }

    private static long topRegionOf(Reader reader, Hierarchy hierarchy, long leaf, List<Long> top) {
        long region = Hierarchies.leaves(reader, hierarchy).get(leaf);
        Map<Long, Region> regions = new HashMap<>();
        Hierarchies.regions(reader, hierarchy).forEach(found -> regions.put(found.id(), found));
        while (!top.contains(region)) {
            region = regions.get(region).parent();
        }
        return region;
    }

    @Test
    void droppingRemovesEveryRegionAndCentroid() {
        for (int i = 0; i < 12; i++) {
            note("Note", "n" + i);
        }
        database.write(writer -> Hierarchies.create(writer, spec("memory", false)));
        long vectorsBefore = database.read(reader -> reader.engineView().scan(VectorRecord.VECTORS).size());
        Set<Long> regions = new HashSet<>(database.read(reader -> Hierarchies.regions(reader, Hierarchies.find(reader, "memory").orElseThrow())
                .stream().map(Region::id).toList()));
        database.write(writer -> {
            Hierarchies.drop(writer, "memory");
            return null;
        });
        database.read(reader -> {
            assertTrue(Hierarchies.find(reader, "memory").isEmpty());
            assertEquals(vectorsBefore - regions.size(), reader.engineView().scan(VectorRecord.VECTORS).size());
            assertEquals(0, reader.engineView().scan(HierarchySlots.REGIONS).size());
            assertEquals(0, reader.engineView().scan(HierarchySlots.LEAVES).size());
            return null;
        });
    }
}
