// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.catalog;

import io.hstore.engine.HStoreException;

import java.util.Map;
import java.util.TreeMap;

public record Generation(long id, long wallTime, long txnId, long nextAtom, long nextTxn, Map<Integer, Branch> branches) {

    public Generation {
        branches = Map.copyOf(branches);
    }

    public static Generation initial() {
        return new Generation(0, 0, 0, 1, 1, Map.of(Branch.MAIN, Branch.main(RootVector.EMPTY)));
    }

    public Branch branch(int id) {
        Branch branch = branches.get(id);
        if (branch == null || branch.state() == Branch.State.DROPPED) {
            throw HStoreException.invalid("branch " + id + " does not exist");
        }
        return branch;
    }

    public Branch main() {
        return branch(Branch.MAIN);
    }

    public Generation successor(long txn, long wallClock, long atomHighWater, long txnHighWater, Branch changed) {
        Map<Integer, Branch> next = new TreeMap<>(branches);
        next.put(changed.id(), changed);
        return new Generation(id + 1, wallClock, txn, Math.max(nextAtom, atomHighWater), Math.max(nextTxn, txnHighWater), next);
    }
}
