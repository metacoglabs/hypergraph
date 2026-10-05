package io.hstore.engine.wal;

import io.hstore.engine.HStoreException;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.page.Checksums;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class WriteAheadLog implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("hstore.wal");

    public record Positioned(long lsn, long next, WalRecord record) {
    }

    private static final int HEADER = 25;
    private static final int MAX_RECORD = 1 << 30;

    private final Path directory;
    private final long segmentBytes;
    private final ReentrantLock lock = new ReentrantLock();
    private final NavigableMap<Long, Path> segments = new TreeMap<>();
    private final AtomicLong bytesAppended = new AtomicLong();
    private FileChannel active;
    private long activeStart;
    private long end;
    private boolean dirty;

    private WriteAheadLog(Path directory, long segmentBytes) {
        this.directory = directory;
        this.segmentBytes = segmentBytes;
    }

    public static WriteAheadLog open(Path directory, long segmentBytes) {
        WriteAheadLog log = new WriteAheadLog(directory, segmentBytes);
        log.restore();
        return log;
    }

    private void restore() {
        try {
            Files.createDirectories(directory);
            try (Stream<Path> listing = Files.list(directory)) {
                listing.filter(path -> path.getFileName().toString().endsWith(".wal"))
                        .forEach(path -> segments.put(startOf(path), path));
            }
            if (segments.isEmpty()) {
                return;
            }
            Map.Entry<Long, Path> last = segments.lastEntry();
            long validEnd = last.getKey();
            for (Positioned positioned : (Iterable<Positioned>) () -> new Reader(last.getKey(), false)) {
                validEnd = positioned.next();
            }
            activeStart = last.getKey();
            active = FileChannel.open(last.getValue(), StandardOpenOption.READ, StandardOpenOption.WRITE);
            active.truncate(validEnd - activeStart);
            active.force(true);
            end = validEnd;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open write-ahead log " + directory, e);
        }
    }

    private static long startOf(Path path) {
        String name = path.getFileName().toString();
        return Long.parseUnsignedLong(name.substring(0, name.indexOf('.')), 16);
    }

    private Path pathFor(long start) {
        return directory.resolve("%016x.wal".formatted(start));
    }

    public long append(List<WalRecord> records) {
        ByteCursor batch = ByteCursor.growable(4096);
        lock.lock();
        try {
            long first = end;
            long lsn = end;
            for (WalRecord record : records) {
                long frameStart = batch.position();
                batch.putInt(0).putInt(0).putLong(lsn).putByte(WalRecord.typeOf(record)).putLong(record.txnId());
                WalRecord.writePayload(batch, record);
                int payload = Math.toIntExact(batch.position() - frameStart - HEADER);
                int crc = Checksums.crc32c(batch.segment(), frameStart + 8, HEADER - 8 + payload);
                long resume = batch.position();
                batch.position(frameStart).putInt(payload).putInt(crc).position(resume);
                lsn += HEADER + payload;
            }
            write(batch.written());
            end = lsn;
            bytesAppended.addAndGet(lsn - first);
            return first;
        } finally {
            lock.unlock();
        }
    }

    public long append(WalRecord record) {
        return append(List.of(record));
    }

    private void write(MemorySegment bytes) {
        try {
            if (active == null || (end - activeStart > 0 && end - activeStart + bytes.byteSize() > segmentBytes)) {
                roll();
            }
            ByteBuffer buffer = bytes.asByteBuffer();
            long position = end - activeStart;
            while (buffer.hasRemaining()) {
                position += active.write(buffer, position);
            }
            dirty = true;
        } catch (IOException e) {
            throw HStoreException.io("write-ahead log append failed", e);
        }
    }

    private void roll() throws IOException {
        if (active != null) {
            active.force(true);
            active.close();
        }
        activeStart = end;
        Path path = pathFor(activeStart);
        active = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        segments.put(activeStart, path);
        syncDirectory();
    }

    private void syncDirectory() {
        try (FileChannel listing = FileChannel.open(directory, StandardOpenOption.READ)) {
            listing.force(true);
        } catch (IOException unsupported) {
            LOG.log(System.Logger.Level.DEBUG, "directory sync is not supported for " + directory, unsupported);
        }
    }

    public void sync() {
        lock.lock();
        try {
            if (dirty && active != null) {
                active.force(false);
                dirty = false;
            }
        } catch (IOException e) {
            throw HStoreException.io("write-ahead log sync failed", e);
        } finally {
            lock.unlock();
        }
    }

    public long end() {
        lock.lock();
        try {
            return end;
        } finally {
            lock.unlock();
        }
    }

    public long bytesAppended() {
        return bytesAppended.get();
    }

    public Stream<Positioned> read(long from) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(new Reader(from, true),
                Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    public void truncateBefore(long lsn) {
        lock.lock();
        try {
            List<Long> obsolete = new ArrayList<>();
            for (Map.Entry<Long, Path> entry : segments.entrySet()) {
                Long next = segments.higherKey(entry.getKey());
                if (next != null && next <= lsn && entry.getKey() != activeStart) {
                    obsolete.add(entry.getKey());
                }
            }
            for (long start : obsolete) {
                Files.deleteIfExists(segments.remove(start));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    public int segmentCount() {
        lock.lock();
        try {
            return segments.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (active != null) {
                active.force(true);
                active.close();
                active = null;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    private final class Reader implements Iterator<Positioned> {
        private final Iterator<Map.Entry<Long, Path>> files;
        private FileChannel channel;
        private long segmentStart;
        private long lsn;
        private Positioned next;
        private boolean finished;
        private final ByteBuffer header = ByteBuffer.allocate(HEADER);
        private final boolean decode;
        private ByteBuffer body = ByteBuffer.allocate(4096);

        Reader(long from, boolean decode) {
            this.decode = decode;
            Long floor = segments.floorKey(from);
            this.files = segments.tailMap(floor == null ? Long.MIN_VALUE : floor, true).entrySet().iterator();
            this.lsn = from;
        }

        @Override
        public boolean hasNext() {
            while (next == null && !finished) {
                if (channel == null && !openNext()) {
                    finished = true;
                    break;
                }
                next = readFrame();
                if (next == null) {
                    closeChannel();
                }
            }
            return next != null;
        }

        @Override
        public Positioned next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Positioned result = next;
            next = null;
            return result;
        }

        private boolean openNext() {
            while (files.hasNext()) {
                Map.Entry<Long, Path> entry = files.next();
                Long following = segments.higherKey(entry.getKey());
                if (following != null && following <= lsn) {
                    continue;
                }
                try {
                    channel = FileChannel.open(entry.getValue(), StandardOpenOption.READ);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                segmentStart = entry.getKey();
                lsn = Math.max(lsn, segmentStart);
                return true;
            }
            return false;
        }

        private Positioned readFrame() {
            try {
                header.clear();
                if (!readFully(header, lsn - segmentStart)) {
                    return header.position() == 0 ? null : damaged("frame header is truncated");
                }
                ByteCursor in = ByteCursor.over(MemorySegment.ofBuffer(header.flip()));
                int payload = in.getInt();
                int crc = in.getInt();
                long frameLsn = in.getLong();
                int type = in.getUnsignedByte();
                long txnId = in.getLong();
                if (payload < 0 || payload > MAX_RECORD || frameLsn != lsn) {
                    return damaged("frame header is invalid");
                }
                int size = HEADER - 8 + payload;
                if (body.capacity() < size) {
                    body = ByteBuffer.allocate(Math.max(size, body.capacity() * 2));
                }
                body.clear().limit(size);
                body.put(header.position(8));
                if (!readFully(body, lsn - segmentStart + HEADER)) {
                    return damaged("frame is truncated");
                }
                MemorySegment frame = MemorySegment.ofBuffer(body.flip());
                if (Checksums.crc32c(frame) != crc) {
                    return damaged("frame checksum does not match");
                }
                WalRecord record = decode ? WalRecord.readPayload(type, txnId, ByteCursor.over(frame).position(HEADER - 8)) : null;
                long start = lsn;
                lsn += HEADER + payload;
                return new Positioned(start, lsn, record);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private Positioned damaged(String reason) {
            if (segments.higherKey(segmentStart) != null) {
                throw HStoreException.corruptLog(lsn, "write-ahead log segment " + pathFor(segmentStart).getFileName() + " is damaged ("
                        + reason + ") but later segments exist; replay would silently drop committed transactions");
            }
            finished = true;
            return null;
        }

        private boolean readFully(ByteBuffer buffer, long position) throws IOException {
            long cursor = position;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, cursor);
                if (read < 0) {
                    return false;
                }
                cursor += read;
            }
            return true;
        }

        private void closeChannel() {
            try {
                channel.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            channel = null;
        }
    }
}
