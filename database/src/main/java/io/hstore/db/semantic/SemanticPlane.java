// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.semantic;

import io.hstore.db.payload.PayloadStore;
import io.hstore.engine.StorageEngine;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.SlotChange;
import io.hstore.engine.feed.ChangeFeed;
import io.hstore.engine.feed.CommitEvent;
import io.hstore.engine.tree.Entry;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.View;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;

public final class SemanticPlane implements AutoCloseable {

    public static final int MODEL_NAMESPACE = 11;

    public enum Consistency { SNAPSHOT, FRESH }

    public record Hit(long atom, float distance) {
    }

    private static final Duration FRESHNESS_TIMEOUT = Duration.ofSeconds(5);
    private static final double TOMBSTONE_LIMIT = 0.3;
    private static final System.Logger LOG = System.getLogger("hstore.semantic");

    private final StorageEngine engine;
    private final PayloadStore payloads;
    private final Encoder encoder;
    private final Map<Integer, HnswIndex> indexes = new ConcurrentHashMap<>();
    private final ReentrantLock progressLock = new ReentrantLock();
    private final Condition advanced = progressLock.newCondition();
    private final ReentrantLock applyLock = new ReentrantLock();
    private final Path directory;
    private final ChangeFeed.Subscription subscription;
    private final ChangeFeed.Hold hold;
    private volatile long indexedGeneration;
    private volatile long persistedGeneration = -1;

    public SemanticPlane(StorageEngine engine, PayloadStore payloads, Encoder encoder, Path directory) {
        this.engine = engine;
        this.payloads = payloads;
        this.encoder = encoder;
        this.directory = directory;
        long start = load().orElseGet(this::rebuild);
        this.subscription = engine.feed().subscribe(start, this::apply, this::recover);
        this.hold = engine.feed().hold(() -> persistedGeneration < 0 ? Long.MAX_VALUE : persistedGeneration);
    }

    private OptionalLong load() {
        Path state = directory.resolve("state");
        if (!Files.exists(state)) {
            return OptionalLong.empty();
        }
        try {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(state)) {
                properties.load(in);
            }
            long generation = Long.parseLong(properties.getProperty("generation"));
            if (generation > engine.transactions().current().id()) {
                LOG.log(System.Logger.Level.WARNING, "semantic index at generation {0} is ahead of the database; rebuilding", generation);
                return OptionalLong.empty();
            }
            if (!engine.feed().covers(generation)) {
                LOG.log(System.Logger.Level.WARNING, "change feed no longer covers semantic index generation {0}; rebuilding", generation);
                return OptionalLong.empty();
            }
            for (String model : properties.getProperty("models", "").split(",")) {
                if (model.isBlank()) {
                    continue;
                }
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(directory.resolve("hnsw-" + model + ".idx"))))) {
                    indexes.put(Integer.parseInt(model), HnswIndex.readFrom(in));
                }
            }
            indexedGeneration = generation;
            persistedGeneration = generation;
            LOG.log(System.Logger.Level.INFO, "loaded semantic index at generation {0} ({1} models)", generation, indexes.size());
            return OptionalLong.of(generation);
        } catch (IOException | RuntimeException unreadable) {
            LOG.log(System.Logger.Level.WARNING, "semantic index files are unreadable; rebuilding from embeddings", unreadable);
            indexes.clear();
            return OptionalLong.empty();
        }
    }

    public void persist() {
        applyLock.lock();
        try {
            if (indexedGeneration == persistedGeneration) {
                return;
            }
            Files.createDirectories(directory);
            for (Map.Entry<Integer, HnswIndex> entry : indexes.entrySet()) {
                Path target = directory.resolve("hnsw-" + entry.getKey() + ".idx");
                Path temporary = directory.resolve("hnsw-" + entry.getKey() + ".tmp");
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                    entry.getValue().writeTo(out);
                }
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            Properties properties = new Properties();
            properties.setProperty("generation", Long.toString(indexedGeneration));
            properties.setProperty("models", indexes.keySet().stream().map(String::valueOf).collect(Collectors.joining(",")));
            Path temporary = directory.resolve("state.tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                properties.store(out, null);
            }
            Files.move(temporary, directory.resolve("state"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            persistedGeneration = indexedGeneration;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "could not persist the semantic index", e);
        } finally {
            applyLock.unlock();
        }
    }

    public Encoder encoder() {
        return encoder;
    }

    public int model(String name) {
        return engine.dictionary().name(MODEL_NAMESPACE, name);
    }

    public long indexedGeneration() {
        return indexedGeneration;
    }

    private long rebuild() {
        try (Snapshot snapshot = engine.snapshot()) {
            snapshot.scan(Embedding.EMBEDDINGS).stream().forEach(entry -> index(entry.key(), entry.value()));
            indexedGeneration = snapshot.generation();
            return snapshot.generation();
        }
    }

    private void recover(long acknowledged, long firstRetained) {
        applyLock.lock();
        try {
            LOG.log(System.Logger.Level.WARNING, "change feed skipped generations {0} to {1}; rebuilding the semantic index",
                    acknowledged + 1, firstRetained - 1);
            indexes.clear();
            rebuild();
        } finally {
            applyLock.unlock();
        }
    }

    private void apply(CommitEvent event) {
        applyLock.lock();
        try {
            if (event.branch() == Branch.MAIN) {
                for (SlotChange<?> change : event.slots()) {
                    if (change.slot() == Embedding.EMBEDDINGS) {
                        change.after().map(Embedding.class::cast).ifPresentOrElse(
                                embedding -> index(change.key(), embedding),
                                () -> indexes.values().forEach(index -> index.remove(change.key())));
                    }
                }
                indexes.replaceAll((_, index) -> index.tombstoneRatio() > TOMBSTONE_LIMIT ? index.compacted() : index);
            }
            indexedGeneration = Math.max(indexedGeneration, event.generation());
        } finally {
            applyLock.unlock();
        }
        progressLock.lock();
        try {
            indexedGeneration = Math.max(indexedGeneration, event.generation());
            advanced.signalAll();
        } finally {
            progressLock.unlock();
        }
    }

    private void index(long atom, Embedding embedding) {
        float[] vector = Embedding.decode(payloads.read(embedding.payloadRef()));
        indexes.values().forEach(index -> index.remove(atom));
        indexes.computeIfAbsent(embedding.model(), _ -> new HnswIndex(16, 128)).upsert(atom, vector);
    }

    public List<Hit> nearest(View view, int model, float[] query, int k, Consistency consistency, LongPredicate admit) {
        if (view instanceof Transaction || view.branch() != Branch.MAIN || !awaitIndexed(view.generation(), consistency)) {
            return exhaustive(view, model, query, k, admit);
        }
        HnswIndex index = indexes.get(model);
        if (index == null) {
            return List.of();
        }
        int indexed = index.size();
        for (int candidates = Math.max(k * 4, 32); ; candidates *= 4) {
            List<Hit> hits = index.nearest(query, candidates, candidates * 2).stream()
                    .filter(neighbor -> admit.test(neighbor.id()))
                    .flatMap(neighbor -> verify(view, model, neighbor.id(), query).stream())
                    .sorted(Comparator.comparingDouble(Hit::distance))
                    .limit(k)
                    .toList();
            if (hits.size() >= k || candidates >= indexed) {
                return hits;
            }
        }
    }

    private boolean awaitIndexed(long snapshotGeneration, Consistency consistency) {
        long generation = Math.min(snapshotGeneration, engine.feed().lastGeneration());
        if (consistency == Consistency.SNAPSHOT || indexedGeneration >= generation) {
            return true;
        }
        long deadline = System.nanoTime() + FRESHNESS_TIMEOUT.toNanos();
        progressLock.lock();
        try {
            while (indexedGeneration < generation) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                advanced.await(remaining, TimeUnit.NANOSECONDS);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            progressLock.unlock();
        }
    }

    private Optional<Hit> verify(View view, int model, long atom, float[] query) {
        return view.get(Embedding.EMBEDDINGS, atom)
                .filter(embedding -> embedding.model() == model && view.exists(atom))
                .map(embedding -> new Hit(atom, distance(embedding, query)));
    }

    private List<Hit> exhaustive(View view, int model, float[] query, int k, LongPredicate admit) {
        return view.scan(Embedding.EMBEDDINGS).stream()
                .filter(entry -> entry.value().model() == model && admit.test(entry.key()))
                .map(entry -> new Hit(entry.key(), distance(entry.value(), query)))
                .sorted(Comparator.comparingDouble(Hit::distance))
                .limit(k)
                .toList();
    }

    private float distance(Embedding embedding, float[] query) {
        return Embedding.cosineDistance(Embedding.normalized(query), Embedding.normalized(Embedding.decode(payloads.read(embedding.payloadRef()))));
    }

    public Optional<float[]> vector(View view, long atom) {
        return view.get(Embedding.EMBEDDINGS, atom).map(embedding -> Embedding.decode(payloads.read(embedding.payloadRef())));
    }

    public List<Entry<Embedding>> embeddings(View view) {
        return view.scan(Embedding.EMBEDDINGS).stream().toList();
    }

    @Override
    public void close() {
        subscription.close();
        persist();
        hold.close();
    }
}
