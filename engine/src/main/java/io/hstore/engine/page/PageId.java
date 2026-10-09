package io.hstore.engine.page;

public record PageId(long index, int reuse) {

    static final int REUSE_BITS = 16;

    public static final long NONE = 0L;
    public static final int UNIT_BYTES = 64;
    public static final long MAX_INDEX = (1L << (Long.SIZE - REUSE_BITS)) - 1;

    public PageId {
        if (index < 1 || index > MAX_INDEX) {
            throw new IllegalArgumentException("page number out of range: " + index);
        }
        reuse &= (1 << REUSE_BITS) - 1;
    }

    public static int unitsFor(long bytes) {
        return Math.toIntExact((bytes + UNIT_BYTES - 1) / UNIT_BYTES);
    }

    public static long pack(long index, int reuse) {
        return new PageId(index, reuse).pack();
    }

    public static PageId unpack(long packed) {
        return new PageId(indexOf(packed), reuseOf(packed));
    }

    public static long indexOf(long packed) {
        return packed >>> REUSE_BITS;
    }

    public static int reuseOf(long packed) {
        return (int) packed & ((1 << REUSE_BITS) - 1);
    }

    public long pack() {
        return (index << REUSE_BITS) | reuse;
    }

    @Override
    public String toString() {
        return reuse == 0 ? "#" + index : "#" + index + "." + reuse;
    }
}
