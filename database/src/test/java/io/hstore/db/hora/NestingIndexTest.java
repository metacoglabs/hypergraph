// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.MemberSpec;
import io.hstore.db.hora.NestingSlots.ChildLink;
import io.hstore.db.query.Session;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.Validity;
import io.hstore.engine.tree.Entry;
import io.hstore.engine.tree.Summary;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestingIndexTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;

    private static DatabaseOptions options() {
        return DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
    }

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, options());
        try (Session admin = new Session(database)) {
            admin.executeAll("""
                    CREATE NODE TYPE Person;
                    CREATE SET EDGE TYPE Group ROLES (subject, member, chair);
                    """);
        }
    }

    @AfterEach
    void close() {
        database.close();
    }

    private void compare() {
        database.read(reader -> {
            reader.atomsOfType(reader.requireReadableType("Group").id()).forEach(parent -> {
                Map<Long, ChildLink> expected = new TreeMap<>();
                reader.engineView().edge(parent).orElseThrow().members().stream().forEach(entry -> {
                    Incidence incidence = entry.value();
                    if (reader.engineView().atom(incidence.member()).orElseThrow() instanceof io.hstore.engine.catalog.AtomRecord.EdgeRecord) {
                        expected.put(incidence.member(), ChildLink.of(incidence, entry.key()));
                    }
                });
                Map<Long, ChildLink> actual = new TreeMap<>();
                Nesting.children(reader.engineView(), parent).forEach(entry -> actual.put(entry.key(), entry.value()));
                assertEquals(expected, actual, "parent " + parent);
            });
            return null;
        });
    }

    private void churn(RandomGenerator random, int operations) {
        List<Long> groups = new ArrayList<>();
        List<Long> people = new ArrayList<>();
        database.write(writer -> {
            for (int i = 0; i < 20; i++) {
                people.add(writer.node("Person", "p" + i));
            }
            for (int i = 0; i < 10; i++) {
                groups.add(writer.edge("Group"));
            }
            return null;
        });
        for (int op = 0; op < operations; op++) {
            database.write(writer -> {
                int choice = random.nextInt(12);
                List<String> roles = List.of("subject", "member", "chair");
                MemberSpec spec = MemberSpec.roles(roles.get(random.nextInt(3))).withWeight(random.nextInt(10) / 10.0)
                        .withValidity(random.nextBoolean() ? Validity.ALWAYS : new Validity(random.nextInt(50), 60 + random.nextInt(50)));
                if (choice < 2) {
                    groups.add(writer.edge("Group"));
                } else if (choice < 6) {
                    long parent = groups.get(random.nextInt(groups.size()));
                    long child = groups.get(random.nextInt(groups.size()));
                    if (parent != child) {
                        writer.add(parent, child, spec);
                    }
                } else if (choice < 8) {
                    writer.add(groups.get(random.nextInt(groups.size())), people.get(random.nextInt(people.size())), spec);
                } else if (choice < 10) {
                    long parent = groups.get(random.nextInt(groups.size()));
                    List<Long> members = writer.transaction().edge(parent).orElseThrow().members().stream().map(Entry::key).toList();
                    if (!members.isEmpty()) {
                        writer.remove(parent, members.get(random.nextInt(members.size())));
                    }
                } else if (choice < 11 && groups.size() > 4) {
                    writer.delete(groups.remove(random.nextInt(groups.size())));
                } else if (people.size() > 4) {
                    writer.delete(people.remove(random.nextInt(people.size())));
                }
                return null;
            });
        }
    }

    @Test
    void childLinksMatchMembersAfterRandomChurn() {
        churn(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(20261123L), 1500);
        compare();
    }

    @Test
    void nodeMembersNeverEnterTheIndex() {
        long group = database.write(writer -> {
            long created = writer.edge("Group");
            writer.add(created, writer.node("Person", "a"), MemberSpec.roles("member"));
            return created;
        });
        database.read(reader -> {
            assertEquals(0, Nesting.children(reader.engineView(), group).count());
            return null;
        });
    }

    @Test
    void promotedChildTreeSummaryPrunesByRoleWeightAndTime() {
        long parent = database.write(writer -> {
            long created = writer.edge("Group");
            for (int i = 0; i < 80; i++) {
                writer.add(created, writer.edge("Group"), MemberSpec.roles(i % 2 == 0 ? "subject" : "member")
                        .withWeight(0.1 + (i % 5) / 10.0).withValidity(new Validity(i, i + 5)));
            }
            return created;
        });
        database.read(reader -> {
            Summary summary = Nesting.tree(reader.engineView(), parent).summary();
            assertEquals(80, Nesting.children(reader.engineView(), parent).count());
            assertEquals(0, summary.timeMin());
            assertEquals(84, summary.timeMax());
            assertFalse(summary.mayBeValidAt(85));
            int chair = reader.database().engine().dictionary().roleSetsContaining("chair").stream().findFirst().orElse(-1);
            assertTrue(chair < 0 || !summary.mayHaveRoles(Incidence.roleBit(chair)));
            return null;
        });
    }

    @Test
    void slotSurvivesRestart() {
        churn(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(20261123L), 400);
        database.close();
        database = HypergraphDatabase.open(directory, options());
        compare();
    }
}
