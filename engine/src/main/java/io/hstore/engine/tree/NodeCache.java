// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

public final class NodeCache {

    private static final int SHARDS = 16;

    private final Shard[] shards = new Shard[SHARDS];
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public NodeCache(int capacity) {
        int perShard = Math.max(16, capacity / SHARDS);
        for (int i = 0; i < SHARDS; i++) {
            shards[i] = new Shard(perShard);
        }
    }

    public Node get(long pageId) {
        Node node = shard(pageId).get(pageId);
        (node == null ? misses : hits).increment();
        return node;
    }

    public void put(long pageId, Node node) {
        shard(pageId).put(pageId, node);
    }

    public void clear() {
        for (Shard shard : shards) {
            shard.clear();
        }
    }

    public long hits() {
        return hits.sum();
    }

    public long misses() {
        return misses.sum();
    }

    private Shard shard(long pageId) {
        return shards[(int) (Hashing.mix(pageId) >>> 60) & (SHARDS - 1)];
    }

    private static final class Slot {
        final long pageId;
        final Node node;
        volatile boolean referenced;

        Slot(long pageId, Node node) {
            this.pageId = pageId;
            this.node = node;
        }
    }

    private static final class Shard {
        private final ConcurrentHashMap<Long, Slot> entries;
        private final Slot[] ring;
        private final ReentrantLock admission = new ReentrantLock();
        private int hand;

        Shard(int capacity) {
            this.entries = new ConcurrentHashMap<>(capacity * 4 / 3 + 1);
            this.ring = new Slot[capacity];
        }

        Node get(long pageId) {
            Slot slot = entries.get(pageId);
            if (slot == null) {
                return null;
            }
            if (!slot.referenced) {
                slot.referenced = true;
            }
            return slot.node;
        }

        void put(long pageId, Node node) {
            admission.lock();
            try {
                Slot existing = entries.get(pageId);
                if (existing != null && existing.node == node) {
                    return;
                }
                Slot slot = new Slot(pageId, node);
                if (existing != null) {
                    for (int i = 0; i < ring.length; i++) {
                        if (ring[i] == existing) {
                            ring[i] = slot;
                            break;
                        }
                    }
                    entries.put(pageId, slot);
                    return;
                }
                while (ring[hand] != null && ring[hand].referenced) {
                    ring[hand].referenced = false;
                    hand = (hand + 1) % ring.length;
                }
                if (ring[hand] != null) {
                    entries.remove(ring[hand].pageId, ring[hand]);
                }
                ring[hand] = slot;
                entries.put(pageId, slot);
                hand = (hand + 1) % ring.length;
            } finally {
                admission.unlock();
            }
        }

        void clear() {
            admission.lock();
            try {
                entries.clear();
                Arrays.fill(ring, null);
                hand = 0;
            } finally {
                admission.unlock();
            }
        }
    }
}
