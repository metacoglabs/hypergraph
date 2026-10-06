// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.feed;

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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class ChangeFeed implements AutoCloseable {

    public interface Subscription extends AutoCloseable {
        long acknowledged();

        @Override
        void close();
    }

    public interface Hold extends AutoCloseable {
        @Override
        void close();
    }

    @FunctionalInterface
    public interface Gap {
        void missed(long acknowledged, long firstRetained);
    }

    private static final int FRAME_HEADER = 16;
    private static final int INDEX_STRIDE = 64;
    private static final long SEGMENT_BYTES = 64L << 20;
    private static final long EMPTY = Long.MAX_VALUE;
    private static final System.Logger LOG = System.getLogger("hstore.feed");

    private static final class Segment {
        final long start;
        final Path path;
        final FileChannel channel;
        volatile long firstGeneration = EMPTY;

        Segment(long start, Path path, FileChannel channel) {
            this.start = start;
            this.path = path;
            this.channel = channel;
        }
    }

    private final Path directory;
    private final FeedCodec codec;
    private final long segmentBytes;
    private final NavigableMap<Long, Segment> segments = new ConcurrentSkipListMap<>();
    private final NavigableMap<Long, Long> index = new ConcurrentSkipListMap<>();
    private final Set<LongSupplier> holds = ConcurrentHashMap.newKeySet();
    private final ReentrantLock lock = new ReentrantLock();
    private final ReentrantReadWriteLock files = new ReentrantReadWriteLock();
    private final Condition appended = lock.newCondition();
    private volatile long end;
    private volatile long lastGeneration;
    private volatile boolean closed;
    private long sinceIndexed;

    private ChangeFeed(Path directory, FeedCodec codec, long segmentBytes) {
        this.directory = directory;
        this.codec = codec;
        this.segmentBytes = segmentBytes;
    }

    public static ChangeFeed open(Path directory, FeedCodec codec) {
        return open(directory, codec, SEGMENT_BYTES);
    }

    public static ChangeFeed open(Path directory, FeedCodec codec, long segmentBytes) {
        ChangeFeed feed = new ChangeFeed(directory, codec, segmentBytes);
        try {
            Files.createDirectories(directory);
            feed.restore();
            return feed;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open change feed in " + directory, e);
        }
    }

    private void restore() throws IOException {
        try (Stream<Path> listing = Files.list(directory)) {
            for (Path path : listing.filter(file -> file.getFileName().toString().endsWith(".feed")).toList()) {
                long start = Long.parseUnsignedLong(path.getFileName().toString().replace(".feed", ""), 16);
                segments.put(start, new Segment(start, path, FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)));
            }
        }
        if (segments.isEmpty()) {
            return;
        }
        Segment last = segments.lastEntry().getValue();
        long position = segments.firstKey();
        for (Iterator<Frame> frames = new FrameReader(position, last.start + last.channel.size()); frames.hasNext(); ) {
            Frame frame = frames.next();
            remember(frame.generation(), position);
            position = frame.next();
        }
        cutAt(position);
    }

    private void remember(long generation, long position) {
        Segment segment = segments.floorEntry(position).getValue();
        boolean first = segment.firstGeneration == EMPTY;
        if (first) {
            segment.firstGeneration = generation;
        }
        if (first || ++sinceIndexed >= INDEX_STRIDE) {
            index.put(generation, position);
            sinceIndexed = 0;
        }
        lastGeneration = generation;
    }

    private void cutAt(long position) throws IOException {
        Segment holding = segments.floorEntry(position).getValue();
        for (Segment later : List.copyOf(segments.tailMap(holding.start, false).values())) {
            segments.remove(later.start);
            discard(later);
        }
        holding.channel.truncate(position - holding.start);
        if (position == holding.start) {
            holding.firstGeneration = EMPTY;
        }
        end = position;
    }

    public long lastGeneration() {
        return lastGeneration;
    }

    public long firstGeneration() {
        return segments.values().stream().mapToLong(segment -> segment.firstGeneration).min().orElse(EMPTY);
    }

    public boolean covers(long afterGeneration) {
        long first = firstGeneration();
        return first == EMPTY || afterGeneration + 1 >= first;
    }

    public long size() {
        return segments.isEmpty() ? 0 : end - segments.firstKey();
    }

    public int segmentCount() {
        return segments.size();
    }

    public void append(CommitEvent event) {
        if (event.generation() <= lastGeneration) {
            return;
        }
        byte[] payload = codec.encode(event);
        ByteCursor frame = ByteCursor.fixed(FRAME_HEADER + payload.length);
        frame.putInt(payload.length).putInt(Checksums.crc32c(payload)).putLong(event.generation()).putBytes(payload);
        lock.lock();
        try {
            Segment active = segments.isEmpty() || end - segments.lastKey() >= segmentBytes ? roll() : segments.lastEntry().getValue();
            ByteBuffer buffer = frame.segment().asByteBuffer();
            long position = end;
            while (buffer.hasRemaining()) {
                position += active.channel.write(buffer, position - active.start);
            }
            remember(event.generation(), end);
            end = position;
            appended.signalAll();
        } catch (IOException e) {
            throw HStoreException.io("change feed append failed", e);
        } finally {
            lock.unlock();
        }
    }

    private Segment roll() throws IOException {
        if (!segments.isEmpty()) {
            segments.lastEntry().getValue().channel.force(false);
        }
        Path path = directory.resolve("%016x.feed".formatted(end));
        Segment segment = new Segment(end, path, FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE));
        segments.put(end, segment);
        try (FileChannel listing = FileChannel.open(directory, StandardOpenOption.READ)) {
            listing.force(true);
        } catch (IOException unsupported) {
            LOG.log(System.Logger.Level.DEBUG, "directory sync is not supported for " + directory, unsupported);
        }
        return segment;
    }

    public void sync() {
        if (segments.isEmpty()) {
            return;
        }
        try {
            segments.lastEntry().getValue().channel.force(false);
        } catch (IOException e) {
            throw HStoreException.io("change feed sync failed", e);
        }
    }

    public Hold hold(LongSupplier generation) {
        holds.add(generation);
        return () -> holds.remove(generation);
    }

    public long retain(long keepAfter) {
        long floor = keepAfter;
        for (LongSupplier hold : holds) {
            floor = Math.min(floor, hold.getAsLong());
        }
        long released = 0;
        lock.lock();
        try {
            for (Segment segment : List.copyOf(segments.values())) {
                Map.Entry<Long, Segment> following = segments.higherEntry(segment.start);
                if (following == null || following.getValue().firstGeneration > floor + 1) {
                    break;
                }
                released += following.getKey() - segment.start;
                segments.remove(segment.start);
                discard(segment);
            }
            if (released > 0) {
                long first = segments.firstKey();
                index.values().removeIf(position -> position < first);
                LOG.log(System.Logger.Level.INFO, "change feed released {0} bytes; generations after {1} are retained", released,
                        firstGeneration() - 1);
            }
        } finally {
            lock.unlock();
        }
        return released;
    }

    private void discard(Segment segment) {
        files.writeLock().lock();
        try {
            segment.channel.close();
            Files.deleteIfExists(segment.path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            files.writeLock().unlock();
        }
    }

    public void truncateAfter(long generation) {
        lock.lock();
        try {
            for (Iterator<Frame> frames = new FrameReader(startFor(generation), end); frames.hasNext(); ) {
                Frame frame = frames.next();
                if (frame.generation() > generation) {
                    cutAt(frame.start());
                    index.tailMap(generation, false).clear();
                    lastGeneration = Math.min(lastGeneration, generation);
                    return;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    public Stream<CommitEvent> replay(long afterGeneration) {
        Iterator<Frame> frames = new FrameReader(startFor(afterGeneration), end);
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(frames, Spliterator.ORDERED | Spliterator.NONNULL), false)
                .filter(frame -> frame.generation() > afterGeneration)
                .map(frame -> codec.decode(frame.payload()));
    }

    public Subscription subscribe(long afterGeneration, Consumer<CommitEvent> consumer) {
        return subscribe(afterGeneration, consumer, (acknowledged, first) -> LOG.log(System.Logger.Level.WARNING,
                "change feed subscriber at generation {0} fell behind retention; resuming at generation {1}", acknowledged, first));
    }

    public Subscription subscribe(long afterGeneration, Consumer<CommitEvent> consumer, Gap gap) {
        Tail tail = new Tail(afterGeneration, consumer, gap);
        Thread.ofVirtual().name("feed-subscriber").start(tail);
        return tail;
    }

    private long startFor(long generation) {
        long first = segments.isEmpty() ? end : segments.firstKey();
        Map.Entry<Long, Long> floor = index.floorEntry(generation);
        return floor == null ? first : Math.max(first, floor.getValue());
    }

    @Override
    public void close() {
        closed = true;
        lock.lock();
        try {
            appended.signalAll();
            for (Segment segment : segments.values()) {
                segment.channel.force(true);
                segment.channel.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    private record Frame(long start, long next, long generation, byte[] payload) {
    }

    private final class FrameReader implements Iterator<Frame> {
        private final long limit;
        private long position;
        private Frame next;

        FrameReader(long from, long limit) {
            this.position = from;
            this.limit = limit;
        }

        @Override
        public boolean hasNext() {
            if (next == null && position + FRAME_HEADER <= limit) {
                next = read();
            }
            return next != null;
        }

        @Override
        public Frame next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Frame frame = next;
            next = null;
            position = frame.next();
            return frame;
        }

        private Frame read() {
            files.readLock().lock();
            try {
                return readLocked();
            } finally {
                files.readLock().unlock();
            }
        }

        private Frame readLocked() {
            Map.Entry<Long, Segment> entry = segments.floorEntry(position);
            if (entry == null || !entry.getValue().channel.isOpen()) {
                return null;
            }
            FileChannel channel = entry.getValue().channel;
            long local = position - entry.getKey();
            try {
                ByteBuffer header = ByteBuffer.allocate(FRAME_HEADER);
                if (!readFully(channel, header, local)) {
                    return null;
                }
                ByteCursor in = ByteCursor.over(MemorySegment.ofBuffer(header.flip()));
                int length = in.getInt();
                int crc = in.getInt();
                long generation = in.getLong();
                if (length < 0 || position + FRAME_HEADER + length > limit) {
                    return null;
                }
                ByteBuffer body = ByteBuffer.allocate(length);
                if (!readFully(channel, body, local + FRAME_HEADER)) {
                    return null;
                }
                byte[] payload = body.array();
                return Checksums.crc32c(payload) == crc
                        ? new Frame(position, position + FRAME_HEADER + length, generation, payload)
                        : null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private boolean readFully(FileChannel channel, ByteBuffer buffer, long at) throws IOException {
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, at + buffer.position()) < 0) {
                    return false;
                }
            }
            return true;
        }
    }

    private final class Tail implements Runnable, Subscription {
        private final Consumer<CommitEvent> consumer;
        private final Gap gap;
        private volatile long acknowledged;
        private volatile boolean stopped;

        Tail(long afterGeneration, Consumer<CommitEvent> consumer, Gap gap) {
            this.acknowledged = afterGeneration;
            this.consumer = consumer;
            this.gap = gap;
        }

        @Override
        public void run() {
            try {
                while (!stopped && !closed) {
                    Stream<CommitEvent> events = replay(acknowledged);
                    if (!covers(acknowledged)) {
                        long first = firstGeneration();
                        gap.missed(acknowledged, first);
                        acknowledged = first - 1;
                        continue;
                    }
                    events.takeWhile(_ -> !stopped && !closed).forEach(event -> {
                        consumer.accept(event);
                        acknowledged = event.generation();
                    });
                    awaitAppend();
                }
            } catch (RuntimeException failure) {
                if (!closed) {
                    LOG.log(System.Logger.Level.WARNING, "change feed subscriber stopped at generation " + acknowledged, failure);
                }
            }
        }

        private void awaitAppend() {
            lock.lock();
            try {
                while (!stopped && !closed && lastGeneration <= acknowledged) {
                    appended.await(250, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = true;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public long acknowledged() {
            return acknowledged;
        }

        @Override
        public void close() {
            stopped = true;
        }
    }
}
