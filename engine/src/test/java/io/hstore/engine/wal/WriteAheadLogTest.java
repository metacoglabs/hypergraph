package io.hstore.engine.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteAheadLogTest {

    private static final long SEGMENT_BYTES = 64 * 1024;

    @TempDir
    Path directory;

    private static List<WalRecord> records(int count, long seed) {
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
        List<WalRecord> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] bytes = new byte[random.nextInt(random.nextInt(10) == 0 ? 20_000 : 200)];
            random.nextBytes(bytes);
            records.add(switch (i % 4) {
                case 0 -> new WalRecord.Begin(i, random.nextInt(4), random.nextLong(1_000_000));
                case 1 -> new WalRecord.Page(i, random.nextLong(), bytes);
                case 2 -> new WalRecord.Feed(i, bytes);
                default -> new WalRecord.Commit(i, i, random.nextLong(), random.nextLong(1 << 30), random.nextLong(1 << 30));
            });
        }
        return records;
    }

    private static String describe(WalRecord record) {
        return switch (record) {
            case WalRecord.Page(long txn, long page, byte[] image) -> "page " + txn + " " + page + " " + HexFormat.of().formatHex(image);
            case WalRecord.Feed(long txn, byte[] payload) -> "feed " + txn + " " + HexFormat.of().formatHex(payload);
            default -> record.toString();
        };
    }

    private static List<String> replay(WriteAheadLog wal) {
        try (Stream<WriteAheadLog.Positioned> stream = wal.read(0)) {
            return stream.map(positioned -> describe(positioned.record())).toList();
        }
    }

    @Test
    void reopenedLogReplaysRecordsOfMixedSizesAcrossSegments() {
        List<WalRecord> written = records(3_000, 7);
        long end;
        try (WriteAheadLog wal = WriteAheadLog.open(directory, SEGMENT_BYTES)) {
            written.forEach(wal::append);
            end = wal.end();
            assertTrue(wal.segmentCount() > 3, "segments " + wal.segmentCount());
        }
        try (WriteAheadLog wal = WriteAheadLog.open(directory, SEGMENT_BYTES)) {
            assertEquals(end, wal.end());
            assertEquals(written.stream().map(WriteAheadLogTest::describe).toList(), replay(wal));
        }
    }

    @Test
    void aTornFinalFrameIsDroppedAndEverythingBeforeItKept() throws IOException {
        List<WalRecord> written = records(400, 11);
        long lastStart;
        try (WriteAheadLog wal = WriteAheadLog.open(directory, SEGMENT_BYTES)) {
            written.subList(0, written.size() - 1).forEach(wal::append);
            lastStart = wal.end();
            wal.append(written.getLast());
        }
        Path last;
        try (Stream<Path> files = Files.list(directory)) {
            last = files.filter(path -> path.toString().endsWith(".wal")).max(Path::compareTo).orElseThrow();
        }
        try (FileChannel channel = FileChannel.open(last, StandardOpenOption.WRITE)) {
            channel.truncate(channel.size() - 3);
        }
        try (WriteAheadLog wal = WriteAheadLog.open(directory, SEGMENT_BYTES)) {
            assertEquals(lastStart, wal.end());
            assertEquals(written.subList(0, written.size() - 1).stream().map(WriteAheadLogTest::describe).toList(), replay(wal));
        }
    }

    @Test
    void aSmallFrameAfterALargeOneReadsBackIntact() {
        List<WalRecord> written = List.of(new WalRecord.Feed(1, new byte[]{1, 2, 3}), new WalRecord.Page(2, 9, new byte[30_000]),
                new WalRecord.Feed(3, new byte[]{4, 5}), new WalRecord.Commit(4, 4, 5, 6, 7), new WalRecord.Feed(5, new byte[0]));
        try (WriteAheadLog wal = WriteAheadLog.open(directory, 1 << 20)) {
            written.forEach(wal::append);
        }
        try (WriteAheadLog wal = WriteAheadLog.open(directory, 1 << 20)) {
            assertEquals(written.stream().map(WriteAheadLogTest::describe).toList(), replay(wal));
        }
    }
}
