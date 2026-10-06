package io.hstore.engine.tree;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

public final class NodeCache {

    private static final int SHARDS = 16;
    private static final long MIN_SHARD_BYTES = 64 * 1024;

    private final Shard[] shards = new Shard[SHARDS];
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public NodeCache(long capacityBytes) {
        long perShard = Math.max(MIN_SHARD_BYTES, capacityBytes / SHARDS);
        for (int i = 0; i < SHARDS; i++) {
            shards[i] = new Shard(perShard);
        }
    }

    public Node get(long pageId) {
        Node node = shard(pageId).get(pageId);
        (node == null ? misses : hits).increment();
        return node;
    }

    public void put(long pageId, Node node, int bytes) {
        shard(pageId).put(pageId, node, bytes);
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

    public long residentBytes() {
        long total = 0;
        for (Shard shard : shards) {
            total += shard.bytes;
        }
        return total;
    }

    private Shard shard(long pageId) {
        return shards[(int) (Hashing.mix(pageId) >>> 60) & (SHARDS - 1)];
    }

    private static final class Slot {
        final long pageId;
        final Node node;
        final int bytes;
        volatile boolean referenced;
        Slot previous;
        Slot next;

        Slot(long pageId, Node node, int bytes) {
            this.pageId = pageId;
            this.node = node;
            this.bytes = bytes;
        }
    }

    private static final class Shard {
        private final ConcurrentHashMap<Long, Slot> entries = new ConcurrentHashMap<>();
        private final ReentrantLock admission = new ReentrantLock();
        private final long budget;
        private volatile long bytes;
        private Slot hand;

        Shard(long budget) {
            this.budget = budget;
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

        void put(long pageId, Node node, int weight) {
            admission.lock();
            try {
                Slot existing = entries.get(pageId);
                if (existing != null && existing.node == node) {
                    return;
                }
                if (existing != null) {
                    unlink(existing);
                    entries.remove(pageId, existing);
                }
                if (weight > budget) {
                    return;
                }
                while (bytes + weight > budget) {
                    evict();
                }
                Slot slot = new Slot(pageId, node, weight);
                link(slot);
                entries.put(pageId, slot);
            } finally {
                admission.unlock();
            }
        }

        private void evict() {
            while (hand.referenced) {
                hand.referenced = false;
                hand = hand.next;
            }
            Slot victim = hand;
            unlink(victim);
            entries.remove(victim.pageId, victim);
        }

        private void link(Slot slot) {
            if (hand == null) {
                slot.previous = slot;
                slot.next = slot;
                hand = slot;
            } else {
                slot.next = hand;
                slot.previous = hand.previous;
                hand.previous.next = slot;
                hand.previous = slot;
            }
            bytes += slot.bytes;
        }

        private void unlink(Slot slot) {
            if (slot.next == slot) {
                hand = null;
            } else {
                slot.previous.next = slot.next;
                slot.next.previous = slot.previous;
                if (hand == slot) {
                    hand = slot.next;
                }
            }
            slot.previous = null;
            slot.next = null;
            bytes -= slot.bytes;
        }

        void clear() {
            admission.lock();
            try {
                entries.clear();
                hand = null;
                bytes = 0;
            } finally {
                admission.unlock();
            }
        }
    }
}
