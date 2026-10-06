// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import java.lang.foreign.MemorySegment;
import java.util.zip.CRC32C;

public final class Checksums {

    private Checksums() {
    }

    public static int crc32c(MemorySegment segment) {
        return crc32c(segment, 0, segment.byteSize());
    }

    public static int crc32c(MemorySegment segment, long offset, long length) {
        CRC32C crc = new CRC32C();
        crc.update(segment.asSlice(offset, length).asByteBuffer());
        return (int) crc.getValue();
    }

    public static int crc32c(MemorySegment first, MemorySegment second) {
        CRC32C crc = new CRC32C();
        crc.update(first.asByteBuffer());
        crc.update(second.asByteBuffer());
        return (int) crc.getValue();
    }

    public static int crc32c(byte[] bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes);
        return (int) crc.getValue();
    }
}
