package io.hstore.engine.page;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageStoreTest {

    private static final int PAGE_SIZE = 4096;
    private static final int PAGES_PER_SEGMENT = 2048;
    private static final int PAYLOAD_LENGTH_OFFSET = 24;

    @TempDir
    Path directory;

    @Test
    void pagesReadBackExactlyAsWrittenAcrossSegmentsAndReopens() {
        Random random = new Random(7);
        Map<Long, byte[]> written = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>();
        List<SegmentInfo> segments;
        try (PageStore store = PageStore.open(directory, PAGE_SIZE, PAGES_PER_SEGMENT, List.of())) {
            for (int i = 0; i < 16_000; i++) {
                byte[] image = image(random, PageHeader.SIZE + random.nextInt(PAGE_SIZE - PageHeader.SIZE + 1));
                long pageId = store.allocate(1, image.length);
                store.write(pageId, MemorySegment.ofArray(image));
                written.put(pageId, image);
                ids.add(pageId);
                for (int probe = 0; probe < 3; probe++) {
                    long earlier = ids.get(random.nextInt(ids.size()));
                    assertArrayEquals(written.get(earlier), bytes(store.read(earlier)), "page " + PageId.unpack(earlier));
                }
            }
            assertTrue(store.segments().size() > 3, "the test should span several segments");
            written.forEach((pageId, image) -> assertArrayEquals(image, bytes(store.read(pageId))));
            segments = store.segments();
        }
        try (PageStore reopened = PageStore.open(directory, PAGE_SIZE, PAGES_PER_SEGMENT, segments)) {
            written.forEach((pageId, image) -> assertArrayEquals(image, bytes(reopened.read(pageId))));
        }
    }

    @Test
    void pagesCannotBeChangedThroughWhatReadReturns() {
        try (PageStore store = PageStore.open(directory, PAGE_SIZE, PAGES_PER_SEGMENT, List.of())) {
            long first = write(store, PAGE_SIZE);
            fillPastOneSegment(store);
            long last = write(store, 200);
            for (long pageId : new long[]{first, last}) {
                MemorySegment page = store.read(pageId);
                assertThrows(IllegalArgumentException.class, () -> page.set(ValueLayout.JAVA_BYTE, 0, (byte) 1));
            }
        }
    }

    private static long write(PageStore store, int length) {
        byte[] image = image(new Random(length), length);
        long pageId = store.allocate(1, image.length);
        store.write(pageId, MemorySegment.ofArray(image));
        return pageId;
    }

    private static void fillPastOneSegment(PageStore store) {
        int segment = store.activeSegment();
        while (store.activeSegment() == segment) {
            write(store, PAGE_SIZE);
        }
    }

    private static byte[] image(Random random, int length) {
        byte[] image = new byte[length];
        random.nextBytes(image);
        ByteCursor.wrap(image).position(PAYLOAD_LENGTH_OFFSET).putInt(length - PageHeader.SIZE);
        return image;
    }

    private static byte[] bytes(MemorySegment page) {
        return page.toArray(ValueLayout.JAVA_BYTE);
    }
}
