// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.semantic;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

public final class HnswIndex {

    public record Neighbor(long id, float distance) {
    }

    private record Candidate(int node, float distance) {
    }

    private static final Comparator<Candidate> NEAREST_FIRST = Comparator.comparingDouble(Candidate::distance);
    private static final Comparator<Candidate> FARTHEST_FIRST = NEAREST_FIRST.reversed();

    private static final int MAGIC = 0x484E5357;

    private final int connections;
    private final int efConstruction;
    private final double levelScale;
    private final RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(0x5EED);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<float[]> vectors = new ArrayList<>();
    private final List<Long> ids = new ArrayList<>();
    private final List<int[][]> links = new ArrayList<>();
    private final Map<Long, Integer> byId = new HashMap<>();
    private final BitSet deleted = new BitSet();
    private int entry = -1;
    private int topLevel = -1;

    public HnswIndex(int connections, int efConstruction) {
        this.connections = connections;
        this.efConstruction = efConstruction;
        this.levelScale = 1 / Math.log(connections);
    }

    public int size() {
        lock.readLock().lock();
        try {
            return byId.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void upsert(long id, float[] vector) {
        float[] unit = Embedding.normalized(vector);
        lock.writeLock().lock();
        try {
            Integer existing = byId.remove(id);
            if (existing != null) {
                deleted.set(existing);
            }
            int node = vectors.size();
            int level = (int) Math.floor(-Math.log(1 - random.nextDouble()) * levelScale);
            vectors.add(unit);
            ids.add(id);
            int[][] layers = new int[level + 1][];
            Arrays.setAll(layers, _ -> new int[0]);
            links.add(layers);
            byId.put(id, node);
            if (entry < 0) {
                entry = node;
                topLevel = level;
                return;
            }
            int current = entry;
            for (int layer = topLevel; layer > level; layer--) {
                current = greedy(unit, current, layer);
            }
            for (int layer = Math.min(level, topLevel); layer >= 0; layer--) {
                List<Candidate> nearest = search(unit, current, efConstruction, layer);
                int limit = layer == 0 ? connections * 2 : connections;
                int[] chosen = nearest.stream().sorted(NEAREST_FIRST).limit(limit).mapToInt(Candidate::node).toArray();
                links.get(node)[layer] = chosen;
                for (int neighbor : chosen) {
                    connect(neighbor, node, layer, limit);
                }
                current = nearest.stream().min(NEAREST_FIRST).map(Candidate::node).orElse(current);
            }
            if (level > topLevel) {
                topLevel = level;
                entry = node;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public double tombstoneRatio() {
        lock.readLock().lock();
        try {
            return vectors.isEmpty() ? 0 : (double) deleted.cardinality() / vectors.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public HnswIndex compacted() {
        lock.readLock().lock();
        try {
            HnswIndex rebuilt = new HnswIndex(connections, efConstruction);
            byId.forEach((id, node) -> rebuilt.upsert(id, vectors.get(node)));
            return rebuilt;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void writeTo(DataOutputStream out) throws IOException {
        lock.readLock().lock();
        try {
            out.writeInt(MAGIC);
            out.writeInt(connections);
            out.writeInt(efConstruction);
            out.writeInt(entry);
            out.writeInt(topLevel);
            out.writeInt(vectors.size());
            for (int node = 0; node < vectors.size(); node++) {
                out.writeLong(ids.get(node));
                out.writeBoolean(deleted.get(node));
                float[] vector = vectors.get(node);
                out.writeInt(vector.length);
                for (float component : vector) {
                    out.writeFloat(component);
                }
                int[][] layers = links.get(node);
                out.writeInt(layers.length);
                for (int[] layer : layers) {
                    out.writeInt(layer.length);
                    for (int neighbor : layer) {
                        out.writeInt(neighbor);
                    }
                }
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    public static HnswIndex readFrom(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new IOException("not an HNSW index file");
        }
        HnswIndex index = new HnswIndex(in.readInt(), in.readInt());
        index.entry = in.readInt();
        index.topLevel = in.readInt();
        int count = in.readInt();
        for (int node = 0; node < count; node++) {
            long id = in.readLong();
            boolean removed = in.readBoolean();
            float[] vector = new float[in.readInt()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = in.readFloat();
            }
            int[][] layers = new int[in.readInt()][];
            for (int layer = 0; layer < layers.length; layer++) {
                layers[layer] = new int[in.readInt()];
                for (int i = 0; i < layers[layer].length; i++) {
                    layers[layer][i] = in.readInt();
                }
            }
            index.ids.add(id);
            index.vectors.add(vector);
            index.links.add(layers);
            if (removed) {
                index.deleted.set(node);
            } else {
                index.byId.put(id, node);
            }
        }
        return index;
    }

    public void remove(long id) {
        lock.writeLock().lock();
        try {
            Integer node = byId.remove(id);
            if (node != null) {
                deleted.set(node);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Neighbor> nearest(float[] query, int k, int ef) {
        float[] unit = Embedding.normalized(query);
        lock.readLock().lock();
        try {
            if (entry < 0) {
                return List.of();
            }
            int current = entry;
            for (int layer = topLevel; layer > 0; layer--) {
                current = greedy(unit, current, layer);
            }
            return search(unit, current, Math.max(ef, k), 0).stream()
                    .filter(candidate -> !deleted.get(candidate.node()))
                    .sorted(NEAREST_FIRST)
                    .limit(k)
                    .map(candidate -> new Neighbor(ids.get(candidate.node()), candidate.distance()))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void connect(int from, int to, int layer, int limit) {
        int[][] layers = links.get(from);
        if (layer >= layers.length) {
            return;
        }
        int[] existing = layers[layer];
        int[] grown = Arrays.copyOf(existing, existing.length + 1);
        grown[existing.length] = to;
        if (grown.length > limit) {
            float[] origin = vectors.get(from);
            grown = Arrays.stream(grown).boxed()
                    .sorted(Comparator.comparingDouble(node -> Embedding.cosineDistance(origin, vectors.get(node))))
                    .limit(limit)
                    .mapToInt(Integer::intValue)
                    .toArray();
        }
        layers[layer] = grown;
    }

    private int greedy(float[] query, int start, int layer) {
        int current = start;
        float best = Embedding.cosineDistance(query, vectors.get(current));
        boolean improved = true;
        while (improved) {
            improved = false;
            int[][] layers = links.get(current);
            if (layer >= layers.length) {
                break;
            }
            for (int neighbor : layers[layer]) {
                float distance = Embedding.cosineDistance(query, vectors.get(neighbor));
                if (distance < best) {
                    best = distance;
                    current = neighbor;
                    improved = true;
                }
            }
        }
        return current;
    }

    private List<Candidate> search(float[] query, int start, int ef, int layer) {
        BitSet visited = new BitSet(vectors.size());
        PriorityQueue<Candidate> frontier = new PriorityQueue<>(NEAREST_FIRST);
        PriorityQueue<Candidate> results = new PriorityQueue<>(FARTHEST_FIRST);
        Candidate first = new Candidate(start, Embedding.cosineDistance(query, vectors.get(start)));
        frontier.add(first);
        results.add(first);
        visited.set(start);
        while (!frontier.isEmpty()) {
            Candidate closest = frontier.poll();
            if (results.size() >= ef && closest.distance() > results.peek().distance()) {
                break;
            }
            int[][] layers = links.get(closest.node());
            if (layer >= layers.length) {
                continue;
            }
            for (int neighbor : layers[layer]) {
                if (visited.get(neighbor)) {
                    continue;
                }
                visited.set(neighbor);
                Candidate candidate = new Candidate(neighbor, Embedding.cosineDistance(query, vectors.get(neighbor)));
                if (results.size() < ef || candidate.distance() < results.peek().distance()) {
                    frontier.add(candidate);
                    results.add(candidate);
                    if (results.size() > ef) {
                        results.poll();
                    }
                }
            }
        }
        return new ArrayList<>(results);
    }
}
