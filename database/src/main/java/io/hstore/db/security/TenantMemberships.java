// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.security;

import io.hstore.engine.StorageEngine;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.View;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class TenantMemberships {

    private static final long CHECKED = 0x4D45_4D42_4552_5301L;

    public record Crossing(String branch, long edge, long member, int edgeTenant, String edgeTenantName, int memberTenant) {
        public String repair() {
            return "USE TENANT " + edgeTenantName + "; " + (branch.equals("main") ? "" : "USE BRANCH " + branch + "; ")
                    + "REMOVE @" + member + " FROM @" + edge + ";";
        }
    }

    public static List<Crossing> find(StorageEngine engine) {
        return engine.branches().stream().filter(Branch::isActive).flatMap(branch -> {
            try (Snapshot snapshot = engine.snapshot(branch.id())) {
                return find(snapshot, branch.name()).stream();
            }
        }).toList();
    }

    private TenantMemberships() {
    }

    public static List<Crossing> find(View view, String branch) {
        Map<Integer, String> names = Security.tenants(view).stream().collect(Collectors.toMap(Security.Tenant::id, Security.Tenant::name));
        return view.atoms()
                .flatMap(entry -> entry.value() instanceof AtomRecord.EdgeRecord edge
                        ? view.edge(entry.key(), edge).memberIds().boxed()
                        .flatMap(member -> view.atom(member)
                                .filter(record -> record.tenant() != edge.tenant())
                                .map(record -> new Crossing(branch, entry.key(), member, edge.tenant(),
                                        names.getOrDefault(edge.tenant(), "#" + edge.tenant()), record.tenant()))
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

    public static List<Crossing> repair(Transaction txn, String branch) {
        List<Crossing> crossings = find(txn, branch);
        crossings.forEach(crossing -> txn.remove(crossing.edge(), crossing.member()));
        return crossings;
    }
}
