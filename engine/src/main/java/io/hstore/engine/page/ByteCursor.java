// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class ByteCursor {

    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private MemorySegment segment;
    private final boolean growable;
    private long position;

    private ByteCursor(MemorySegment segment, boolean growable) {
        this.segment = segment;
        this.growable = growable;
    }

    public static ByteCursor over(MemorySegment segment) {
        return new ByteCursor(segment, false);
    }

    public static ByteCursor wrap(byte[] bytes) {
        return new ByteCursor(MemorySegment.ofArray(bytes), false);
    }

    public static ByteCursor fixed(int capacity) {
        return new ByteCursor(MemorySegment.ofArray(new byte[capacity]), false);
    }

    public static ByteCursor growable(int initialCapacity) {
        return new ByteCursor(MemorySegment.ofArray(new byte[Math.max(16, initialCapacity)]), true);
    }

    public static int varLongSize(long value) {
        return value == 0 ? 1 : (63 - Long.numberOfLeadingZeros(value)) / 7 + 1;
    }

    public static int signedVarLongSize(long value) {
        return varLongSize(zigzag(value));
    }

    public static int stringSize(String value) {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        return varLongSize(length) + length;
    }

    private static long zigzag(long value) {
        return (value << 1) ^ (value >> 63);
    }

    private static long unzigzag(long value) {
        return (value >>> 1) ^ -(value & 1);
    }

    public MemorySegment segment() {
        return segment;
    }

    public long position() {
        return position;
    }

    public ByteCursor position(long newPosition) {
        this.position = newPosition;
        return this;
    }

    public long remaining() {
        return segment.byteSize() - position;
    }

    public byte[] toByteArray() {
        return segment.asSlice(0, position).toArray(ValueLayout.JAVA_BYTE);
    }

    public MemorySegment written() {
        return segment.asSlice(0, position);
    }

    private void ensure(long bytes) {
        if (position + bytes <= segment.byteSize()) {
            return;
        }
        if (!growable) {
            throw new IndexOutOfBoundsException("cursor overflow at " + position + " + " + bytes + " > " + segment.byteSize());
        }
        long capacity = Math.max(segment.byteSize() * 2, position + bytes);
        MemorySegment grown = MemorySegment.ofArray(new byte[Math.toIntExact(capacity)]);
        MemorySegment.copy(segment, 0, grown, 0, position);
        segment = grown;
    }

    public ByteCursor putByte(int value) {
        ensure(1);
        segment.set(ValueLayout.JAVA_BYTE, position++, (byte) value);
        return this;
    }

    public int getByte() {
        return segment.get(ValueLayout.JAVA_BYTE, position++);
    }

    public int getUnsignedByte() {
        return getByte() & 0xFF;
    }

    public ByteCursor putInt(int value) {
        ensure(4);
        segment.set(INT, position, value);
        position += 4;
        return this;
    }

    public int getInt() {
        int value = segment.get(INT, position);
        position += 4;
        return value;
    }

    public ByteCursor putLong(long value) {
        ensure(8);
        segment.set(LONG, position, value);
        position += 8;
        return this;
    }

    public long getLong() {
        long value = segment.get(LONG, position);
        position += 8;
        return value;
    }

    public ByteCursor putDouble(double value) {
        return putLong(Double.doubleToRawLongBits(value));
    }

    public double getDouble() {
        return Double.longBitsToDouble(getLong());
    }

    public ByteCursor putVarLong(long value) {
        ensure(10);
        long v = value;
        while ((v & ~0x7FL) != 0) {
            segment.set(ValueLayout.JAVA_BYTE, position++, (byte) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        segment.set(ValueLayout.JAVA_BYTE, position++, (byte) v);
        return this;
    }

    public long getVarLong() {
        long result = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, position++);
            result |= (long) (b & 0x7F) << shift;
            if (b >= 0) {
                return result;
            }
        }
        throw new IllegalStateException("malformed varint at " + (position - 10));
    }

    public ByteCursor putVarInt(int value) {
        return putVarLong(Integer.toUnsignedLong(value));
    }

    public int getVarInt() {
        return Math.toIntExact(getVarLong());
    }

    public ByteCursor putSignedVarLong(long value) {
        return putVarLong(zigzag(value));
    }

    public long getSignedVarLong() {
        return unzigzag(getVarLong());
    }

    public ByteCursor putBytes(byte[] bytes) {
        ensure(bytes.length);
        MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, position, bytes.length);
        position += bytes.length;
        return this;
    }

    public ByteCursor putSegment(MemorySegment source) {
        ensure(source.byteSize());
        MemorySegment.copy(source, 0, segment, position, source.byteSize());
        position += source.byteSize();
        return this;
    }

    public byte[] getBytes(int length) {
        byte[] bytes = new byte[length];
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, position, bytes, 0, length);
        position += length;
        return bytes;
    }

    public ByteCursor putBlob(byte[] bytes) {
        putVarInt(bytes.length);
        return putBytes(bytes);
    }

    public byte[] getBlob() {
        return getBytes(getVarInt());
    }

    public ByteCursor putString(String value) {
        return putBlob(value.getBytes(StandardCharsets.UTF_8));
    }

    public String getString() {
        return new String(getBlob(), StandardCharsets.UTF_8);
    }

    public ByteCursor skip(long bytes) {
        ensure(bytes);
        position += bytes;
        return this;
    }
}
