// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.MemberSpec;
import io.hstore.db.Reader;
import io.hstore.db.query.Session;
import io.hstore.db.security.Principal;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.Branch;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignatureIndexTest {

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
                    CREATE NODE TYPE Document;
                    CREATE SET EDGE TYPE Review;
                    CREATE SET EDGE TYPE Bundle;
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

    private void churn(RandomGenerator random, int operations) {
        for (Principal tenant : tenants) {
            List<Long> nodes = new ArrayList<>();
            List<Long> edges = new ArrayList<>();
            Map<Long, Set<Long>> members = new HashMap<>();
            database.write(TxnOptions.defaults(), tenant, writer -> {
                for (int i = 0; i < 30; i++) {
                    nodes.add(writer.node(i % 3 == 0 ? "Document" : "Person", "n" + i));
                }
                return null;
            });
            for (int op = 0; op < operations; op++) {
                database.write(TxnOptions.defaults(), tenant, writer -> {
                    int choice = edges.isEmpty() ? 0 : random.nextInt(12);
                    if (choice < 2) {
                        long edge = writer.edge(random.nextBoolean() ? "Review" : "Bundle");
                        edges.add(edge);
                        members.put(edge, new LinkedHashSet<>());
                    } else if (choice < 7) {
                        long edge = edges.get(random.nextInt(edges.size()));
                        long member = random.nextInt(5) == 0 && edges.size() > 1
                                ? edges.get(random.nextInt(edges.size()))
                                : nodes.get(random.nextInt(nodes.size()));
                        if (member != edge) {
                            writer.add(edge, member, MemberSpec.PLAIN);
                            members.get(edge).add(member);
                        }
                    } else if (choice < 10) {
                        long edge = edges.get(random.nextInt(edges.size()));
                        List<Long> current = List.copyOf(members.get(edge));
                        if (!current.isEmpty()) {
                            long member = current.get(random.nextInt(current.size()));
                            writer.remove(edge, member);
                            members.get(edge).remove(member);
                        }
                    } else if (choice < 11 && nodes.size() > 5) {
                        long node = nodes.remove(random.nextInt(nodes.size()));
                        writer.delete(node);
                        members.values().forEach(set -> set.remove(node));
                    } else {
                        long edge = edges.remove(random.nextInt(edges.size()));
                        writer.delete(edge);
                        members.remove(edge);
                        members.values().forEach(set -> set.remove(edge));
                    }
                    return null;
                });
            }
        }
    }

    private static Optional<Signature> expected(Reader reader, long edge) {
        int edgeType = reader.engineView().atom(edge).orElseThrow().type();
        TreeMap<Integer, Integer> labels = new TreeMap<>();
        reader.members(edge).forEach(member -> labels.merge(reader.engineView().atom(member.atom()).orElseThrow().type(), 1, Integer::sum));
        if (labels.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Signature(edgeType, labels.keySet().stream().mapToInt(Integer::intValue).toArray(),
                labels.values().stream().mapToInt(Integer::intValue).toArray()));
    }

    private void compareWithBruteForce() {
        for (Principal tenant : tenants) {
            database.read(Branch.MAIN, tenant, reader -> {
                Map<Signature, TreeSet<Long>> bySignature = new HashMap<>();
                for (String type : List.of("Review", "Bundle")) {
                    reader.atomsOfType(reader.requireReadableType(type).id()).forEach(edge -> {
                        Optional<Signature> want = expected(reader, edge);
                        assertEquals(want, Signatures.of(reader.engineView(), edge), "edge " + edge);
                        want.ifPresent(signature -> bySignature.computeIfAbsent(signature, _ -> new TreeSet<>()).add(edge));
                    });
                }
                assertFalse(bySignature.isEmpty());
                bySignature.forEach((signature, edges) -> {
                    assertEquals(List.copyOf(edges), Signatures.edges(reader.engineView(), reader.tenant(), signature).boxed().toList(), signature.toString());
                    assertEquals(edges.size(), Signatures.count(reader.engineView(), reader.tenant(), signature), signature.toString());
                    assertFalse(Signatures.collides(reader.engineView(), reader.tenant(), signature));
                });
                return null;
            });
        }
    }

    @Test
    void signaturesMatchMembersAfterRandomChurn() {
        churn(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(20261123L), 1200);
        compareWithBruteForce();
    }

    @Test
    void anEdgeWithNoMembersLeavesAllThreeSlots() {
        long[] ids = database.write(TxnOptions.defaults(), tenants.getFirst(), writer -> {
            long edge = writer.edge("Review");
            long person = writer.node("Person", "a");
            writer.add(edge, person, MemberSpec.PLAIN);
            return new long[]{edge, person};
        });
        Signature one = database.read(Branch.MAIN, tenants.getFirst(), reader -> Signatures.of(reader.engineView(), ids[0]).orElseThrow());
        database.write(TxnOptions.defaults(), tenants.getFirst(), writer -> {
            writer.remove(ids[0], ids[1]);
            return null;
        });
        database.read(Branch.MAIN, tenants.getFirst(), reader -> {
            assertEquals(Optional.empty(), Signatures.of(reader.engineView(), ids[0]));
            assertEquals(List.of(), Signatures.edges(reader.engineView(), reader.tenant(), one).boxed().toList());
            assertEquals(0, Signatures.count(reader.engineView(), reader.tenant(), one));
            return null;
        });
    }

    @Test
    void tenantsWithTheSameSignatureDoNotShareKeys() {
        for (Principal tenant : tenants) {
            database.write(TxnOptions.defaults(), tenant, writer -> {
                writer.add(writer.edge("Review"), writer.node("Person", "a"), MemberSpec.PLAIN);
                return null;
            });
        }
        Signature acme = database.read(Branch.MAIN, tenants.get(0), reader -> {
            long edge = reader.atomsOfType(reader.requireReadableType("Review").id()).findFirst().orElseThrow();
            return Signatures.of(reader.engineView(), edge).orElseThrow();
        });
        database.read(Branch.MAIN, tenants.get(1), reader -> {
            assertEquals(1, Signatures.count(reader.engineView(), reader.tenant(), acme));
            assertEquals(1, Signatures.edges(reader.engineView(), reader.tenant(), acme).count());
            return null;
        });
        int[] ids = tenants.stream().mapToInt(principal -> database.read(Branch.MAIN, principal, Reader::tenant)).toArray();
        assertNotEquals(SignatureSlots.signatureKey(ids[0], acme), SignatureSlots.signatureKey(ids[1], acme));
    }

    @Test
    void forcedCollisionIsReportedAndStaysExact() {
        long edge = database.write(TxnOptions.defaults(), tenants.getFirst(), writer -> {
            long created = writer.edge("Review");
            writer.add(created, writer.node("Person", "a"), MemberSpec.PLAIN);
            return created;
        });
        Signature real = database.read(Branch.MAIN, tenants.getFirst(), reader -> Signatures.of(reader.engineView(), edge).orElseThrow());
        int tenant = database.read(Branch.MAIN, tenants.getFirst(), Reader::tenant);
        Signature impostor = new Signature(real.edgeType(), new int[]{real.labels()[0] + 1000}, new int[]{3});
        long key = SignatureSlots.signatureKey(tenant, real);
        database.write(writer -> {
            writer.transaction().apply("inject a colliding signature name", workspace -> workspace.write(SignatureSlots.NAMES, key,
                    names -> Optional.of(SignatureSlots.withName(names.orElseThrow(), new SignatureSlots.SignatureName(tenant, impostor, 1)))));
            return null;
        });
        database.read(Branch.MAIN, tenants.getFirst(), reader -> {
            assertTrue(Signatures.collides(reader.engineView(), reader.tenant(), real));
            assertEquals(List.of(edge), Signatures.edges(reader.engineView(), reader.tenant(), real).boxed().toList());
            assertEquals(1, Signatures.count(reader.engineView(), reader.tenant(), real));
            return null;
        });
    }

    @Test
    void mergedBranchesKeepSignaturesAndChildLinksExact() {
        try (Session admin = new Session(database)) {
            admin.executeAll("""
                    INSERT NODE Person 'a' AS $a;
                    INSERT EDGE Review MEMBERS ($a) AS $r;
                    INSERT EDGE Bundle AS $b;
                    CREATE BRANCH side;
                    USE BRANCH side;
                    INSERT NODE Document 'd' AS $d;
                    ADD $d TO $r;
                    INSERT EDGE Review MEMBERS ($d) AS $child;
                    ADD $child TO $b;
                    USE MAIN;
                    MERGE BRANCH side;
                    """);
            long review = admin.variables().get("r");
            long bundle = admin.variables().get("b");
            long child = admin.variables().get("child");
            database.read(reader -> {
                assertEquals(expected(reader, review), Signatures.of(reader.engineView(), review));
                assertEquals(expected(reader, bundle), Signatures.of(reader.engineView(), bundle));
                assertEquals(List.of(child), io.hstore.db.hora.Nesting.children(reader.engineView(), bundle).map(entry -> entry.key()).toList());
                Signature mixed = expected(reader, review).orElseThrow();
                assertEquals(List.of(review), Signatures.edges(reader.engineView(), reader.tenant(), mixed).boxed().toList());
                return null;
            });
        }
    }

    @Test
    void mergeSkipsMembersTheTargetDeleted() {
        for (String policy : List.of("FAIL", "TARGET", "SOURCE")) {
            try (Session admin = new Session(database)) {
                admin.executeAll("""
                        INSERT NODE Person 'n-%1$s' AS $n;
                        INSERT EDGE Review AS $e;
                        CREATE BRANCH b%1$s;
                        USE BRANCH b%1$s;
                        ADD $n TO $e;
                        USE MAIN;
                        DELETE $n;
                        """.formatted(policy));
                long edge = admin.variables().get("e");
                if (policy.equals("FAIL")) {
                    HStoreException refused = assertThrows(HStoreException.class, () -> admin.execute("MERGE BRANCH b" + policy));
                    assertTrue(refused.getMessage().contains("conflict"), refused.getMessage());
                    continue;
                }
                admin.execute("MERGE BRANCH b" + policy + " ON CONFLICT " + policy);
                database.read(reader -> {
                    assertEquals(0, reader.members(edge).count(), policy);
                    assertEquals(Optional.empty(), Signatures.of(reader.engineView(), edge), policy);
                    return null;
                });
            }
        }
    }

    @Test
    void aMemberDeletedUnderAnOpenTransactionIsAConflict() {
        try (Session first = new Session(database); Session second = new Session(database)) {
            first.executeAll("INSERT NODE Person 'gone' AS $n; INSERT EDGE Review AS $e;");
            long node = first.variables().get("n");
            first.executeAll("BEGIN; ADD $n TO $e;");
            second.execute("DELETE @" + node);
            assertThrows(HStoreException.Conflict.class, () -> first.execute("COMMIT"));
        }
    }

    @Test
    void slotsSurviveRestart() {
        churn(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(20261123L), 300);
        database.close();
        database = HypergraphDatabase.open(directory, options());
        compareWithBruteForce();
    }
}
