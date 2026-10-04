package io.hstore.engine.page;

public record PageId(int segment, int offset, int generation) {

    static final int SEGMENT_BITS = 24;
    static final int OFFSET_BITS = 24;
    static final int GENERATION_BITS = 16;

    public static final long NONE = 0L;
    public static final int UNIT_BYTES = 64;
    public static final int MAX_SEGMENT = (1 << SEGMENT_BITS) - 1;
    public static final int MAX_OFFSET = (1 << OFFSET_BITS) - 1;

    public PageId {
        if (segment < 1 || segment > MAX_SEGMENT || offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("page address out of range: " + segment + ":" + offset);
        }
        generation &= (1 << GENERATION_BITS) - 1;
    }

    public static int unitsFor(long bytes) {
        return Math.toIntExact((bytes + UNIT_BYTES - 1) / UNIT_BYTES);
    }

    public static long pack(int segment, int offset, long epoch) {
        return new PageId(segment, offset, (int) epoch).pack();
    }

    public static PageId unpack(long packed) {
        return new PageId(segmentOf(packed), offsetOf(packed), generationOf(packed));
    }

    public static int segmentOf(long packed) {
        return (int) (packed >>> (OFFSET_BITS + GENERATION_BITS));
    }

    public static int offsetOf(long packed) {
        return (int) (packed >>> GENERATION_BITS) & MAX_OFFSET;
    }

    public static int generationOf(long packed) {
        return (int) packed & ((1 << GENERATION_BITS) - 1);
    }

    public long pack() {
        return ((long) segment << (OFFSET_BITS + GENERATION_BITS)) | ((long) offset << GENERATION_BITS) | generation;
    }

    @Override
    public String toString() {
        return segment + ":" + offset + "@" + generation;
    }
}
