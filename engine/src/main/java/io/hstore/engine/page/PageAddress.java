package io.hstore.engine.page;

public record PageAddress(int segment, int offset, int units) {

    static final int SEGMENT_BITS = 24;
    static final int OFFSET_BITS = 24;
    static final int UNITS_BITS = 16;

    public static final long NONE = 0L;
    public static final int MAX_SEGMENT = (1 << SEGMENT_BITS) - 1;
    public static final int MAX_OFFSET = (1 << OFFSET_BITS) - 1;
    public static final int MAX_UNITS = (1 << UNITS_BITS) - 1;

    public PageAddress {
        if (segment < 1 || segment > MAX_SEGMENT || offset < 0 || offset > MAX_OFFSET || units < 1 || units > MAX_UNITS) {
            throw new IllegalArgumentException("page address out of range: " + segment + ":" + offset + "+" + units);
        }
    }

    public static PageAddress unpack(long packed) {
        return new PageAddress(segmentOf(packed), offsetOf(packed), unitsOf(packed));
    }

    public static int segmentOf(long packed) {
        return (int) (packed >>> (OFFSET_BITS + UNITS_BITS));
    }

    public static int offsetOf(long packed) {
        return (int) (packed >>> UNITS_BITS) & MAX_OFFSET;
    }

    public static int unitsOf(long packed) {
        return (int) packed & MAX_UNITS;
    }

    public long position() {
        return (long) offset * PageId.UNIT_BYTES;
    }

    public long bytes() {
        return (long) units * PageId.UNIT_BYTES;
    }

    public long pack() {
        return ((long) segment << (OFFSET_BITS + UNITS_BITS)) | ((long) offset << UNITS_BITS) | units;
    }

    @Override
    public String toString() {
        return segment + ":" + offset + "+" + units;
    }
}
