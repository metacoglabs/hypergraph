// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db;

import io.hstore.db.semantic.Encoder;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.txn.TxnOptions;

public record DatabaseOptions(EngineOptions engine, Encoder encoder, long defaultQueryPages, long transactionPages) {

    public static DatabaseOptions defaults() {
        return new DatabaseOptions(EngineOptions.defaults(), Encoder.hashing(256), 2_000_000, Long.MAX_VALUE);
    }

    public DatabaseOptions withEngine(EngineOptions options) {
        return new DatabaseOptions(options, encoder, defaultQueryPages, transactionPages);
    }

    public DatabaseOptions withEncoder(Encoder replacement) {
        return new DatabaseOptions(engine, replacement, defaultQueryPages, transactionPages);
    }

    public DatabaseOptions withQueryPages(long pages) {
        return new DatabaseOptions(engine, encoder, pages, transactionPages);
    }

    public DatabaseOptions withTransactionPages(long pages) {
        return new DatabaseOptions(engine, encoder, defaultQueryPages, pages);
    }

    public TxnOptions transaction() {
        return TxnOptions.defaults().withMaxPages(transactionPages);
    }
}
