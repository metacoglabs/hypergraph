// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.temporal;

import io.hstore.db.property.PropertySlots.IndexRoot;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.index.Postings;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.txn.Derivation;
import io.hstore.engine.txn.Workspace;

public final class ValidityIndexing implements Derivation {

    public static final ValidityIndexing INSTANCE = new ValidityIndexing();

    private ValidityIndexing() {
    }

    @Override
    public void onMember(MemberChange change, Workspace workspace) {
        switch (change) {
            case MemberChange.Added(long edge, Incidence incidence, long _) -> adjust(workspace, edge, incidence, 1);
            case MemberChange.Updated(long edge, Incidence before, Incidence after, long _, long _)
                    when before.validFrom() != after.validFrom() || before.validTo() != after.validTo() -> {
                adjust(workspace, edge, before, -1);
                adjust(workspace, edge, after, 1);
            }
            case MemberChange.Updated _ -> {
            }
            case MemberChange.Removed(long edge, Incidence incidence, long _) -> adjust(workspace, edge, incidence, -1);
        }
    }

    private static void adjust(Workspace workspace, long edge, Incidence incidence, int delta) {
        boolean from = incidence.validFrom() != Long.MIN_VALUE;
        boolean to = incidence.validTo() != Long.MAX_VALUE;
        if (!from && !to) {
            return;
        }
        AtomRecord record = workspace.get(EngineSlots.CATALOG, edge).orElseThrow(() -> workspace.replaying()
                ? HStoreException.conflict("hyperedge " + edge + " was deleted by a concurrent commit")
                : HStoreException.invalid("hyperedge " + edge + " has no catalog record"));
        if (from) {
            count(workspace, ValiditySlots.endpointKey(record.tenant(), record.type(), ValiditySlots.VALID_FROM), incidence.validFrom(), edge, delta);
        }
        if (to) {
            count(workspace, ValiditySlots.endpointKey(record.tenant(), record.type(), ValiditySlots.VALID_TO), incidence.validTo(), edge, delta);
        }
    }

    private static void count(Workspace workspace, long directoryKey, long instant, long edge, int delta) {
        Tree<IndexRoot> directory = workspace.tree(ValiditySlots.ENDPOINT_INDEX);
        Ref current = directory.get(directoryKey).map(IndexRoot::root).orElse(Ref.EMPTY);
        Tree<Postings<Long>> instants = new Tree<>(ValiditySlots.ENDPOINTS.schema(), workspace.source(), current);
        long next = ValiditySlots.ENDPOINTS.get(instants, instant, edge).orElse(0L) + delta;
        Tree<Postings<Long>> updated = next > 0
                ? ValiditySlots.ENDPOINTS.add(instants, workspace.scope(), instant, edge, next)
                : ValiditySlots.ENDPOINTS.remove(instants, workspace.scope(), instant, edge);
        workspace.replace(ValiditySlots.ENDPOINT_INDEX, updated.root() instanceof Ref.Empty
                ? directory.remove(workspace.scope(), directoryKey)
                : directory.put(workspace.scope(), directoryKey, new IndexRoot(updated.root())));
    }
}
