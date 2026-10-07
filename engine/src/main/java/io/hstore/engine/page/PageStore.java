package io.hstore.engine.page;

import io.hstore.engine.HStoreException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

public final class PageStore implements AutoCloseable {

    private final Path directory;
    private final int pageSize;
    private final int segmentUnits;
    private final Map<Integer, SegmentFile> files = new ConcurrentHashMap<>();
    private final ConcurrentSkipListMap<Integer, SegmentInfo> segments = new ConcurrentSkipListMap<>();
    private final ReentrantLock allocation = new ReentrantLock();
    private final AtomicLong pagesWritten = new AtomicLong();
    private final AtomicLong bytesWritten = new AtomicLong();
    private final AtomicLong pagesRead = new AtomicLong();
    private int activeSegment;
    private int nextUnit;

    private PageStore(Path directory, int pageSize, int pagesPerSegment) {
        this.directory = directory;
        this.pageSize = pageSize;
        this.segmentUnits = (int) Math.min(PageId.MAX_OFFSET + 1L, (long) pagesPerSegment * pageSize / PageId.UNIT_BYTES);
    }

    public static PageStore open(Path directory, int pageSize, int pagesPerSegment, Collection<SegmentInfo> known) {
        PageStore store = new PageStore(directory, pageSize, pagesPerSegment);
        store.restore(known);
        return store;
    }

    private void restore(Collection<SegmentInfo> known) {
        try {
            Files.createDirectories(directory);
            Map<Integer, SegmentInfo> byId = new ConcurrentHashMap<>();
            known.forEach(info -> byId.put(info.id(), info));
            try (Stream<Path> listing = Files.list(directory)) {
                for (Path file : listing.filter(p -> p.getFileName().toString().endsWith(".seg")).toList()) {
                    int id = SegmentFile.idOf(file);
                    SegmentFile segment = SegmentFile.open(id, file);
                    files.put(id, segment);
                    long units = segment.unitsOnDisk();
                    SegmentInfo info = byId.getOrDefault(id, new SegmentInfo(id, SegmentState.SEALED, units, units, 0));
                    segments.put(id, info.withAllocation(info.pages(), Math.max(info.units(), units)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open page store " + directory, e);
        }
        segments.descendingMap().values().stream()
                .filter(info -> info.state() == SegmentState.ACTIVE)
                .findFirst()
                .ifPresent(info -> {
                    activeSegment = info.id();
                    nextUnit = Math.toIntExact(info.units());
                });
    }

    public int pageSize() {
        return pageSize;
    }

    public long allocate(long epoch, int length) {
        if (length > pageSize) {
            throw new IllegalArgumentException("page image exceeds page size: " + length);
        }
        int units = PageId.unitsFor(length);
        allocation.lock();
        try {
            if (activeSegment == 0 || nextUnit + units > segmentUnits) {
                rollSegment();
            }
            int offset = nextUnit;
            nextUnit += units;
            segments.computeIfPresent(activeSegment, (_, info) -> info.withAllocation(info.pages() + 1, Math.max(info.units(), offset + (long) units)));
            return PageId.pack(activeSegment, offset, epoch);
        } finally {
            allocation.unlock();
        }
    }

    private void rollSegment() {
        if (activeSegment != 0) {
            segments.computeIfPresent(activeSegment, (_, info) -> info.withState(SegmentState.SEALED, 0));
        }
        int id = segments.isEmpty() ? 1 : segments.lastKey() + 1;
        files.put(id, SegmentFile.open(id, SegmentFile.pathFor(directory, id)));
        segments.put(id, new SegmentInfo(id, SegmentState.ACTIVE, 0, 0, 0));
        activeSegment = id;
        nextUnit = 0;
    }

    public void write(long pageId, MemorySegment image) {
        if (image.byteSize() > pageSize) {
            throw new IllegalArgumentException("page image exceeds page size: " + image.byteSize());
        }
        int segment = PageId.segmentOf(pageId);
        int offset = PageId.offsetOf(pageId);
        SegmentFile file = files.computeIfAbsent(segment, id -> {
            segments.putIfAbsent(id, new SegmentInfo(id, SegmentState.SEALED, 0, 0, 0));
            return SegmentFile.open(id, SegmentFile.pathFor(directory, id));
        });
        file.write((long) offset * PageId.UNIT_BYTES, image);
        long end = offset + (long) PageId.unitsFor(image.byteSize());
        segments.computeIfPresent(segment, (_, info) -> info.units() >= end ? info : info.withAllocation(info.pages() + 1, end));
        pagesWritten.incrementAndGet();
        bytesWritten.addAndGet(image.byteSize());
    }

    public MemorySegment read(long pageId) {
        SegmentFile file = files.get(PageId.segmentOf(pageId));
        if (file == null) {
            throw HStoreException.corrupt(pageId, "segment does not exist");
        }
        pagesRead.incrementAndGet();
        return file.read((long) PageId.offsetOf(pageId) * PageId.UNIT_BYTES, pageSize);
    }

    public MemorySegment readHeader(long pageId) {
        SegmentFile file = files.get(PageId.segmentOf(pageId));
        if (file == null) {
            throw HStoreException.corrupt(pageId, "segment does not exist");
        }
        return file.read((long) PageId.offsetOf(pageId) * PageId.UNIT_BYTES, PageHeader.SIZE);
    }

    public void sync() {
        files.values().forEach(SegmentFile::force);
    }

    public List<SegmentInfo> segments() {
        return List.copyOf(segments.values());
    }

    public int activeSegment() {
        allocation.lock();
        try {
            return activeSegment;
        } finally {
            allocation.unlock();
        }
    }

    public void transition(int segment, SegmentState next, long at) {
        segments.computeIfPresent(segment, (_, info) -> info.withState(next, at));
    }

    public void delete(int segment) {
        SegmentFile file = files.remove(segment);
        if (file != null) {
            file.delete();
        }
        segments.remove(segment);
    }

    public long pagesWritten() {
        return pagesWritten.get();
    }

    public long bytesWritten() {
        return bytesWritten.get();
    }

    public long pagesRead() {
        return pagesRead.get();
    }

    @Override
    public void close() {
        files.values().forEach(SegmentFile::close);
        files.clear();
    }
}
