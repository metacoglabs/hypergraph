// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.retrieve.SignatureSlots.SignatureName;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.index.IndexValues.Marker;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.txn.Derivation;
import io.hstore.engine.txn.Workspace;

import java.util.List;
import java.util.Optional;

public final class SignatureIndexing implements Derivation {

    public static final SignatureIndexing INSTANCE = new SignatureIndexing();

    private SignatureIndexing() {
    }

    @Override
    public void onMember(MemberChange change, Workspace workspace) {
        switch (change) {
            case MemberChange.Added(long edge, Incidence incidence, long _) -> move(workspace, edge, incidence.member(), true);
            case MemberChange.Removed(long edge, Incidence incidence, long _) -> move(workspace, edge, incidence.member(), false);
            case MemberChange.Updated _ -> {
            }
        }
    }

    private static AtomRecord record(Workspace workspace, long atom) {
        return workspace.get(EngineSlots.CATALOG, atom)
                .orElseThrow(() -> HStoreException.invalid("atom " + atom + " has no catalog record"));
    }

    private static void move(Workspace workspace, long edge, long member, boolean added) {
        AtomRecord edgeRecord = record(workspace, edge);
        int label = record(workspace, member).type();
        int tenant = edgeRecord.tenant();
        Signature before = workspace.get(SignatureSlots.SIGNATURES, edge).orElse(Signature.empty(edgeRecord.type()));
        Signature after = added ? before.withAdded(label) : before.withRemoved(label);
        if (before.arity() > 0) {
            long key = SignatureSlots.signatureKey(tenant, before);
            workspace.replace(SignatureSlots.EDGES, SignatureSlots.SIGNATURE_EDGES.remove(workspace.tree(SignatureSlots.EDGES), workspace.scope(), key, edge));
            count(workspace, key, tenant, before, -1);
        }
        if (after.arity() > 0) {
            long key = SignatureSlots.signatureKey(tenant, after);
            workspace.write(SignatureSlots.SIGNATURES, edge, _ -> Optional.of(after));
            workspace.replace(SignatureSlots.EDGES, SignatureSlots.SIGNATURE_EDGES.add(workspace.tree(SignatureSlots.EDGES), workspace.scope(), key, edge, Marker.PRESENT));
            count(workspace, key, tenant, after, 1);
        } else {
            workspace.write(SignatureSlots.SIGNATURES, edge, _ -> Optional.empty());
        }
    }

    private static void count(Workspace workspace, long key, int tenant, Signature signature, int delta) {
        workspace.write(SignatureSlots.NAMES, key, current -> {
            List<SignatureName> names = current.orElse(List.of());
            long now = names.stream()
                    .filter(name -> name.tenant() == tenant && name.signature().equals(signature))
                    .mapToLong(SignatureName::edgeCount).findFirst().orElse(0) + delta;
            List<SignatureName> next = SignatureSlots.withName(names, new SignatureName(tenant, signature, now));
            return next.isEmpty() ? Optional.empty() : Optional.of(next);
        });
    }
}
