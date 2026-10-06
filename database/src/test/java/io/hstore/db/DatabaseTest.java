// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db;

import io.hstore.db.evidence.AssertionType;
import io.hstore.db.evidence.Evidence;
import io.hstore.db.evidence.EvidencePolicy;
import io.hstore.db.evidence.Provenance;
import io.hstore.db.evidence.Qualifier;
import io.hstore.db.hora.Budget;
import io.hstore.db.hora.Field;
import io.hstore.db.hora.Gathered;
import io.hstore.db.hora.GroupKernel;
import io.hstore.db.hora.Hora;
import io.hstore.db.hora.Pattern;
import io.hstore.db.hora.PatternMatcher;
import io.hstore.db.hora.Reducer;
import io.hstore.db.hora.SwapSampler;
import io.hstore.db.schema.AtomKind;
import io.hstore.db.schema.TypeDef.PropertyDef;
import io.hstore.db.security.Principal;
import io.hstore.db.semantic.SemanticPlane;
import io.hstore.db.temporal.StateBindings;
import io.hstore.db.temporal.Temporal;
import io.hstore.db.value.Json;
import io.hstore.db.value.TypeTag;
import io.hstore.db.value.Value;
import io.hstore.db.view.MaterializedViews;
import io.hstore.db.view.ViewCell;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;
import io.hstore.engine.topology.Validity;
import io.hstore.engine.txn.TxnOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults()
                .withEngine(EngineOptions.defaults().withPageSize(4096).withHistoryLimit(500)));
        database.write(writer -> {
            writer.defineNode("Person", List.of(new PropertyDef("name", TypeTag.STRING, true, true),
                    new PropertyDef("age", TypeTag.INT, true, false)));
            writer.defineNode("Paper", List.of(new PropertyDef("title", TypeTag.STRING, false, false)));
            writer.defineEdge("Coauthorship", AtomKind.SET_EDGE, List.of(new PropertyDef("year", TypeTag.INT, true, false)),
                    List.of("lead", "author"));
            writer.defineEdge("Route", AtomKind.ORDERED_EDGE, List.of(), List.of());
            return null;
        });
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void propertyIndexAgreesWithFullScan() {
        database.write(writer -> {
            for (int i = 0; i < 400; i++) {
                writer.node("Person", "p" + i, Map.of("name", "person " + i, "age", i % 90));
            }
            return null;
        });
        database.read(reader -> {
            for (int age : List.of(0, 17, 45, 89)) {
                Set<Long> indexed = reader.lookup("Person", "age", new Value.Int(age)).boxed().collect(Collectors.toSet());
                Set<Long> scanned = reader.atoms("Person")
                        .filter(atom -> reader.property(atom, "age").orElseThrow().equals(new Value.Int(age)))
                        .boxed().collect(Collectors.toSet());
                assertEquals(scanned, indexed);
            }
            long range = reader.range("Person", "age", Optional.of(new Value.Int(30)), Optional.of(new Value.Real(40.5)), true, false).count();
            assertEquals(54, range);
            assertEquals(1, reader.lookup("Person", "name", new Value.Text("person 123")).count());
            return null;
        });
        database.write(writer -> {
            writer.set(writer.resolve("Person", "p5"), "age", new Value.Int(200));
            return null;
        });
        database.read(reader -> {
            assertEquals(List.of(reader.resolve("Person", "p5")), reader.lookup("Person", "age", new Value.Int(200)).boxed().toList());
            return null;
        });
    }

    @Test
    void membersCarryRolesWeightsAndValidity() {
        long edge = database.write(writer -> {
            long alice = writer.node("Person", "alice", Map.of("name", "Alice"));
            long bob = writer.node("Person", "bob", Map.of("name", "Bob"));
            long collaboration = writer.edge("Coauthorship", Map.of("year", 2024));
            writer.add(collaboration, alice, MemberSpec.roles("lead", "author").withWeight(0.75).withValidity(new Validity(10, 20)));
            writer.add(collaboration, bob, MemberSpec.roles("author"));
            return collaboration;
        });
        assertThrows(HStoreException.InvalidSchema.class, () -> database.write(writer -> {
            writer.add(edge, writer.resolve("Person", "bob"), MemberSpec.roles("reviewer"));
            return null;
        }));
        database.read(reader -> {
            long alice = reader.resolve("Person", "alice");
            Member member = reader.members(edge).filter(m -> m.atom() == alice).findFirst().orElseThrow();
            assertEquals(Set.of("lead", "author"), Set.copyOf(member.roles()));
            assertEquals(0.75, member.weight());
            assertEquals(2, reader.members(edge, 15).count());
            assertEquals(1, reader.members(edge, 25).count());
            assertEquals(List.of(edge), reader.incident(alice).map(Incident::edge).toList());
            assertEquals(1.75, Hora.on(reader).reduce(edge, new Field.Weight(), Reducer.SUM, false).orElseThrow(), 1e-9);
            return null;
        });
    }

    @Test
    void documentsAndJsonPathIndexes() {
        database.write(writer -> {
            long paper = writer.node("Paper", "hypergraphs", Map.of("title", "Hypergraphs"));
            writer.document(paper, Json.parse("{\"venue\":{\"name\":\"VLDB\",\"year\":2027},\"tags\":[\"storage\",\"graphs\"]}"));
            writer.createJsonIndex("Paper", "$.venue.year", TypeTag.INT);
            writer.createJsonIndex("Paper", "$.tags[*]", TypeTag.STRING);
            return null;
        });
        database.write(writer -> {
            long other = writer.node("Paper", "olap", Map.of());
            writer.document(other, Json.parse("{\"venue\":{\"year\":2025},\"tags\":[\"olap\"]}"));
            return null;
        });
        database.read(reader -> {
            long paper = reader.resolve("Paper", "hypergraphs");
            assertEquals("VLDB", reader.document(paper).orElseThrow().select("$.venue.name").findFirst().orElseThrow().render().replace("'", ""));
            assertEquals(List.of(paper), reader.range("Paper", "$.venue.year", Optional.of(new Value.Int(2026)), Optional.empty(), true, true)
                    .boxed().toList());
            return null;
        });
    }

    @Test
    void semanticCandidatesAreVerifiedAgainstTheSnapshot() {
        database.write(writer -> {
            writer.embed(writer.node("Paper", "storage", Map.of()), "persistent hypergraph storage engine with copy on write pages");
            writer.embed(writer.node("Paper", "vision", Map.of()), "convolutional networks for image segmentation");
            writer.embed(writer.node("Paper", "graphs", Map.of()), "graph database query processing and storage");
            return null;
        });
        database.read(reader -> {
            List<SemanticPlane.Hit> hits = reader.similar("hypergraph storage engine", 2, SemanticPlane.Consistency.FRESH);
            assertEquals(reader.resolve("Paper", "storage"), hits.getFirst().atom());
            assertFalse(hits.stream().anyMatch(hit -> hit.atom() == reader.resolve("Paper", "vision")));
            return null;
        });
    }

    @Test
    void evidencePoliciesFilterQualifiedIncidences() {
        long[] ids = database.write(writer -> {
            long a = writer.node("Person", "a", Map.of("name", "A"));
            long b = writer.node("Person", "b", Map.of("name", "B"));
            long edge = writer.edge("Coauthorship");
            writer.add(edge, a, MemberSpec.PLAIN);
            writer.add(edge, b, MemberSpec.PLAIN);
            long evidence = Provenance.record(writer, new Evidence(a, 0, 1, "lab", 0, Map.of("instrument", "survey"), 0));
            long assertion = Provenance.qualify(writer, edge, b, Qualifier.of(AssertionType.HYPOTHESIZED, 0.4, List.of(evidence)));
            return new long[]{edge, a, b, assertion};
        });
        database.read(reader -> {
            assertEquals(List.of(ids[1]), Provenance.effective(reader, ids[0], EvidencePolicy.OBSERVED).map(Member::atom).toList());
            assertEquals(2, Provenance.effective(reader, ids[0], EvidencePolicy.ANY).count());
            assertEquals(1, Provenance.effective(reader, ids[0], EvidencePolicy.supported(0.5)).count());
            List<Provenance.Step> trace = Provenance.trace(reader, ids[3], 5);
            assertTrue(trace.getFirst() instanceof Provenance.Step.Assertion);
            assertTrue(trace.stream().anyMatch(step -> step instanceof Provenance.Step.Source(int _, long atom) && atom == ids[1]));
            return null;
        });
    }

    @Test
    void topologyAndStateEvolveAtomically() {
        long generationBefore = database.engine().transactions().current().id();
        long[] ids = database.write(writer -> {
            long a = writer.node("Person", "s1", Map.of("name", "S1"));
            long edge = writer.edge("Coauthorship");
            StateBindings.transition(writer, w -> w.add(edge, a, MemberSpec.PLAIN), Map.of(a, new Value.Real(0.5)), "infection", Validity.since(0));
            return new long[]{edge, a};
        });
        database.read(reader -> {
            assertEquals(new Value.Real(0.5), StateBindings.state(reader, ids[1]).orElseThrow().value());
            assertEquals(1, reader.cardinality(ids[0]));
            assertEquals(new Value.Real(0.5), Hora.on(reader).gather(ids[0], new Field.State()).findFirst().orElseThrow().value());
            return null;
        });
        database.readAt(generationBefore, 0, reader -> {
            assertTrue(StateBindings.state(reader, ids[1]).isEmpty());
            return null;
        });
    }

    @Test
    void higherOrderOperatorsComputeExactResults() {
        long[] edges = database.write(writer -> {
            long[] people = LongStream.range(0, 6).map(i -> writer.node("Person", "h" + i, Map.of("name", "H" + i, "age", 20 + i))).toArray();
            long e1 = writer.edge("Coauthorship");
            long e2 = writer.edge("Coauthorship");
            long e3 = writer.edge("Coauthorship");
            for (int i : new int[]{0, 1, 2, 3}) {
                writer.add(e1, people[i], MemberSpec.PLAIN.withWeight(2.0));
            }
            for (int i : new int[]{2, 3, 4}) {
                writer.add(e2, people[i], MemberSpec.PLAIN);
            }
            for (int i : new int[]{4, 5}) {
                writer.add(e3, people[i], MemberSpec.PLAIN);
            }
            return new long[]{e1, e2, e3, people[0], people[2], people[5]};
        });
        database.read(reader -> {
            Hora hora = Hora.on(reader);
            assertEquals(2, hora.overlap(edges[0], edges[1]));
            assertEquals(2.0 / 5.0, hora.jaccard(edges[0], edges[1]), 1e-12);
            assertEquals(2.0, hora.weightedOverlap(edges[0], edges[1]), 1e-12);
            assertEquals(List.of(new Hora.OverlapPair(edges[0], edges[1], 2)),
                    hora.overlapJoin(List.of(edges[0], edges[1], edges[2]), 2, Budget.DEFAULT).toList());
            assertEquals(4 + 6, hora.closure(edges[0], 1, Budget.DEFAULT).count());
            assertEquals(Set.of(edges[1]), hora.edgeNeighbors(edges[0], 2, Budget.DEFAULT).boxed().collect(Collectors.toSet()));
            assertTrue(hora.adjacent(edges[3], edges[4], 1));
            assertFalse(hora.adjacent(edges[3], edges[5], 1));
            Map<Long, Double> bx = hora.edgeValues(LongStream.of(edges[0]), atom -> 1.0, true);
            assertEquals(8.0, bx.get(edges[0]), 1e-9);
            Map<Long, Double> propagated = hora.propagate(List.of(edges[0], edges[1], edges[2]), atom -> 1.0, edge -> 1.0);
            assertEquals(19.0, propagated.get(edges[4]), 1e-9);
            double meanAge = hora.apply(LongStream.of(edges[0]), new Field.Property("age"),
                            new GroupKernel.TwoPass<Double, Double>(
                                    members -> members.mapToDouble(Gathered::numeric).average().orElse(0),
                                    (mean, members) -> members.mapToDouble(gathered -> Math.abs(gathered.numeric() - mean)).sum()),
                            Budget.DEFAULT)
                    .findFirst().orElseThrow().result();
            assertEquals(4.0, meanAge, 1e-9);
            assertThrows(HStoreException.ResourceLimit.class, () -> hora.apply(LongStream.of(edges[0]), new Field.Weight(),
                    new GroupKernel.Materialize<Integer>(List::size), Budget.DEFAULT.withRows(2)).toList());
            Pattern pattern = new Pattern(List.of(Pattern.Variable.edge("x", "Coauthorship"), Pattern.Variable.edge("y", "Coauthorship"),
                    Pattern.Variable.node("p", "Person")),
                    List.of(new Pattern.Constraint.Contains("x", "p"), new Pattern.Constraint.Contains("y", "p"),
                            new Pattern.Constraint.Distinct("x", "y"), new Pattern.Constraint.Bound("p", edges[4]),
                            new Pattern.Constraint.Shares("x", "y", 2, 2)));
            assertEquals(2, new PatternMatcher(reader).match(pattern, Budget.DEFAULT).count());
            return null;
        });
    }

    @Test
    void swapSamplingPreservesDegreesAndCardinalities() {
        database.write(writer -> {
            long[] people = LongStream.range(0, 30).map(i -> writer.node("Person", "w" + i, Map.of("name", "W" + i))).toArray();
            for (int e = 0; e < 10; e++) {
                long edge = writer.edge("Coauthorship");
                for (int k = 0; k < 4; k++) {
                    writer.add(edge, people[(e * 3 + k * 7) % 30], MemberSpec.PLAIN);
                }
            }
            return null;
        });
        SwapSampler.Sample sample = SwapSampler.run(database, "Coauthorship", 200, 7, "null-model");
        assertTrue(sample.accepted() > 0);
        Map<Long, Long> degrees = database.read(reader -> reader.atoms("Person").boxed()
                .collect(Collectors.toMap(atom -> atom, reader::degree)));
        Map<Long, Long> cardinalities = database.read(reader -> reader.atoms("Coauthorship").boxed()
                .collect(Collectors.toMap(edge -> edge, reader::cardinality)));
        database.read(sample.branch().id(), reader -> {
            degrees.forEach((atom, degree) -> assertEquals(degree, reader.degree(atom)));
            cardinalities.forEach((edge, cardinality) -> assertEquals(cardinality, reader.cardinality(edge)));
            return null;
        });
        long changed = database.engine().read(main -> {
            try (var branch = database.engine().snapshot(sample.branch().id())) {
                return Temporal.diff(database.reader(main), database.reader(branch)).count();
            }
        });
        assertTrue(changed > 0);
    }

    @Test
    void materializedViewsTrackCommittedDeltas() {
        long edge = database.write(writer -> {
            long collaboration = writer.edge("Coauthorship");
            writer.add(collaboration, writer.node("Person", "v1", Map.of("name", "V1")), MemberSpec.PLAIN);
            return collaboration;
        });
        database.views().create(Principal.SYSTEM, "degrees", MaterializedViews.Kind.DEGREE, MaterializedViews.Refresh.ON_DEMAND, 0);
        long second = database.write(writer -> {
            long atom = writer.node("Person", "v2", Map.of("name", "V2"));
            writer.add(edge, atom, MemberSpec.PLAIN);
            return atom;
        });
        assertTrue(database.read(reader -> database.views().read(reader, "degrees", second)).isEmpty());
        database.views().refresh(Principal.SYSTEM, "degrees");
        database.read(reader -> {
            MaterializedViews.Reading reading = database.views().read(reader, "degrees", second).orElseThrow();
            assertEquals(new ViewCell.Count(1), reading.cell());
            assertEquals(0, reading.staleness());
            return null;
        });
    }

    @Test
    void writesOnBranchesStayIsolated() {
        var branch = database.engine().createBranch("what-if", 0);
        database.write(TxnOptions.defaults().onBranch(branch.id()), writer -> writer.node("Person", "ghost", Map.of("name", "Ghost")));
        assertTrue(database.read(reader -> reader.find("Person", "ghost")).isEmpty());
        assertTrue(database.read(branch.id(), reader -> reader.find("Person", "ghost")).isPresent());
    }
}
