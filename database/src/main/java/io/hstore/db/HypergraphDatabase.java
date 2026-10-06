// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db;

import io.hstore.db.evidence.Provenance;
import io.hstore.db.payload.PayloadStore;
import io.hstore.db.property.PropertyIndexing;
import io.hstore.db.property.PropertySlots;
import io.hstore.db.schema.Schema;
import io.hstore.db.schema.SchemaSlots;
import io.hstore.db.security.Principal;
import io.hstore.db.security.Security;
import io.hstore.db.semantic.Embedding;
import io.hstore.db.semantic.SemanticPlane;
import io.hstore.db.signal.Signals;
import io.hstore.db.stats.Statistics;
import io.hstore.db.temporal.StateBindings;
import io.hstore.db.view.MaterializedViews;
import io.hstore.engine.HStoreException;
import io.hstore.engine.StorageEngine;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.txn.Derivation;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.TxnOptions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

public final class HypergraphDatabase implements AutoCloseable {

    private static final int MAX_ATTEMPTS = 8;

    private final DatabaseOptions options;
    private final PayloadStore payloads;
    private final StorageEngine engine;
    private final Schema schema;
    private final SemanticPlane semantic;
    private final Statistics statistics;
    private final MaterializedViews views;

    private HypergraphDatabase(Path directory, DatabaseOptions options) {
        this.options = options;
        this.payloads = PayloadStore.open(directory.resolve("payload"));
        AtomicReference<Schema> schemaHolder = new AtomicReference<>();
        List<Derivation> derivations = List.of(new PropertyIndexing(schemaHolder::get, () -> payloads), Signals.INDEXING);
        this.engine = StorageEngine.open(directory, options.engine().withExtensions(extensionSlots(), derivations));
        this.schema = new Schema(engine.dictionary());
        schemaHolder.set(schema);
        this.semantic = new SemanticPlane(engine, payloads, options.encoder(), directory.resolve("semantic"));
        engine.onCheckpoint(semantic::persist);
        this.statistics = new Statistics(engine);
        this.views = new MaterializedViews(this);
    }

    public static List<Slot<?>> extensionSlots() {
        return Stream.of(SchemaSlots.ALL, PropertySlots.ALL, List.<Slot<?>>of(Embedding.EMBEDDINGS),
                        Provenance.SLOTS, StateBindings.SLOTS, Signals.SLOTS, MaterializedViews.SLOTS, Security.SLOTS)
                .flatMap(List::stream)
                .toList();
    }

    public static HypergraphDatabase open(Path directory) {
        return open(directory, DatabaseOptions.defaults());
    }

    public static HypergraphDatabase open(Path directory, DatabaseOptions options) {
        return new HypergraphDatabase(directory, options);
    }

    public DatabaseOptions options() {
        return options;
    }

    public StorageEngine engine() {
        return engine;
    }

    public Schema schema() {
        return schema;
    }

    public PayloadStore payloads() {
        return payloads;
    }

    public SemanticPlane semantic() {
        return semantic;
    }

    public Statistics statistics() {
        return statistics;
    }

    public MaterializedViews views() {
        return views;
    }

    public Reader reader(Snapshot snapshot) {
        return reader(snapshot, Principal.SYSTEM);
    }

    public Reader reader(Snapshot snapshot, Principal principal) {
        return new Reader(this, snapshot, principal);
    }

    public Writer writer(Transaction transaction) {
        return writer(transaction, Principal.SYSTEM);
    }

    public Writer writer(Transaction transaction, Principal principal) {
        return new Writer(this, transaction, principal);
    }

    public <T> T read(Function<? super Reader, T> work) {
        return read(Branch.MAIN, Principal.SYSTEM, work);
    }

    public <T> T read(int branch, Function<? super Reader, T> work) {
        return read(branch, Principal.SYSTEM, work);
    }

    public <T> T read(int branch, Principal principal, Function<? super Reader, T> work) {
        try (Snapshot snapshot = engine.snapshot(branch)) {
            return work.apply(reader(snapshot, principal));
        }
    }

    public <T> T readAt(long generation, int branch, Function<? super Reader, T> work) {
        return readAt(generation, branch, Principal.SYSTEM, work);
    }

    public <T> T readAt(long generation, int branch, Principal principal, Function<? super Reader, T> work) {
        try (Snapshot snapshot = engine.snapshotAt(generation, branch)) {
            return work.apply(reader(snapshot, principal));
        }
    }

    public <T> T write(Function<? super Writer, T> work) {
        return write(options.transaction(), Principal.SYSTEM, work);
    }

    public <T> T write(TxnOptions txnOptions, Function<? super Writer, T> work) {
        return write(txnOptions, Principal.SYSTEM, work);
    }

    public TxnOptions transactionOptions(Principal principal) {
        long tenantPages = read(reader -> Security.quota(reader.view(), principal.tenant()).maxTransactionPages());
        return options.transaction().withMaxPages(Math.min(options.transactionPages(), tenantPages));
    }

    public long queryPageBudget(Principal principal) {
        long tenantPages = read(reader -> Security.quota(reader.view(), principal.tenant()).maxQueryPages());
        return Math.min(options.defaultQueryPages(), tenantPages);
    }

    public Optional<Principal> authenticate(String user, String password) {
        return read(reader -> Security.authenticate(reader.view(), user, password));
    }

    public boolean requiresAuthentication() {
        return read(reader -> Security.hasUsers(reader.view()));
    }

    public <T> T write(TxnOptions txnOptions, Principal principal, Function<? super Writer, T> work) {
        principal.requireWrite();
        for (int attempt = 1; ; attempt++) {
            try (Transaction txn = engine.begin(txnOptions)) {
                T result = work.apply(writer(txn, principal));
                commit(txn);
                return result;
            } catch (HStoreException.Conflict conflict) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw conflict;
                }
                pause(attempt);
            }
        }
    }

    public void commit(Transaction txn) {
        payloads.sync();
        txn.commit();
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(Duration.ofMillis(ThreadLocalRandom.current().nextLong(1, 1L << Math.min(attempt, 6))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw HStoreException.io("interrupted while retrying", e);
        }
    }

    @Override
    public void close() {
        views.close();
        statistics.close();
        semantic.close();
        engine.close();
        payloads.close();
    }
}
