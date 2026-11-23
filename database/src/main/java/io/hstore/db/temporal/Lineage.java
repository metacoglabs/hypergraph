// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.temporal;

import io.hstore.engine.catalog.Branch;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public record Lineage(Map<Integer, Branch> branches, int branch) {

    public Lineage {
        branches = Map.copyOf(branches);
    }

    public static Lineage of(Collection<Branch> branches, int branch) {
        return new Lineage(branches.stream().collect(Collectors.toMap(Branch::id, Function.identity())), branch);
    }

    public boolean includes(int eventBranch, long generation) {
        int id = branch;
        long upper = Long.MAX_VALUE;
        while (true) {
            Branch node = branches.get(id);
            if (node == null) {
                return false;
            }
            if (eventBranch == id) {
                return generation <= upper && (id == Branch.MAIN || generation > node.baseGeneration());
            }
            if (id == Branch.MAIN) {
                return false;
            }
            upper = Math.min(upper, node.baseGeneration());
            id = node.parent();
        }
    }

    public int branchAt(long generation) {
        int id = branch;
        while (id != Branch.MAIN) {
            Branch node = branches.get(id);
            if (node == null || generation > node.baseGeneration()) {
                return id;
            }
            id = node.parent();
        }
        return Branch.MAIN;
    }
}
