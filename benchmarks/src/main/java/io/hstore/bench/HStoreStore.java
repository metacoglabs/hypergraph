// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.bench;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.MemberSpec;
import io.hstore.db.schema.AtomKind;
import io.hstore.db.schema.TypeDef.PropertyDef;
import io.hstore.db.value.TypeTag;
import io.hstore.db.value.Value;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.EngineStats;
import io.hstore.engine.tree.TreeAlgebra;
import io.hstore.engine.txn.Durability;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

final class HStoreStore implements Store {

    private static final String NODE = "Entity";
    private static final String EDGE = "Relation";

    private final Path directory;
    private final DatabaseOptions options;
    private HypergraphDatabase database;
    private long[] nodes;
    private long[] edges;
    private long large;
    private long writtenBeforeReopen;
    private long readBeforeReopen;

    static final long DEFAULT_CACHE_BYTES = Runtime.getRuntime().maxMemory() / 2;

    private final int history;

    private final long cacheBytes;

    HStoreStore(Path directory, boolean sync, int history, long cacheBytes, double liveRatio, int pagesPerSegment, boolean create) {
        this.directory = directory;
        this.history = history;
        this.cacheBytes = cacheBytes > 0 ? cacheBytes : DEFAULT_CACHE_BYTES;
        EngineOptions engine = EngineOptions.defaults();
        this.options = DatabaseOptions.defaults().withEngine(engine
                .withDurability(sync ? Durability.SYNC : Durability.ASYNC).withNodeCacheBytes(this.cacheBytes).withHistoryLimit(history)
                .withCompactionLiveRatio(liveRatio).withPagesPerSegment(pagesPerSegment > 0 ? pagesPerSegment : engine.pagesPerSegment()));
        this.database = HypergraphDatabase.open(directory, options);
        if (create) {
            database.write(writer -> {
                writer.defineNode(NODE, List.of(new PropertyDef("name", TypeTag.STRING, false, false)));
                writer.defineEdge(EDGE, AtomKind.SET_EDGE, List.of(), List.of());
                return null;
            });
        }
    }

    @Override
    public String name() {
        return "HStore";
    }

    @Override
    public String version() {
        return "0.2.0";
    }

    @Override
    public String cache() {
        return "node cache of %,d MiB and retains %d generations of history".formatted(cacheBytes >> 20, history);
    }

    @Override
    public Path directory() {
        return directory;
    }

    @Override
    public void ingestNodes(Dataset dataset, int batch, IntConsumer committed) {
        nodes = new long[dataset.nodes()];
        for (int start = 0; start < nodes.length; start += batch) {
            int from = start;
            int to = Math.min(nodes.length, start + batch);
            database.write(writer -> {
                for (int i = from; i < to; i++) {
                    nodes[i] = writer.node(NODE, "n" + i, Map.of("name", "node-" + i));
                }
                return null;
            });
            committed.accept(to);
        }
    }

    @Override
    public long countNodes() {
        return database.read(reader -> reader.atoms(NODE).count());
    }

    @Override
    public void ingestEdges(Dataset dataset, int batch) {
        int[][] source = dataset.edges();
        edges = new long[source.length];
        for (int start = 0; start < source.length; start += batch) {
            int from = start;
            int to = Math.min(source.length, start + batch);
            database.write(writer -> {
                for (int e = from; e < to; e++) {
                    long edge = writer.edge(EDGE);
                    writer.load(edge, members(source[e]), MemberSpec.PLAIN);
                    edges[e] = edge;
                }
                return null;
            });
        }
    }

    private long[] members(int[] indexes) {
        long[] members = new long[indexes.length];
        for (int i = 0; i < indexes.length; i++) {
            members[i] = nodes[indexes[i]];
        }
        return members;
    }

    @Override
    public long incidence(int[] probes, int from, int to) {
        return database.read(reader -> {
            IdCount count = new IdCount();
            for (int i = from; i < to; i++) {
                reader.forEachIncidentEdge(nodes[probes[i]], count);
            }
            return count.total;
        });
    }

    @Override
    public long members(int[] probes, int from, int to) {
        return database.read(reader -> {
            IdCount count = new IdCount();
            for (int i = from; i < to; i++) {
                reader.forEachMemberId(edges[probes[i]], count);
            }
            return count.total;
        });
    }

    @Override
    public long coMembership(int[][] pairs, int from, int to) {
        return database.read(reader -> {
            long total = 0;
            for (int i = from; i < to; i++) {
                total += TreeAlgebra.intersectKeys(reader.view().incidentTree(nodes[pairs[i][0]]),
                        reader.view().incidentTree(nodes[pairs[i][1]])).count();
            }
            return total;
        });
    }

    @Override
    public long twoHop(int[] probes, int from, int to) {
        return database.read(reader -> {
            Reached reached = new Reached();
            long total = 0;
            for (int i = from; i < to; i++) {
                long start = nodes[probes[i]];
                reached.size = 0;
                reader.forEachIncidentEdge(start, edge -> reader.forEachMemberId(edge, reached));
                total += reached.distinctExcept(start);
            }
            return total;
        });
    }

    @Override
    public void update(int[] probes, int from, int to, int round) {
        database.write(writer -> {
            for (int i = from; i < to; i++) {
                writer.set(nodes[probes[i]], "name", new Value.Text("updated-" + round + "-" + i));
            }
            return null;
        });
    }

    @Override
    public void largeEdge(int count) {
        large = database.write(writer -> {
            long edge = writer.edge(EDGE);
            writer.load(edge, Arrays.copyOf(nodes, count), MemberSpec.PLAIN);
            return edge;
        });
    }

    @Override
    public long scanLargeEdge() {
        return database.read(reader -> {
            IdCount count = new IdCount();
            reader.forEachMemberId(large, count);
            return count.total;
        });
    }

    @Override
    public long probeLargeEdge(int[] probes, int from, int to) {
        return database.read(reader -> {
            long found = 0;
            for (int i = from; i < to; i++) {
                found += reader.view().incidence(large, nodes[probes[i]]).isPresent() ? 1 : 0;
            }
            return found;
        });
    }

    @Override
    public void flush() {
        database.engine().checkpoint();
    }

    @Override
    public void compact() {
        database.engine().compact();
    }

    @Override
    public long bytesWritten() {
        EngineStats stats = database.engine().stats();
        return writtenBeforeReopen + stats.walBytes() + stats.dataBytesWritten();
    }

    @Override
    public void deleteEdges(int[] deletions, int from, int to) {
        database.write(writer -> {
            for (int i = from; i < to; i++) {
                writer.delete(edges[deletions[i]]);
            }
            return null;
        });
    }

    @Override
    public void removeMembers(int[][] removals, int from, int to) {
        database.write(writer -> {
            for (int i = from; i < to; i++) {
                writer.remove(edges[removals[i][0]], nodes[removals[i][1]]);
            }
            return null;
        });
    }

    @Override
    public long bytesRead() {
        return readBeforeReopen + database.engine().stats().dataBytesRead();
    }

    @Override
    public void reopen(Runnable whileClosed) {
        writtenBeforeReopen = bytesWritten();
        readBeforeReopen = bytesRead();
        database.close();
        whileClosed.run();
        database = HypergraphDatabase.open(directory, options);
    }

    @Override
    public void close() {
        database.close();
    }

    private static final class Reached implements LongConsumer {
        long[] atoms = new long[64];
        int size;

        @Override
        public void accept(long atom) {
            if (size == atoms.length) {
                atoms = Arrays.copyOf(atoms, size * 2);
            }
            atoms[size++] = atom;
        }

        long distinctExcept(long start) {
            Arrays.sort(atoms, 0, size);
            long distinct = 0;
            for (int i = 0; i < size; i++) {
                if (atoms[i] != start && (i == 0 || atoms[i] != atoms[i - 1])) {
                    distinct++;
                }
            }
            return distinct;
        }
    }

    private static final class IdCount implements LongConsumer {
        long total;

        @Override
        public void accept(long atom) {
            if (atom != 0) {
                total++;
            }
        }
    }
}
