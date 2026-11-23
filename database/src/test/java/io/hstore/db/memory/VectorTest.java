// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.semantic.Embedding;
import io.hstore.db.semantic.SemanticPlane.Consistency;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;

    @BeforeEach
    void open() {
        database = reopen();
        database.write(writer -> {
            writer.defineNode("Region", List.of());
            writer.defineNode("Note", List.of());
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

    @Test
    void oneOwnerKeepsSeveralViewsAndAViewIsReplaced() {
        long region = database.write(writer -> writer.node("Region", "claimsbay"));
        database.write(writer -> {
            writer.vector(region, "people", "toy", 1, new float[]{1, 0, 0});
            writer.vector(region, "decisions", "toy", 1, new float[]{0, 1, 0});
            writer.vector(region, "decisions", "toy", 1, new float[]{0, 0.5f, 0.5f});
            return null;
        });
        List<VectorRecord> vectors = database.read(reader -> reader.vectors(region));
        assertEquals(List.of("decisions", "people"), vectors.stream().map(VectorRecord::view).sorted().toList());
        assertEquals(1, database.read(reader -> reader.nearestVectors("toy", new float[]{0, 1, 1}, 1, Optional.of("decisions"), Consistency.FRESH))
                .size());
        assertEquals(region, database.read(reader -> reader.nearestVectors("toy", new float[]{0, 1, 1}, 1, Optional.empty(), Consistency.FRESH))
                .getFirst().owner());
        assertEquals("decisions", database.read(reader -> reader.nearestVectors("toy", new float[]{0, 1, 1}, 1, Optional.empty(),
                Consistency.FRESH)).getFirst().view());
        assertThrows(HStoreException.class, () -> database.write(writer -> writer.vector(region, "people", "toy", 1, new float[]{1, 0})));
        assertThrows(HStoreException.class, () -> database.write(writer -> writer.vector(region, "bad view!", "toy", 1, new float[]{1, 0, 0})));
    }

    @Test
    void nearestVectorsAgreeWithAnExhaustiveScanAndSurviveRestarts() {
        Random random = new Random(3);
        List<float[]> stored = new ArrayList<>();
        List<Long> owners = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            float[] vector = {random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f};
            int index = i;
            long owner = database.write(writer -> {
                long node = writer.node("Note", "n" + index);
                writer.vector(node, index % 2 == 0 ? "content" : "summary", "toy4", 1, vector);
                return node;
            });
            stored.add(vector);
            owners.add(owner);
        }
        for (int round = 0; round < 2; round++) {
            for (int probe = 0; probe < 20; probe++) {
                float[] query = {random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f};
                List<Long> want = new ArrayList<>();
                for (int i = 0; i < stored.size(); i++) {
                    if (i % 2 == 0) {
                        want.add((long) i);
                    }
                }
                want.sort(Comparator.comparingDouble(i -> Embedding.cosineDistance(Embedding.normalized(query), Embedding.normalized(stored.get(i.intValue())))));
                List<Long> expected = want.subList(0, 5).stream().map(i -> owners.get(i.intValue())).toList();
                List<Long> got = database.read(reader -> reader.nearestVectors("toy4", query, 5, Optional.of("content"), Consistency.FRESH))
                        .stream().map(VectorRecord.Hit::owner).toList();
                assertEquals(expected, got);
            }
            database.close();
            database = reopen();
        }
    }

    @Test
    void sectionsAreEmbeddedOnRequestAndPurgedWithTheirDocument() {
        long note = database.write(writer -> writer.node("Note", "plan"));
        Revision revision = database.write(writer -> writer.appendRevision(note, "# Plan\nUse HStore.\n## Why\nFewer joins.\n",
                RevisionSpec.DEFAULT.embedded()));
        List<Section> sections = database.read(reader -> reader.sections(revision.id()));
        String model = database.semantic().encoder().model();
        for (Section section : sections) {
            List<VectorRecord> vectors = database.read(reader -> reader.vectors(section.id()));
            assertEquals(List.of("content"), vectors.stream().map(VectorRecord::view).toList());
        }
        List<VectorRecord.Hit> hits = database.read(reader -> reader.nearestVectors(model,
                database.semantic().encoder().encode("Fewer joins").vector(), 1, Optional.of("content"), Consistency.FRESH));
        assertEquals(sections.get(1).id(), hits.getFirst().owner());
        database.write(writer -> writer.vector(note, "summary", model, 1, database.semantic().encoder().encode("plan summary").vector()));
        database.write(writer -> writer.purge(note));
        assertTrue(database.read(reader -> reader.nearestVectors(model, database.semantic().encoder().encode("Fewer joins").vector(), 5,
                Optional.empty(), Consistency.FRESH)).isEmpty());
        assertEquals(0L, (long) database.read(reader -> reader.engineView().scan(VectorRecord.VECTORS).size()));
    }
}
