package io.hstore.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

interface Store extends AutoCloseable {

    String name();

    String version();

    String cache();

    void ingestNodes(Dataset dataset, int batch);

    void ingestEdges(Dataset dataset, int batch);

    long incidence(int[] nodes, int from, int to);

    long members(int[] edges, int from, int to);

    long coMembership(int[][] pairs, int from, int to);

    void update(int[] nodes, int from, int to, int round);

    void largeEdge(int members);

    long scanLargeEdge();

    long probeLargeEdge(int[] nodes, int from, int to);

    void deleteEdges(int[] deletions, int from, int to);

    void removeMembers(int[][] removals, int from, int to);

    default void reopen() {
        reopen(() -> {
        });
    }

    void reopen(Runnable whileClosed);

    void flush();

    long bytesWritten();

    Path directory();

    @Override
    void close();

    default long diskBytes() {
        try (Stream<Path> files = Files.walk(directory())) {
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).sum();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
