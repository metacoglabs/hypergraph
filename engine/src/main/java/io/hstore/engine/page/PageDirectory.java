package io.hstore.engine.page;

import io.hstore.engine.HStoreException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

final class PageDirectory implements AutoCloseable {

    static final String FILE = "pages.dir";

    private static final int MAGIC = 0x52494450;
    private static final int FORMAT = 1;
    private static final long HEADER = 64;
    private static final long NEXT_OFFSET = 8;
    private static final int CHUNK_SHIFT = 23;
    private static final long CHUNK_BYTES = 1L << CHUNK_SHIFT;
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle ENTRY = LONG.varHandle();

    private final Path path;
    private final FileChannel channel;
    private volatile MemorySegment[] chunks = new MemorySegment[0];
    private long next;

    private PageDirectory(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
    }

    static PageDirectory open(Path path) {
        try {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            PageDirectory directory = new PageDirectory(path, channel);
            boolean fresh = channel.size() == 0;
            directory.mapThrough(fresh ? 0 : (int) ((channel.size() - 1) >>> CHUNK_SHIFT));
            MemorySegment header = directory.chunks[0];
            if (fresh) {
                header.set(INT, 0, MAGIC);
                header.set(INT, 4, FORMAT);
                header.set(LONG, NEXT_OFFSET, 1L);
            } else if (header.get(INT, 0) != MAGIC || header.get(INT, 4) != FORMAT) {
                channel.close();
                throw HStoreException.invalid("page directory " + path + " has an unknown format");
            }
            directory.next = header.get(LONG, NEXT_OFFSET);
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open page directory " + path, e);
        }
    }

    long get(long index) {
        long position = HEADER + index * Long.BYTES;
        MemorySegment[] mapped = chunks;
        int chunk = (int) (position >>> CHUNK_SHIFT);
        return chunk < mapped.length ? (long) ENTRY.getAcquire(mapped[chunk], position & (CHUNK_BYTES - 1)) : PageAddress.NONE;
    }

    void put(long index, long address) {
        long position = HEADER + index * Long.BYTES;
        int chunk = (int) (position >>> CHUNK_SHIFT);
        if (chunk >= chunks.length) {
            mapThrough(chunk);
        }
        ENTRY.setRelease(chunks[chunk], position & (CHUNK_BYTES - 1), address);
    }

    long allocate() {
        long index = next;
        if (index > PageId.MAX_INDEX) {
            throw HStoreException.limit("page numbers exhausted");
        }
        reserve(index);
        return index;
    }

    void reserve(long index) {
        if (index >= next) {
            next = index + 1;
            chunks[0].set(LONG, NEXT_OFFSET, next);
        }
    }

    long next() {
        return next;
    }

    void force() {
        for (MemorySegment chunk : chunks) {
            chunk.force();
        }
    }

    private void mapThrough(int last) {
        MemorySegment[] mapped = chunks;
        if (last < mapped.length) {
            return;
        }
        MemorySegment[] grown = Arrays.copyOf(mapped, last + 1);
        try {
            for (int chunk = mapped.length; chunk <= last; chunk++) {
                grown[chunk] = channel.map(FileChannel.MapMode.READ_WRITE, chunk * CHUNK_BYTES, CHUNK_BYTES, Arena.ofAuto());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot grow page directory " + path, e);
        }
        chunks = grown;
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot close page directory " + path, e);
        }
    }
}
