// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.index;

import io.hstore.engine.EngineOptions;
import io.hstore.engine.StorageEngine;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.index.IndexValues.Incident;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.Validity;
import io.hstore.engine.tree.Summary;
import io.hstore.engine.txn.IncidentEdge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentPostingTest {

    private static final int NODE = 1;
    private static final int EDGE = 2;

    @TempDir
    Path directory;

    private static EngineOptions small() {
        return EngineOptions.defaults().withPageSize(1024).withPagesPerSegment(256).withHistoryLimit(1000);
    }

    private static Incident roundTrip(Incident incident) {
        ByteCursor out = ByteCursor.growable(64);
        IndexValues.INCIDENT.encode(out, new long[]{1}, List.of(incident));
        Object[] into = new Object[1];
        IndexValues.INCIDENT.decode(ByteCursor.wrap(out.toByteArray()), new long[]{1}, into);
        return (Incident) into[0];
    }

    @Test
    void aDefaultIntervalCostsOneByteMoreThanFormat8() {
        Incident open = new Incident(3, 17, Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(ByteCursor.varLongSize(3) + ByteCursor.signedVarLongSize(17) + 1, IndexValues.INCIDENT.maxSize(open));
    }

    @Test
    void incidentsRoundTripWithEveryEndpointCombination() {
        List<Incident> cases = List.of(
                new Incident(0, 0, Long.MIN_VALUE, Long.MAX_VALUE),
                new Incident(5, -9, -42, Long.MAX_VALUE),
                new Incident(7, 1L << 40, Long.MIN_VALUE, -3),
                new Incident(1, 12, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1),
                new Incident(63, 4, 100, 200));
        for (Incident incident : cases) {
            assertEquals(incident, roundTrip(incident));
        }
    }

    @Test
    void promotedPostingsTreeSummaryCarriesTimeAndRoles() {
        try (StorageEngine engine = StorageEngine.open(directory, small())) {
            long hub = engine.write(txn -> txn.createNode(NODE, "hub"));
            engine.write(txn -> {
                for (int i = 0; i < 60; i++) {
                    long edge = txn.createEdge(EDGE, EdgeKind.SET);
                    txn.insert(edge, new Incidence(hub, 1 + i % 3, Incidence.of(hub).weight(), i, i + 10, 0, 0));
                }
                return null;
            });
            engine.read(snapshot -> {
                Summary summary = snapshot.incidentTree(hub).summary();
                assertEquals(60, snapshot.degree(hub));
                assertEquals(0, summary.timeMin());
                assertEquals(69, summary.timeMax());
                for (int roleSet = 1; roleSet <= 3; roleSet++) {
                    assertTrue(summary.mayHaveRoles(Incidence.roleBit(roleSet)));
                }
                assertFalse(summary.mayHaveRoles(Incidence.roleBit(9)));
                assertFalse(summary.mayBeValidAt(70));
                return null;
            });
        }
    }

    @Test
    void aValidityOnlyUpdateRewritesThePosting() {
        try (StorageEngine engine = StorageEngine.open(directory, small())) {
            long[] ids = engine.write(txn -> {
                long node = txn.createNode(NODE, "a");
                long edge = txn.createEdge(EDGE, EdgeKind.SET);
                txn.insert(edge, Incidence.of(node).withValidity(new Validity(5, Long.MAX_VALUE)));
                return new long[]{node, edge};
            });
            engine.write(txn -> {
                txn.upsert(ids[1], ids[0], incidence -> incidence.withValidity(new Validity(5, 9)));
                return null;
            });
            engine.read(snapshot -> {
                IncidentEdge posting = snapshot.incident(ids[0]).findFirst().orElseThrow();
                assertEquals(ids[1], posting.edge());
                assertEquals(5, posting.validFrom());
                assertEquals(9, posting.validTo());
                return null;
            });
        }
    }
}
