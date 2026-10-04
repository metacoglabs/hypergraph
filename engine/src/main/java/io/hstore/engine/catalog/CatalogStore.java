package io.hstore.engine.catalog;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.page.Checksums;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class CatalogStore {

    private static final int MAGIC = 0x54414348;
    private static final int RETAINED_FILES = 3;

    private final Path manifest;
    private final Path generations;

    private CatalogStore(Path directory) {
        this.manifest = directory.resolve("manifest");
        this.generations = directory.resolve("generations");
    }

    public static CatalogStore open(Path directory) {
        CatalogStore store = new CatalogStore(directory);
        try {
            Files.createDirectories(store.generations);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return store;
    }

    public Optional<CatalogImage> loadLatest() {
        Optional<CatalogImage> fromManifest = readManifest().flatMap(this::read);
        if (fromManifest.isPresent()) {
            return fromManifest;
        }
        return files().map(this::read).flatMap(Optional::stream).findFirst();
    }

    public void publish(CatalogImage image) {
        ByteCursor body = ByteCursor.growable(4096);
        image.writeTo(body);
        MemorySegment payload = body.written();
        ByteCursor file = ByteCursor.growable(Math.toIntExact(payload.byteSize() + 16));
        file.putInt(MAGIC).putInt(Checksums.crc32c(payload)).putLong(payload.byteSize()).putSegment(payload);
        Path target = generations.resolve("%016x.cat".formatted(image.current().id()));
        writeDurably(target, file.toByteArray());
        writeDurably(manifest, target.getFileName().toString().getBytes(StandardCharsets.UTF_8));
        files().skip(RETAINED_FILES).forEach(this::deleteQuietly);
    }

    private Optional<Path> readManifest() {
        try {
            if (!Files.exists(manifest)) {
                return Optional.empty();
            }
            return Optional.of(generations.resolve(Files.readString(manifest).trim()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<CatalogImage> read(Path file) {
        try {
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            ByteCursor in = ByteCursor.wrap(Files.readAllBytes(file));
            if (in.remaining() < 16 || in.getInt() != MAGIC) {
                return Optional.empty();
            }
            int checksum = in.getInt();
            long length = in.getLong();
            if (length != in.remaining()) {
                return Optional.empty();
            }
            MemorySegment payload = in.segment().asSlice(in.position(), length);
            if (Checksums.crc32c(payload) != checksum) {
                return Optional.empty();
            }
            return Optional.of(CatalogImage.readFrom(ByteCursor.over(payload)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private Stream<Path> files() {
        try (Stream<Path> listing = Files.list(generations)) {
            List<Path> sorted = listing.filter(path -> path.getFileName().toString().endsWith(".cat"))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
            return sorted.stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeDurably(Path target, byte[] bytes) {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            syncDirectory(target.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot publish catalog file " + target, e);
        }
    }

    private static void syncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException _) {
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException _) {
        }
    }
}
