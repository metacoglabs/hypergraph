// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.txn;

import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;
import io.hstore.engine.StorageEngine;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.index.Postings;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializableReadsTest {

    private static final int NODE = 1;
    private static final int EDGE = 2;
    private static final int OTHER = 3;
    private static final int FEW = 4;
    private static final TxnOptions SERIALIZABLE = TxnOptions.defaults().withIsolation(Isolation.SERIALIZABLE);
    private static final Slot<Long> OBSERVED = Slot.primary(120, "observed", counters(240));
    private static final Slot<Long> WRITTEN = Slot.primary(121, "written", counters(241));

    @TempDir
    Path directory;

    private int names;

    private static TreeSchema<Long> counters(int id) {
        return new TreeSchema<>(id, "counters-" + id, FingerprintMode.SET,
                ValueCodec.rows(ByteCursor::signedVarLongSize, ByteCursor::putSignedVarLong, ByteCursor::getSignedVarLong),
                (key, value, into) -> into.entry(key, Hashing.of(key, value)));
    }

    private StorageEngine open() {
        return StorageEngine.open(directory, EngineOptions.defaults().withPageSize(1024).withPagesPerSegment(256).withHistoryLimit(1000)
                .withExtensions(List.of(OBSERVED, WRITTEN), List.of()));
    }

    private String name() {
        return "n" + names++;
    }

    private long node(StorageEngine engine, int type) {
        String key = name();
        return engine.write(txn -> txn.createNode(type, key));
    }

    private long joinNewEdge(StorageEngine engine, long member) {
        return engine.write(txn -> {
            long edge = txn.createEdge(EDGE, EdgeKind.SET);
            txn.insert(edge, member);
            return edge;
        });
    }

    private void unrelatedCommit(StorageEngine engine) {
        long key = names++;
        engine.write(txn -> {
            txn.put(WRITTEN, 1_000_000 + key, key);
            long edge = txn.createEdge(EDGE, EdgeKind.SET);
            txn.insert(edge, txn.createNode(OTHER, "unrelated-" + key));
            return null;
        });
    }

    private void ownWrite(Transaction txn) {
        txn.put(WRITTEN, names++, 1L);
    }

    private CommitResult.Outcome commitAfterCheckpoint(StorageEngine engine, Transaction txn) {
        engine.checkpoint();
        return txn.commit().outcome();
    }

    private void assertConflict(StorageEngine engine, Transaction txn, String expected) {
        engine.checkpoint();
        HStoreException.Conflict conflict = assertThrows(HStoreException.Conflict.class, txn::commit);
        assertTrue(conflict.retryable());
        assertTrue(conflict.getMessage().contains(expected), conflict.getMessage());
    }

    private static Map<String, ObjLongConsumer<Transaction>> incidenceReads() {
        Map<String, ObjLongConsumer<Transaction>> reads = new LinkedHashMap<>();
        reads.put("incident", (txn, atom) -> txn.incident(atom).count());
        reads.put("forEachIncidentEdge", (txn, atom) -> txn.forEachIncidentEdge(atom, _ -> {
        }));
        reads.put("incidentTree", (txn, atom) -> txn.incidentTree(atom).size());
        reads.put("degree", Transaction::degree);
        return reads;
    }

    @Test
    void incidenceReadsAreValidatedAcrossACheckpoint() {
        try (StorageEngine engine = open()) {
            long hub = node(engine, NODE);
            long leaf = node(engine, NODE);
            for (int i = 0; i < 60; i++) {
                joinNewEdge(engine, hub);
            }
            joinNewEdge(engine, leaf);
            engine.read(snapshot -> {
                assertInstanceOf(Postings.Promoted.class, snapshot.scan(EngineSlots.REVERSE).get(hub).orElseThrow());
                assertInstanceOf(Postings.Inline.class, snapshot.scan(EngineSlots.REVERSE).get(leaf).orElseThrow());
                return null;
            });
            for (Map.Entry<String, ObjLongConsumer<Transaction>> read : incidenceReads().entrySet()) {
                for (long atom : new long[]{hub, leaf}) {
                    String what = read.getKey() + " of " + atom;
                    joinNewEdge(engine, atom);
                    Transaction unchanged = engine.begin(SERIALIZABLE);
                    read.getValue().accept(unchanged, atom);
                    ownWrite(unchanged);
                    unrelatedCommit(engine);
                    assertEquals(CommitResult.Outcome.REBASED, commitAfterCheckpoint(engine, unchanged), what);

                    Transaction changed = engine.begin(SERIALIZABLE);
                    read.getValue().accept(changed, atom);
                    ownWrite(changed);
                    joinNewEdge(engine, atom);
                    assertConflict(engine, changed, "incident edges of atom " + atom);
                }
            }
        }
    }

    @Test
    void typeIndexReadsAreValidatedAcrossACheckpoint() {
        try (StorageEngine engine = open()) {
            for (int i = 0; i < 40; i++) {
                node(engine, NODE);
            }
            node(engine, FEW);
            Map<String, Consumer<Transaction>> reads = new LinkedHashMap<>();
            for (int type : new int[]{NODE, FEW}) {
                reads.put("atomsOfType " + type, txn -> txn.atomsOfType(0, type).count());
                reads.put("typeTree " + type, txn -> txn.typeTree(0, type).size());
                reads.put("countOfType " + type, txn -> txn.countOfType(0, type));
            }
            engine.read(snapshot -> {
                assertInstanceOf(Postings.Promoted.class, snapshot.scan(EngineSlots.TYPES).get(EngineSlots.typeKey(0, NODE)).orElseThrow());
                assertInstanceOf(Postings.Inline.class, snapshot.scan(EngineSlots.TYPES).get(EngineSlots.typeKey(0, FEW)).orElseThrow());
                return null;
            });
            for (Map.Entry<String, Consumer<Transaction>> read : reads.entrySet()) {
                int type = read.getKey().endsWith(" " + NODE) ? NODE : FEW;
                node(engine, type);
                Transaction unchanged = engine.begin(SERIALIZABLE);
                read.getValue().accept(unchanged);
                ownWrite(unchanged);
                unrelatedCommit(engine);
                assertEquals(CommitResult.Outcome.REBASED, commitAfterCheckpoint(engine, unchanged), read.getKey());

                Transaction changed = engine.begin(SERIALIZABLE);
                read.getValue().accept(changed);
                ownWrite(changed);
                node(engine, type);
                assertConflict(engine, changed, "atoms of type " + type + " in tenant 0");
            }
        }
    }

    @Test
    void canonicalKeyLookupsAreValidatedIncludingAbsentKeys() {
        try (StorageEngine engine = open()) {
            long alice = engine.write(txn -> txn.createNode(NODE, "alice"));

            Transaction absent = engine.begin(SERIALIZABLE);
            assertTrue(absent.resolve(0, NODE, "ghost").isEmpty());
            ownWrite(absent);
            engine.write(txn -> txn.createNode(NODE, "someone-else"));
            assertEquals(CommitResult.Outcome.REBASED, commitAfterCheckpoint(engine, absent));

            Transaction appears = engine.begin(SERIALIZABLE);
            assertTrue(appears.resolve(0, NODE, "ghost").isEmpty());
            ownWrite(appears);
            engine.write(txn -> txn.createNode(NODE, "ghost"));
            assertConflict(engine, appears, "canonical key");

            Transaction disappears = engine.begin(SERIALIZABLE);
            assertEquals(alice, disappears.resolve(0, NODE, "alice").orElseThrow());
            ownWrite(disappears);
            engine.write(txn -> {
                txn.delete(alice);
                return null;
            });
            assertConflict(engine, disappears, "canonical key");
        }
    }

    @Test
    void wholeSlotScansAreValidatedByContent() {
        try (StorageEngine engine = open()) {
            node(engine, NODE);
            engine.write(txn -> {
                txn.put(OBSERVED, 1, 1L);
                return null;
            });

            Transaction catalog = engine.begin(SERIALIZABLE);
            catalog.atoms().count();
            ownWrite(catalog);
            engine.write(txn -> {
                txn.put(WRITTEN, 9_999, 1L);
                return null;
            });
            assertEquals(CommitResult.Outcome.REBASED, commitAfterCheckpoint(engine, catalog));

            Transaction grows = engine.begin(SERIALIZABLE);
            grows.atoms().count();
            ownWrite(grows);
            node(engine, OTHER);
            assertConflict(engine, grows, "serializable scan of catalog");

            Transaction slot = engine.begin(SERIALIZABLE);
            slot.scan(OBSERVED).stream().count();
            ownWrite(slot);
            unrelatedCommit(engine);
            assertEquals(CommitResult.Outcome.REBASED, commitAfterCheckpoint(engine, slot));

            Transaction moved = engine.begin(SERIALIZABLE);
            moved.scan(OBSERVED).stream().count();
            ownWrite(moved);
            engine.write(txn -> {
                txn.put(OBSERVED, 2, 2L);
                return null;
            });
            assertConflict(engine, moved, "serializable scan of observed");
        }
    }

    @Test
    void nestedRangeReadsIgnoreChangesOutsideTheRange() {
        try (StorageEngine engine = open()) {
            long hub = node(engine, NODE);
            long[] edges = LongStream.range(0, 60).map(_ -> joinNewEdge(engine, hub)).toArray();
            long low = edges[10];
            long high = edges[20];

            Transaction outside = engine.begin(SERIALIZABLE);
            assertInstanceOf(Postings.Promoted.class, outside.nested(EngineSlots.REVERSE, hub, low, high).orElseThrow());
            ownWrite(outside);
            joinNewEdge(engine, hub);
            engine.write(txn -> {
                txn.remove(edges[5], hub);
                return null;
            });
            assertEquals(CommitResult.Outcome.REBASED, commitAfterCheckpoint(engine, outside));

            Transaction inside = engine.begin(SERIALIZABLE);
            inside.nested(EngineSlots.REVERSE, hub, low, high);
            ownWrite(inside);
            engine.write(txn -> {
                txn.remove(edges[15], hub);
                return null;
            });
            assertConflict(engine, inside, "between " + low + " and " + high);
        }
    }

    @Test
    void unrecordedReadsAreNotValidated() {
        try (StorageEngine engine = open()) {
            node(engine, NODE);

            Transaction estimated = engine.begin(SERIALIZABLE);
            try (View.Unrecorded _ = estimated.unrecorded()) {
                try (View.Unrecorded _ = estimated.unrecorded()) {
                    estimated.countOfType(0, NODE);
                }
                estimated.degree(1);
            }
            ownWrite(estimated);
            node(engine, NODE);
            assertEquals(CommitResult.Outcome.REBASED, commitAfterCheckpoint(engine, estimated));

            Transaction recorded = engine.begin(SERIALIZABLE);
            try (View.Unrecorded _ = recorded.unrecorded()) {
                recorded.countOfType(0, OTHER);
            }
            recorded.countOfType(0, NODE);
            ownWrite(recorded);
            node(engine, NODE);
            assertConflict(engine, recorded, "atoms of type " + NODE);

            try (Transaction first = engine.begin(); Transaction second = engine.begin(); Snapshot snapshot = engine.snapshot()) {
                assertSame(View.NOT_RECORDING, first.unrecorded());
                assertSame(View.NOT_RECORDING, second.unrecorded());
                assertSame(View.NOT_RECORDING, snapshot.unrecorded());
            }
        }
    }

    @Test
    void ownWritesDoNotInvalidateOwnReads() {
        try (StorageEngine engine = open()) {
            node(engine, NODE);
            long hub = node(engine, NODE);
            Transaction txn = engine.begin(SERIALIZABLE);
            long before = txn.countOfType(0, NODE);
            long degree = txn.degree(hub);
            txn.createNode(NODE, "mine");
            long edge = txn.createEdge(EDGE, EdgeKind.SET);
            txn.insert(edge, hub);
            assertEquals(before + 1, txn.countOfType(0, NODE));
            assertEquals(degree + 1, txn.degree(hub));
            long observed = txn.scan(OBSERVED).size();
            txn.put(OBSERVED, 5, 5L);
            assertEquals(observed + 1, txn.scan(OBSERVED).size());
            unrelatedCommit(engine);
            assertEquals(CommitResult.Outcome.REBASED, commitAfterCheckpoint(engine, txn));
        }
    }

    @Test
    void manyKeyReadsInOneSlotArePromotedToAScanOfTheSlot() {
        try (StorageEngine engine = open()) {
            Transaction few = engine.begin(SERIALIZABLE);
            for (long key = 0; key < Transaction.PROMOTE_AFTER; key++) {
                few.get(OBSERVED, key);
            }
            ownWrite(few);
            engine.write(txn -> {
                txn.put(OBSERVED, Transaction.PROMOTE_AFTER + 10, 1L);
                return null;
            });
            assertEquals(CommitResult.Outcome.REBASED, few.commit().outcome());

            Transaction many = engine.begin(SERIALIZABLE);
            for (long key = 0; key <= Transaction.PROMOTE_AFTER; key++) {
                many.get(OBSERVED, key);
            }
            ownWrite(many);
            engine.write(txn -> {
                txn.put(OBSERVED, Transaction.PROMOTE_AFTER + 20, 1L);
                return null;
            });
            assertConflict(engine, many, "serializable scan of observed");
        }
    }
}
