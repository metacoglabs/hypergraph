// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import io.hstore.db.hora.NestingSlots.ChildLink;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.txn.Derivation;
import io.hstore.engine.txn.Workspace;

public final class NestingIndexing implements Derivation {

    public static final NestingIndexing INSTANCE = new NestingIndexing();

    private NestingIndexing() {
    }

    @Override
    public void onMember(MemberChange change, Workspace workspace) {
        switch (change) {
            case MemberChange.Added(long parent, Incidence incidence, long locator) -> link(workspace, parent, incidence, locator);
            case MemberChange.Updated(long parent, Incidence _, Incidence after, long _, long to) -> link(workspace, parent, after, to);
            case MemberChange.Removed(long parent, Incidence incidence, long _) -> {
                if (isEdge(workspace, incidence.member())) {
                    workspace.replace(NestingSlots.CHILDREN, NestingSlots.CHILD_INDEX.remove(workspace.tree(NestingSlots.CHILDREN),
                            workspace.scope(), parent, incidence.member()));
                }
            }
        }
    }

    private static boolean isEdge(Workspace workspace, long atom) {
        return workspace.get(EngineSlots.CATALOG, atom).map(record -> record instanceof AtomRecord.EdgeRecord).orElse(false);
    }

    private static void link(Workspace workspace, long parent, Incidence incidence, long locator) {
        if (isEdge(workspace, incidence.member())) {
            workspace.replace(NestingSlots.CHILDREN, NestingSlots.CHILD_INDEX.add(workspace.tree(NestingSlots.CHILDREN),
                    workspace.scope(), parent, incidence.member(), ChildLink.of(incidence, locator)));
        }
    }
}
