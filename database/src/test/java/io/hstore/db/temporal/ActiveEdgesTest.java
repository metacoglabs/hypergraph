// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.temporal;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.MemberSpec;
import io.hstore.db.query.Session;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.page.IoTrace;
import io.hstore.engine.topology.Validity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveEdgesTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults()
                .withEngine(EngineOptions.defaults().withPageSize(4096)));
        try (Session session = new Session(database)) {
            session.executeAll("""
                    CREATE NODE TYPE Person;
                    CREATE SET EDGE TYPE Collaboration ROLES (lead, member);
                    """);
        }
    }

    @AfterEach
    void close() {
        database.close();
    }

    private static Validity randomInterval(RandomGenerator random) {
        long from = random.nextInt(200);
        return switch (random.nextInt(4)) {
            case 0 -> Validity.ALWAYS;
            case 1 -> new Validity(from, Long.MAX_VALUE);
            case 2 -> new Validity(Long.MIN_VALUE, from + 1);
            default -> new Validity(from, from + 1 + random.nextInt(60));
        };
    }

    @Test
    void activeEdgesAgreeWithMemberReadsOnRandomIntervals() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(20261123L);
        long hub = database.write(writer -> {
            long person = writer.node("Person", "hub");
            for (int i = 0; i < 400; i++) {
                long edge = writer.edge("Collaboration");
                writer.add(edge, person, MemberSpec.roles(random.nextBoolean() ? "lead" : "member").withValidity(randomInterval(random)));
                if (random.nextBoolean()) {
                    writer.add(edge, writer.node("Person", "p" + i), MemberSpec.PLAIN);
                }
            }
            return person;
        });
        database.read(reader -> {
            List<Long> edges = reader.incidentEdges(hub).map(incident -> incident.edge()).toList();
            for (int q = 0; q < 300; q++) {
                long instant = random.nextInt(280) - 20;
                List<Long> expected = new ArrayList<>(edges.stream()
                        .filter(edge -> reader.incidence(edge, hub).orElseThrow().validAt(instant)).sorted().toList());
                assertEquals(expected, reader.incidentAt(hub, instant).sorted().boxed().toList(), "instant " + instant);
                assertEquals(expected, Temporal.activeEdges(reader, hub, instant).sorted().boxed().toList(), "instant " + instant);
            }
            return null;
        });
    }

    @Test
    void promotedPostingsSkipSubtreesOutsideTheInstant() {
        long hub = database.write(writer -> {
            long person = writer.node("Person", "hub");
            for (int i = 0; i < 5000; i++) {
                writer.add(writer.edge("Collaboration"), person, MemberSpec.PLAIN.withValidity(new Validity(i, i + 1)));
            }
            return person;
        });
        database.close();
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults()
                .withEngine(EngineOptions.defaults().withPageSize(4096)));
        database.read(reader -> {
            IoTrace narrow = IoTrace.unbounded();
            long found = narrow.call(() -> reader.incidentAt(hub, 10).count());
            IoTrace full = IoTrace.unbounded();
            long all = full.call(() -> reader.incidentEdges(hub).count());
            assertEquals(1, found);
            assertEquals(5000, all);
            assertTrue(narrow.pageVisits() * 5 < full.pageVisits(), narrow.pageVisits() + " vs " + full.pageVisits());
            return null;
        });
    }
}
