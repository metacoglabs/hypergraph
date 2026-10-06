// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import io.hstore.engine.HStoreException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

final class SegmentFile implements AutoCloseable {

    private static final int FIRST_READ = 4096;

    private final int id;
    private final Path path;
    private final FileChannel channel;
    private final AtomicBoolean dirty = new AtomicBoolean();

    private SegmentFile(int id, Path path, FileChannel channel) {
        this.id = id;
        this.path = path;
        this.channel = channel;
    }

    static SegmentFile open(int id, Path path) {
        try {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            return new SegmentFile(id, path, channel);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open segment " + path, e);
        }
    }

    static Path pathFor(Path directory, int id) {
        return directory.resolve("%08x.seg".formatted(id));
    }

    static int idOf(Path file) {
        String name = file.getFileName().toString();
        return Integer.parseInt(name.substring(0, name.indexOf('.')), 16);
    }

    int id() {
        return id;
    }

    long unitsOnDisk() {
        try {
            return PageId.unitsFor(channel.size());
        } catch (IOException e) {
            throw HStoreException.io("cannot size segment " + path, e);
        }
    }

    MemorySegment read(long position, int maximum) {
        ByteBuffer first = ByteBuffer.allocate(Math.min(maximum, FIRST_READ));
        fill(first, position);
        if (first.position() < PageHeader.SIZE) {
            return MemorySegment.ofBuffer(first.flip());
        }
        int length = PageHeader.SIZE + PageHeader.payloadLength(MemorySegment.ofArray(first.array()));
        if (length <= first.position() || length > maximum) {
            return MemorySegment.ofBuffer(first.flip());
        }
        ByteBuffer whole = ByteBuffer.allocate(length).put(first.flip());
        fill(whole, position);
        return MemorySegment.ofBuffer(whole.flip());
    }

    private void fill(ByteBuffer buffer, long position) {
        try {
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, position + buffer.position()) < 0) {
                    return;
                }
            }
        } catch (IOException e) {
            throw HStoreException.io("read failed in segment " + id + " at byte " + position, e);
        }
    }

    void write(long position, MemorySegment image) {
        ByteBuffer buffer = image.asByteBuffer();
        try {
            while (buffer.hasRemaining()) {
                channel.write(buffer, position + buffer.position());
            }
            dirty.set(true);
        } catch (IOException e) {
            throw HStoreException.io("write failed in segment " + id + " at byte " + position, e);
        }
    }

    void force() {
        if (dirty.getAndSet(false)) {
            try {
                channel.force(false);
            } catch (IOException e) {
                dirty.set(true);
                throw HStoreException.io("sync failed for segment " + id, e);
            }
        }
    }

    void delete() {
        close();
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
