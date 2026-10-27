// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db;

import io.hstore.db.schema.AtomKind;
import io.hstore.db.schema.TypeDef.PropertyDef;
import io.hstore.db.value.Json;
import io.hstore.db.value.TypeTag;
import io.hstore.db.value.Value;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.topology.Validity;
import io.hstore.engine.txn.TxnOptions;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeLogReplayTest {

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3})
    void replayingTheChangeLogRebuildsPropertiesIndexesAndDocuments(long seed) {
        Random random = new Random(seed);
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, DatabaseOptions.defaults()
                .withEngine(EngineOptions.defaults().withPageSize(4096).withHistoryLimit(100_000).withCheckpointWalBytes(Long.MAX_VALUE)))) {
            long first = database.engine().stats().generation();
            long logged = database.engine().verifyChangeLog().commits();
            database.write(writer -> {
                writer.defineNode("Person", List.of(new PropertyDef("name", TypeTag.STRING, true, false),
                        new PropertyDef("age", TypeTag.INT, false, false)));
                writer.defineNode("Paper", List.of(new PropertyDef("title", TypeTag.STRING, false, false)));
                writer.defineEdge("Coauthorship", AtomKind.SET_EDGE, List.of(new PropertyDef("year", TypeTag.INT, true, false)),
                        List.of("lead", "author"));
                writer.defineEdge("Route", AtomKind.ORDERED_EDGE, List.of(), List.of());
                return null;
            });
            List<Long> people = new ArrayList<>();
            List<Long> papers = new ArrayList<>();
            List<Long> sets = new ArrayList<>();
            List<Long> routes = new ArrayList<>();
            int branchChanges = 0;
            for (int round = 0; round < 200; round++) {
                int choice = random.nextInt(14);
                if (round == 80) {
                    database.write(writer -> writer.createIndex("Person", "age"));
                } else if (round == 120) {
                    database.write(writer -> {
                        writer.createJsonIndex("Paper", "$.venue.year", TypeTag.INT);
                        return writer.createJsonIndex("Paper", "$.tags[*]", TypeTag.STRING);
                    });
                } else if (choice <= 2 || people.size() < 4 || papers.size() < 2) {
                    int id = round;
                    database.write(writer -> {
                        people.add(writer.node("Person", "p" + id, Map.of("name", "person " + id, "age", random.nextInt(90))));
                        papers.add(writer.node("Paper", "q" + id, Map.of("title", "paper " + id)));
                        return null;
                    });
                } else if (choice == 3) {
                    long person = pick(random, people);
                    database.write(writer -> {
                        writer.set(person, "age", new Value.Int(random.nextInt(90)));
                        writer.set(person, "name", new Value.Text("renamed " + random.nextInt(1000)), new Validity(random.nextInt(50), 100 + random.nextInt(50)));
                        return null;
                    });
                } else if (choice == 4) {
                    long person = pick(random, people);
                    database.write(writer -> {
                        writer.unset(person, "age");
                        return null;
                    });
                } else if (choice == 5) {
                    long paper = pick(random, papers);
                    database.write(writer -> {
                        writer.document(paper, Json.parse("{\"venue\":{\"year\":" + (2000 + random.nextInt(30)) + "},\"tags\":[\"t"
                                + random.nextInt(5) + "\",\"t" + random.nextInt(5) + "\"]}"));
                        return null;
                    });
                } else if (choice == 6) {
                    database.write(writer -> {
                        long edge = writer.edge("Coauthorship", Map.of("year", 2000 + random.nextInt(30)));
                        writer.add(edge, pick(random, people), MemberSpec.roles("lead").withWeight(random.nextInt(4)));
                        sets.add(edge);
                        long route = writer.edge("Route");
                        writer.append(route, pick(random, people), MemberSpec.PLAIN);
                        routes.add(route);
                        return null;
                    });
                } else if (choice == 7 && !sets.isEmpty()) {
                    long edge = pick(random, sets);
                    database.write(writer -> {
                        for (long person : people.subList(0, Math.min(people.size(), 1 + random.nextInt(40)))) {
                            if (random.nextBoolean()) {
                                writer.add(edge, person, MemberSpec.roles("author").withValidity(new Validity(0, 10 + random.nextInt(100))));
                            }
                        }
                        return null;
                    });
                } else if (choice == 8 && !routes.isEmpty()) {
                    long route = pick(random, routes);
                    int id = round;
                    database.write(writer -> {
                        for (int i = 0; i < 40; i++) {
                            writer.insertAt(route, 0, writer.node("Person", "r" + id + "-" + i, Map.of()), MemberSpec.PLAIN);
                        }
                        return null;
                    });
                } else if (choice == 9 && sets.size() > 2) {
                    long edge = sets.remove(random.nextInt(sets.size()));
                    database.write(writer -> {
                        writer.delete(edge);
                        return null;
                    });
                } else if (choice == 10) {
                    long start = 1_000 + round * 10_000L;
                    database.write(writer -> {
                        List<Long> members = LongStream.range(0, 30).mapToObj(i -> writer.node("Person", "bulk" + start + "-" + i, Map.of())).toList();
                        long edge = writer.edge("Coauthorship", Map.of("year", 1999));
                        writer.load(edge, members, MemberSpec.roles("author"));
                        sets.add(edge);
                        return null;
                    });
                } else if (choice == 11) {
                    Branch branch = database.engine().createBranch("b" + round, Branch.MAIN);
                    long person = pick(random, people);
                    database.engine().write(TxnOptions.defaults().onBranch(branch.id()), txn -> {
                        txn.delete(person);
                        return null;
                    });
                    branchChanges++;
                    if (random.nextBoolean()) {
                        database.engine().dropBranch(branch.id());
                        branchChanges++;
                    }
                } else if (choice == 12) {
                    long paper = pick(random, papers);
                    database.write(writer -> {
                        writer.embed(paper, "storage engines and graph queries " + random.nextInt(100));
                        return null;
                    });
                } else {
                    long person = people.remove(random.nextInt(people.size()));
                    database.write(writer -> {
                        writer.delete(person);
                        return null;
                    });
                }
            }
            long commits = database.engine().stats().generation() - first - branchChanges;
            assertEquals(logged + commits, database.engine().verifyChangeLog().commits());
            assertTrue(commits > 150);
        }
    }

    private static long pick(Random random, List<Long> atoms) {
        return atoms.get(random.nextInt(atoms.size()));
    }
}
