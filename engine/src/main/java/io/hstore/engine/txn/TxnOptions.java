// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.txn;

import io.hstore.engine.catalog.Branch;

import java.util.Optional;

public record TxnOptions(int branch, Isolation isolation, Optional<String> requestId, long maxPages) {

    public static TxnOptions defaults() {
        return new TxnOptions(Branch.MAIN, Isolation.SNAPSHOT, Optional.empty(), Long.MAX_VALUE);
    }

    public TxnOptions onBranch(int id) {
        return new TxnOptions(id, isolation, requestId, maxPages);
    }

    public TxnOptions withIsolation(Isolation level) {
        return new TxnOptions(branch, level, requestId, maxPages);
    }

    public TxnOptions withRequestId(String id) {
        return new TxnOptions(branch, isolation, Optional.of(id), maxPages);
    }

    public TxnOptions withMaxPages(long pages) {
        return new TxnOptions(branch, isolation, requestId, pages);
    }
}
