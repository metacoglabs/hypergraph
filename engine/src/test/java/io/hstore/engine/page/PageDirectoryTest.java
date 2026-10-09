package io.hstore.engine.page;

import io.hstore.engine.HStoreException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageDirectoryTest {

    @TempDir
    Path directory;

    @Test
    void addressesAndTheNextNumberSurviveReopenAcrossChunks() {
        Path file = directory.resolve(PageDirectory.FILE);
        int count = 1_500_000;
        try (PageDirectory pages = PageDirectory.open(file)) {
            for (long expected = 1; expected <= count; expected++) {
                long index = pages.allocate();
                assertEquals(expected, index);
                pages.put(index, address(index));
            }
            pages.force();
        }
        try (PageDirectory reopened = PageDirectory.open(file)) {
            assertEquals(count + 1, reopened.next());
            for (long index = 1; index <= count; index++) {
                assertEquals(address(index), reopened.get(index), "entry " + index);
            }
            assertEquals(PageAddress.NONE, reopened.get(count + 1));
            assertEquals(PageAddress.NONE, reopened.get(50_000_000));
        }
    }

    @Test
    void aReservedNumberIsNeverHandedOut() {
        try (PageDirectory pages = PageDirectory.open(directory.resolve(PageDirectory.FILE))) {
            pages.reserve(41);
            assertEquals(42, pages.allocate());
            pages.reserve(7);
            assertEquals(43, pages.allocate());
        }
    }

    @Test
    void aFileInAnotherFormatIsRefused() throws Exception {
        Path file = directory.resolve(PageDirectory.FILE);
        Files.write(file, new byte[64]);
        assertThrows(HStoreException.InvalidSchema.class, () -> PageDirectory.open(file));
    }

    private static long address(long index) {
        return new PageAddress(1 + (int) (index % 1000), (int) (index % PageAddress.MAX_OFFSET), 1 + (int) (index % 200)).pack();
    }
}
