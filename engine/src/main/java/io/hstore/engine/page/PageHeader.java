package io.hstore.engine.page;

import io.hstore.engine.HStoreException;

import java.lang.foreign.MemorySegment;

public record PageHeader(
        PageType type,
        int schema,
        int flags,
        long pageId,
        long creationEpoch,
        int payloadLength,
        int slotCount,
        long logicalCount,
        long lowerBound,
        long upperBound,
        int height,
        long fingerprint) {

    public static final int MAGIC = 0x47505348;
    public static final int FORMAT = 1;
    public static final int SIZE = 80;
    private static final int PAGE_ID_OFFSET = 8;
    private static final int PAYLOAD_LENGTH_OFFSET = 24;
    private static final int CHECKSUM_OFFSET = 72;

    public void writeTo(ByteCursor page) {
        page.position(0)
                .putInt(MAGIC)
                .putByte(FORMAT)
                .putByte(type.code())
                .putByte(schema)
                .putByte(flags)
                .putLong(pageId)
                .putLong(creationEpoch)
                .putInt(payloadLength)
                .putInt(slotCount)
                .putLong(logicalCount)
                .putLong(lowerBound)
                .putLong(upperBound)
                .putInt(height)
                .putInt(0)
                .putLong(fingerprint)
                .putInt(0)
                .putInt(0);
    }

    public static void assign(MemorySegment page, long pageId) {
        ByteCursor.over(page).position(PAGE_ID_OFFSET).putLong(pageId);
        seal(page, payloadLength(page));
    }

    public static int payloadLength(MemorySegment page) {
        return ByteCursor.over(page).position(PAYLOAD_LENGTH_OFFSET).getInt();
    }

    public static void seal(MemorySegment page, int payloadLength) {
        int checksum = Checksums.crc32c(page.asSlice(0, CHECKSUM_OFFSET), page.asSlice(SIZE, payloadLength));
        ByteCursor.over(page).position(CHECKSUM_OFFSET).putInt(checksum);
    }

    public static PageHeader verify(MemorySegment page, long expectedPageId) {
        ByteCursor in = ByteCursor.over(page);
        if (in.getInt() != MAGIC) {
            throw HStoreException.corrupt(expectedPageId, "bad page magic");
        }
        int format = in.getUnsignedByte();
        if (format != FORMAT) {
            throw HStoreException.corrupt(expectedPageId, "unsupported page format " + format);
        }
        PageType type = PageType.of(in.getUnsignedByte());
        int schema = in.getUnsignedByte();
        int flags = in.getUnsignedByte();
        long pageId = in.getLong();
        long epoch = in.getLong();
        int payloadLength = in.getInt();
        int slotCount = in.getInt();
        long logicalCount = in.getLong();
        long lower = in.getLong();
        long upper = in.getLong();
        int height = in.getInt();
        in.getInt();
        long fingerprint = in.getLong();
        int checksum = in.getInt();
        if (pageId != expectedPageId) {
            throw HStoreException.corrupt(expectedPageId, "page identity mismatch, found " + PageId.unpack(pageId));
        }
        if (payloadLength < 0 || SIZE + (long) payloadLength > page.byteSize()) {
            throw HStoreException.corrupt(expectedPageId, "payload length out of bounds");
        }
        int actual = Checksums.crc32c(page.asSlice(0, CHECKSUM_OFFSET), page.asSlice(SIZE, payloadLength));
        if (actual != checksum) {
            throw HStoreException.corrupt(expectedPageId, "checksum mismatch");
        }
        return new PageHeader(type, schema, flags, pageId, epoch, payloadLength, slotCount, logicalCount, lower, upper, height, fingerprint);
    }
}
