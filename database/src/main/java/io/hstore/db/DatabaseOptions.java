// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db;

import io.hstore.db.semantic.Encoder;
import io.hstore.db.semantic.Reranker;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.txn.TxnOptions;

public record DatabaseOptions(EngineOptions engine, Encoder encoder, long defaultQueryPages, long transactionPages, Reranker reranker) {

    public static DatabaseOptions defaults() {
        return new DatabaseOptions(EngineOptions.defaults(), Encoder.hashing(256), 2_000_000, Long.MAX_VALUE, Reranker.NONE);
    }

    public DatabaseOptions withEngine(EngineOptions options) {
        return new DatabaseOptions(options, encoder, defaultQueryPages, transactionPages, reranker);
    }

    public DatabaseOptions withEncoder(Encoder replacement) {
        return new DatabaseOptions(engine, replacement, defaultQueryPages, transactionPages, reranker);
    }

    public DatabaseOptions withQueryPages(long pages) {
        return new DatabaseOptions(engine, encoder, pages, transactionPages, reranker);
    }

    public DatabaseOptions withTransactionPages(long pages) {
        return new DatabaseOptions(engine, encoder, defaultQueryPages, pages, reranker);
    }

    public DatabaseOptions withReranker(Reranker replacement) {
        return new DatabaseOptions(engine, encoder, defaultQueryPages, transactionPages, replacement);
    }

    public TxnOptions transaction() {
        return TxnOptions.defaults().withMaxPages(transactionPages);
    }
}
