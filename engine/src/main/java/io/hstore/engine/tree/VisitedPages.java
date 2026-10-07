package io.hstore.engine.tree;

public final class VisitedPages {

    @FunctionalInterface
    public interface Consumer {
        void accept(long pageId, int units);
    }

    private static final long EMPTY = 0L;

    private long[] keys;
    private int[] units;
    private int size;

    public VisitedPages() {
        this(1 << 10);
    }

    public VisitedPages(long expected) {
        int capacity = Integer.highestOneBit((int) Math.min(1 << 30, Math.max(16, expected * 10 / 6))) << 1;
        keys = new long[capacity];
        units = new int[capacity];
    }

    public boolean add(long pageId, int pageUnits) {
        if (pageId == EMPTY) {
            throw new IllegalArgumentException("page id 0 is not a page");
        }
        if ((size + 1) * 10L > keys.length * 6L) {
            grow();
        }
        int slot = slot(pageId, keys.length);
        while (keys[slot] != EMPTY) {
            if (keys[slot] == pageId) {
                return false;
            }
            slot = (slot + 1) & (keys.length - 1);
        }
        keys[slot] = pageId;
        units[slot] = pageUnits;
        size++;
        return true;
    }

    public boolean contains(long pageId) {
        int slot = slot(pageId, keys.length);
        while (keys[slot] != EMPTY) {
            if (keys[slot] == pageId) {
                return true;
            }
            slot = (slot + 1) & (keys.length - 1);
        }
        return false;
    }

    public int size() {
        return size;
    }

    public void forEach(Consumer consumer) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != EMPTY) {
                consumer.accept(keys[i], units[i]);
            }
        }
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldUnits = units;
        keys = new long[oldKeys.length * 2];
        units = new int[oldKeys.length * 2];
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                int slot = slot(oldKeys[i], keys.length);
                while (keys[slot] != EMPTY) {
                    slot = (slot + 1) & (keys.length - 1);
                }
                keys[slot] = oldKeys[i];
                units[slot] = oldUnits[i];
            }
        }
    }

    private static int slot(long pageId, int capacity) {
        return (int) (Hashing.mix(pageId) >>> 32) & (capacity - 1);
    }
}
