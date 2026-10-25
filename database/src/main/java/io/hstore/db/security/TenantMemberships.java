// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.security;

import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.View;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class TenantMemberships {

    private static final long CHECKED = 0x4D45_4D42_4552_5301L;

    public record Crossing(long edge, long member, int edgeTenant, int memberTenant) {
        public String repair() {
            return "REMOVE @" + member + " FROM @" + edge;
        }
    }

    private TenantMemberships() {
    }

    public static List<Crossing> find(View view) {
        return view.atoms()
                .flatMap(entry -> entry.value() instanceof AtomRecord.EdgeRecord edge
                        ? view.edge(entry.key(), edge).memberIds().boxed()
                        .flatMap(member -> view.atom(member)
                                .filter(record -> record.tenant() != edge.tenant())
                                .map(record -> new Crossing(entry.key(), member, edge.tenant(), record.tenant()))
                                .stream())
                        : Stream.empty())
                .toList();
    }

    public static boolean checked(View view) {
        return view.get(EngineSlots.SETTINGS, CHECKED).isPresent();
    }

    public static void markChecked(Transaction txn) {
        txn.merge(EngineSlots.SETTINGS, CHECKED, Optional::isEmpty, _ -> Optional.of(1L));
    }

    public static List<Crossing> repair(Transaction txn) {
        List<Crossing> crossings = find(txn);
        crossings.forEach(crossing -> txn.remove(crossing.edge(), crossing.member()));
        if (!checked(txn)) {
            markChecked(txn);
        }
        return crossings;
    }
}
