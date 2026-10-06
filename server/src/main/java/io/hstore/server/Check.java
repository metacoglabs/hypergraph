// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server;

import io.hstore.engine.StorageEngine;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.Generation;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.tree.TreeVerifier;

import java.util.Map;

final class Check {

    private Check() {
    }

    static String run(StorageEngine engine) {
        StringBuilder report = new StringBuilder();
        Generation generation = engine.transactions().current();
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
        report.append("verified ").append(pages).append(" pages: checksums, ordering, balance and summaries are consistent");
        return report.toString();
    }
}
