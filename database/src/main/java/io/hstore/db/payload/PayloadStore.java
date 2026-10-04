package io.hstore.db.payload;

import io.hstore.engine.HStoreException;
import io.hstore.engine.page.Checksums;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

public final class PayloadStore implements AutoCloseable {

    private static final int HEADER = 8;
    private static final int OFFSET_BITS = 40;
    private static final long SEGMENT_LIMIT = 1L << 30;

    private final Path directory;
    private final Map<Integer, FileChannel> segments = new ConcurrentHashMap<>();
    private final ReentrantLock appendLock = new ReentrantLock();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private int active;
    private long end;

    private PayloadStore(Path directory) {
        this.directory = directory;
    }

    public static PayloadStore open(Path directory) {
        PayloadStore store = new PayloadStore(directory);
        try {
            Files.createDirectories(directory);
            try (Stream<Path> listing = Files.list(directory)) {
                listing.filter(path -> path.getFileName().toString().endsWith(".pay")).forEach(path -> {
                    String name = path.getFileName().toString();
                    int id = Integer.parseInt(name.substring(0, name.indexOf('.')), 16);
                    store.segments.put(id, store.channel(path));
                    store.active = Math.max(store.active, id);
                });
            }
            if (store.active == 0) {
                store.roll();
            } else {
                store.end = store.segments.get(store.active).size();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open payload store " + directory, e);
        }
        return store;
    }

    private FileChannel channel(Path path) {
        try {
            return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void roll() {
        FileChannel previous = segments.get(active);
        if (previous != null) {
            try {
                previous.force(false);
            } catch (IOException e) {
                throw HStoreException.io("payload sync failed", e);
            }
        }
        active++;
        segments.put(active, channel(directory.resolve("%06x.pay".formatted(active))));
        end = 0;
    }

    public long write(byte[] bytes) {
        ByteBuffer record = ByteBuffer.allocate(HEADER + bytes.length);
        record.putInt(bytes.length).putInt(Checksums.crc32c(bytes)).put(bytes).flip();
        appendLock.lock();
        try {
            if (end + record.remaining() > SEGMENT_LIMIT && end > 0) {
                roll();
            }
            long offset = end;
            FileChannel channel = segments.get(active);
            long position = offset;
            while (record.hasRemaining()) {
                position += channel.write(record, position);
            }
            end = position;
            dirty.set(true);
            return ((long) active << OFFSET_BITS) | offset;
        } catch (IOException e) {
            throw HStoreException.io("payload append failed", e);
        } finally {
            appendLock.unlock();
        }
    }

    public byte[] read(long ref) {
        FileChannel channel = segments.get((int) (ref >>> OFFSET_BITS));
        long offset = ref & ((1L << OFFSET_BITS) - 1);
        if (channel == null) {
            throw HStoreException.invalid("payload reference " + Long.toHexString(ref) + " points to a missing segment");
        }
        try {
            ByteBuffer header = ByteBuffer.allocate(HEADER);
            readFully(channel, header, offset);
            int length = header.flip().getInt();
            int crc = header.getInt();
            ByteBuffer body = ByteBuffer.allocate(length);
            readFully(channel, body, offset + HEADER);
            byte[] bytes = body.array();
            if (Checksums.crc32c(bytes) != crc) {
                throw HStoreException.invalid("payload " + Long.toHexString(ref) + " failed its checksum");
            }
            return bytes;
        } catch (IOException e) {
            throw HStoreException.io("payload read failed", e);
        }
    }

    public String readText(long ref) {
        return new String(read(ref), StandardCharsets.UTF_8);
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                throw new IOException("payload truncated");
            }
        }
    }

    public void sync() {
        if (dirty.getAndSet(false)) {
            try {
                segments.get(active).force(false);
            } catch (IOException e) {
                dirty.set(true);
                throw HStoreException.io("payload sync failed", e);
            }
        }
    }

    @Override
    public void close() {
        sync();
        segments.values().forEach(channel -> {
            try {
                channel.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
