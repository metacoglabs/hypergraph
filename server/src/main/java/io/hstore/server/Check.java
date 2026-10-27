// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server;

import io.hstore.db.HypergraphDatabase;
import io.hstore.db.security.TenantMemberships;
import io.hstore.engine.StorageEngine;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.Generation;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.maintenance.ReferenceCounter;
import io.hstore.engine.tree.TreeVerifier;
import io.hstore.engine.txn.Snapshot;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class Check {

    private static final int MAX_LISTED = 50;

    private Check() {
    }

    static String run(StorageEngine engine) {
        String report;
        boolean clean;
        boolean checked;
        try (Snapshot pinned = engine.snapshot()) {
            List<TenantMemberships.Crossing> crossings = TenantMemberships.find(engine);
            report = run(engine, pinned.catalog()) + "\n" + isolation(crossings);
            clean = crossings.isEmpty();
            checked = TenantMemberships.checked(pinned);
        }
        if (clean && !checked) {
            engine.write(txn -> {
                TenantMemberships.markChecked(txn);
                return null;
            });
        }
        return report;
    }

    static String repair(HypergraphDatabase database) {
        List<TenantMemberships.Crossing> removed = database.repairTenantMemberships();
        if (removed.isEmpty()) {
            return "tenant isolation: no hyperedge on any branch had a member from another tenant; nothing to repair";
        }
        return "tenant isolation: removed " + removed.size() + " memberships that crossed tenants:\n"
                + removed.stream().map(crossing -> "  " + crossing.repair() + "   -- member of tenant " + crossing.memberTenant()
                + " in an edge of tenant " + crossing.edgeTenant()).collect(Collectors.joining("\n"));
    }

    private static String run(StorageEngine engine, Generation generation) {
        StringBuilder report = new StringBuilder();
        TreeVerifier verifier = new TreeVerifier(engine.source());
        report.append("generation ").append(generation.id()).append(", recovered ")
                .append(engine.recovery().replayedCommits()).append(" commits from the log\n");
        long pages = 0;
        for (Branch branch : generation.branches().values()) {
            if (!branch.isActive()) {
                continue;
            }
            report.append("branch ").append(branch.name()).append('\n');
            for (Map.Entry<Integer, ?> root : branch.roots().roots().entrySet()) {
                Slot<?> slot = engine.slots().slot(root.getKey());
                TreeVerifier.Report verified = verifier.verify(branch.roots().get(slot), slot.schema());
                pages += verified.pages();
                report.append("  %-16s %10d entries %8d pages %6d nested trees  height %d%n".formatted(
                        slot.name(), verified.entries(), verified.pages(), verified.nestedTrees(), verified.height()));
            }
        }
        report.append("verified ").append(pages).append(" pages: checksums, ordering, balance and summaries are consistent\n");
        ReferenceCounter.Verification references = engine.verifyReferences();
        report.append("reference counts match the trees: ").append(references.livePages()).append(" pages in use, ")
                .append(references.waitingPages()).append(" waiting for older readers");
        return report.toString();
    }

    private static String isolation(List<TenantMemberships.Crossing> found) {
        List<String> crossings = found.stream().map(crossing -> "  " + crossing.repair() + "   -- member of tenant "
                + crossing.memberTenant() + " in an edge of tenant " + crossing.edgeTenant()).toList();
        if (crossings.isEmpty()) {
            return "tenant isolation: no hyperedge on any branch has a member from another tenant";
        }
        return "tenant isolation: " + crossings.size() + " memberships cross tenants; remove them with "
                + "hstore check --repair-tenants, or run these as the edge's tenant:\n"
                + String.join("\n", crossings.subList(0, Math.min(MAX_LISTED, crossings.size())))
                + (crossings.size() > MAX_LISTED ? "\n  ... " + (crossings.size() - MAX_LISTED) + " more" : "");
    }
}
