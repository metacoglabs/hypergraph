// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.temporal;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.MemberSpec;
import io.hstore.db.Reader;
import io.hstore.db.query.Session;
import io.hstore.db.security.Principal;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.topology.Validity;
import io.hstore.engine.txn.TxnOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidityIndexTest {

    private static final List<String> TYPES = List.of("Collaboration", "Meeting", "Review");

    @TempDir
    Path directory;

    HypergraphDatabase database;
    List<Principal> tenants = new ArrayList<>();

    private static DatabaseOptions options() {
        return DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
    }

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, options());
        try (Session admin = new Session(database)) {
            admin.executeAll("""
                    CREATE NODE TYPE Person;
                    CREATE SET EDGE TYPE Collaboration;
                    CREATE SET EDGE TYPE Meeting;
                    CREATE SET EDGE TYPE Review;
                    CREATE TENANT acme;
                    CREATE TENANT globex;
                    CREATE USER alice PASSWORD 's3cret-pass' TENANT acme ROLE writer;
                    CREATE USER bob PASSWORD 'hunter2-pass' TENANT globex ROLE writer;
                    """);
        }
        tenants.add(principal("alice", "s3cret-pass"));
        tenants.add(principal("bob", "hunter2-pass"));
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Principal principal(String user, String password) {
        try (Session session = new Session(database)) {
            assertTrue(session.authenticate(user, password));
            return session.principal();
        }
    }

    private static Validity randomInterval(RandomGenerator random) {
        long from = random.nextInt(100);
        return switch (random.nextInt(5)) {
            case 0, 1 -> Validity.ALWAYS;
            case 2 -> new Validity(from, Long.MAX_VALUE);
            case 3 -> new Validity(Long.MIN_VALUE, from + 1);
            default -> new Validity(from, from + 1 + random.nextInt(40));
        };
    }

    private void churn(RandomGenerator random, int operations) {
        for (Principal tenant : tenants) {
            List<Long> people = new ArrayList<>();
            List<Long> edges = new ArrayList<>();
            Map<Long, Set<Long>> members = new HashMap<>();
            database.write(TxnOptions.defaults(), tenant, writer -> {
                for (int i = 0; i < 40; i++) {
                    people.add(writer.node("Person", "p" + i));
                }
                return null;
            });
            for (int op = 0; op < operations; op++) {
                database.write(TxnOptions.defaults(), tenant, writer -> {
                    int choice = edges.isEmpty() ? 0 : random.nextInt(10);
                    if (choice < 2) {
                        long edge = writer.edge(TYPES.get(random.nextInt(TYPES.size())));
                        edges.add(edge);
                        members.put(edge, new LinkedHashSet<>());
                    } else if (choice < 7) {
                        long edge = edges.get(random.nextInt(edges.size()));
                        long person = people.get(random.nextInt(people.size()));
                        writer.add(edge, person, MemberSpec.PLAIN.withValidity(randomInterval(random)));
                        members.get(edge).add(person);
                    } else if (choice < 9) {
                        long edge = edges.get(random.nextInt(edges.size()));
                        List<Long> current = List.copyOf(members.get(edge));
                        if (!current.isEmpty()) {
                            long person = current.get(random.nextInt(current.size()));
                            writer.remove(edge, person);
                            members.get(edge).remove(person);
                        }
                    } else {
                        long edge = edges.remove(random.nextInt(edges.size()));
                        writer.delete(edge);
                        members.remove(edge);
                    }
                    return null;
                });
            }
        }
    }

    private static List<Long> bruteForce(Reader reader, int type, int side, long low, long high) {
        TreeSet<Long> found = new TreeSet<>();
        reader.atomsOfType(type).forEach(edge -> reader.members(edge).forEach(member -> {
            long endpoint = side == 0 ? member.validity().from() : member.validity().to();
            boolean open = side == 0 ? endpoint == Long.MIN_VALUE : endpoint == Long.MAX_VALUE;
            if (!open && endpoint > low && endpoint <= high) {
                found.add(edge);
            }
        }));
        return List.copyOf(found);
    }

    private static long bruteCount(Reader reader, int type, int side, long low, long high) {
        TreeSet<String> entries = new TreeSet<>();
        reader.atomsOfType(type).forEach(edge -> reader.members(edge).forEach(member -> {
            long endpoint = side == 0 ? member.validity().from() : member.validity().to();
            boolean open = side == 0 ? endpoint == Long.MIN_VALUE : endpoint == Long.MAX_VALUE;
            if (!open && endpoint > low && endpoint <= high) {
                entries.add(endpoint + "/" + edge);
            }
        }));
        return entries.size();
    }

    private void compareWithBruteForce(RandomGenerator random) {
        for (Principal tenant : tenants) {
            database.read(Branch.MAIN, tenant, reader -> {
                for (String name : TYPES) {
                    int type = reader.requireReadableType(name).id();
                    for (int side = 0; side < 2; side++) {
                        for (int q = 0; q < 60; q++) {
                            long low = random.nextInt(160) - 20;
                            long high = low + random.nextInt(60);
                            String where = name + " side " + side + " (" + low + ", " + high + "]";
                            assertEquals(bruteForce(reader, type, side, low, high),
                                    ValidityIndex.edges(reader.engineView(), reader.tenant(), type, side, low, high).boxed().toList(), where);
                            assertEquals(bruteCount(reader, type, side, low, high),
                                    ValidityIndex.count(reader.engineView(), reader.tenant(), type, side, low, high), where);
                        }
                    }
                }
                return null;
            });
        }
    }

    @Test
    void endpointsMatchABruteForceListAfterRandomChurn() {
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        churn(random, 1000);
        compareWithBruteForce(random);
    }

    @Test
    void defaultIntervalsAreNeverIndexed() {
        database.write(TxnOptions.defaults(), tenants.getFirst(), writer -> {
            long edge = writer.edge("Meeting");
            writer.add(edge, writer.node("Person", "a"), MemberSpec.PLAIN);
            writer.add(edge, writer.node("Person", "b"), MemberSpec.PLAIN);
            return null;
        });
        database.read(Branch.MAIN, tenants.getFirst(), reader -> {
            int type = reader.requireReadableType("Meeting").id();
            for (int side = 0; side < 2; side++) {
                assertEquals(0, ValidityIndex.count(reader.engineView(), reader.tenant(), type, side, Long.MIN_VALUE, Long.MAX_VALUE - 1));
            }
            return null;
        });
    }

    @Test
    void closingAnOpenIntervalAddsOnlyTheNewEnd() {
        long[] ids = database.write(TxnOptions.defaults(), tenants.getFirst(), writer -> {
            long edge = writer.edge("Meeting");
            long person = writer.node("Person", "a");
            writer.add(edge, person, MemberSpec.PLAIN.withValidity(new Validity(5, Long.MAX_VALUE)));
            return new long[]{edge, person};
        });
        database.write(TxnOptions.defaults(), tenants.getFirst(), writer -> {
            writer.add(ids[0], ids[1], MemberSpec.PLAIN.withValidity(new Validity(5, 9)));
            return null;
        });
        database.read(Branch.MAIN, tenants.getFirst(), reader -> {
            int type = reader.requireReadableType("Meeting").id();
            assertEquals(List.of(ids[0]), ValidityIndex.edges(reader.engineView(), reader.tenant(), type, 0, 4, 5).boxed().toList());
            assertEquals(List.of(ids[0]), ValidityIndex.edges(reader.engineView(), reader.tenant(), type, 1, 8, 9).boxed().toList());
            assertEquals(1, ValidityIndex.count(reader.engineView(), reader.tenant(), type, 0, Long.MIN_VALUE, Long.MAX_VALUE - 1));
            assertEquals(1, ValidityIndex.count(reader.engineView(), reader.tenant(), type, 1, Long.MIN_VALUE, Long.MAX_VALUE - 1));
            return null;
        });
    }

    @Test
    void otherTenantsEndpointsStayOut() {
        database.write(TxnOptions.defaults(), tenants.get(1), writer -> {
            writer.add(writer.edge("Meeting"), writer.node("Person", "x"), MemberSpec.PLAIN.withValidity(new Validity(1, 2)));
            return null;
        });
        database.read(Branch.MAIN, tenants.getFirst(), reader -> {
            int type = reader.requireReadableType("Meeting").id();
            assertEquals(List.of(), ValidityIndex.edges(reader.engineView(), reader.tenant(), type, 1, 0, 10).boxed().toList());
            return null;
        });
    }

    @Test
    void indexSurvivesRestart() {
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        churn(random, 300);
        database.close();
        database = HypergraphDatabase.open(directory, options());
        compareWithBruteForce(random);
    }
}
