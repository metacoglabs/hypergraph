package io.hstore.bench;

import com.sleepycat.je.Durability;
import org.hypergraphdb.HGConfiguration;
import org.hypergraphdb.HGEnvironment;
import org.hypergraphdb.HGHandle;
import org.hypergraphdb.HGLink;
import org.hypergraphdb.HGPersistentHandle;
import org.hypergraphdb.HGPlainLink;
import org.hypergraphdb.HGQuery.hg;
import org.hypergraphdb.HGSearchResult;
import org.hypergraphdb.HyperGraph;
import org.hypergraphdb.IncidenceSet;
import org.hypergraphdb.storage.bje.BJEStorageImplementation;

import java.nio.file.Path;
import java.util.concurrent.Callable;

final class HyperGraphDbStore implements Store {

    private final Path directory;
    private final HGConfiguration configuration = new HGConfiguration();
    private HyperGraph graph;
    private HGPersistentHandle[] nodes;
    private HGPersistentHandle[] edges;
    private HGPersistentHandle large;

    HyperGraphDbStore(Path directory, boolean sync) {
        this.directory = directory;
        configuration.setTransactional(true);
        if (sync) {
            ((BJEStorageImplementation) configuration.getStoreImplementation()).getConfiguration()
                    .getEnvironmentConfig().setDurability(Durability.COMMIT_SYNC);
        }
        this.graph = HGEnvironment.get(directory.toString(), configuration);
    }

    @Override
    public String name() {
        return "HyperGraphDB";
    }

    @Override
    public String version() {
        return "1.4 @ 99485a1 (Berkeley DB JE 5.0.73)";
    }

    @Override
    public String cache() {
        return "Berkeley DB JE cache at 30% of the heap (HyperGraphDB default) plus the HyperGraphDB atom cache";
    }

    @Override
    public Path directory() {
        return directory;
    }

    private <T> T transact(Callable<T> work) {
        return graph.getTransactionManager().transact(work);
    }

    private <T> T read(Callable<T> work) {
        return graph.getTransactionManager().ensureTransaction(work);
    }

    @Override
    public void ingestNodes(Dataset dataset, int batch) {
        nodes = new HGPersistentHandle[dataset.nodes()];
        for (int start = 0; start < nodes.length; start += batch) {
            int from = start;
            int to = Math.min(nodes.length, start + batch);
            transact(() -> {
                for (int i = from; i < to; i++) {
                    nodes[i] = graph.getPersistentHandle(graph.add("node-" + i));
                }
                return null;
            });
        }
    }

    @Override
    public void ingestEdges(Dataset dataset, int batch) {
        int[][] source = dataset.edges();
        edges = new HGPersistentHandle[source.length];
        for (int start = 0; start < source.length; start += batch) {
            int from = start;
            int to = Math.min(source.length, start + batch);
            transact(() -> {
                for (int e = from; e < to; e++) {
                    HGHandle[] targets = new HGHandle[source[e].length];
                    for (int m = 0; m < targets.length; m++) {
                        targets[m] = nodes[source[e][m]];
                    }
                    edges[e] = graph.getPersistentHandle(graph.add(new HGPlainLink(targets)));
                }
                return null;
            });
        }
    }

    @Override
    public long incidence(int[] probes, int from, int to) {
        return read(() -> {
            long total = 0;
            for (int i = from; i < to; i++) {
                IncidenceSet set = graph.getIncidenceSet(nodes[probes[i]]);
                HGSearchResult<HGHandle> result = set.getSearchResult();
                try {
                    while (result.hasNext()) {
                        result.next();
                        total++;
                    }
                } finally {
                    result.close();
                }
            }
            return total;
        });
    }

    @Override
    public long members(int[] probes, int from, int to) {
        return read(() -> {
            long total = 0;
            for (int i = from; i < to; i++) {
                HGLink link = graph.get(edges[probes[i]]);
                for (int t = 0; t < link.getArity(); t++) {
                    if (link.getTargetAt(t) != null) {
                        total++;
                    }
                }
            }
            return total;
        });
    }

    @Override
    public long coMembership(int[][] pairs, int from, int to) {
        return read(() -> {
            long total = 0;
            for (int i = from; i < to; i++) {
                total += hg.count(graph, hg.and(hg.incident(nodes[pairs[i][0]]), hg.incident(nodes[pairs[i][1]])));
            }
            return total;
        });
    }

    @Override
    public void update(int[] probes, int from, int to, int round) {
        transact(() -> {
            for (int i = from; i < to; i++) {
                graph.replace(nodes[probes[i]], "updated-" + round + "-" + i);
            }
            return null;
        });
    }

    @Override
    public void largeEdge(int count) {
        HGHandle[] targets = new HGHandle[count];
        System.arraycopy(nodes, 0, targets, 0, count);
        large = transact(() -> graph.getPersistentHandle(graph.add(new HGPlainLink(targets))));
    }

    @Override
    public long scanLargeEdge() {
        return read(() -> {
            HGLink link = graph.get(large);
            long total = 0;
            for (int t = 0; t < link.getArity(); t++) {
                if (link.getTargetAt(t) != null) {
                    total++;
                }
            }
            return total;
        });
    }

    @Override
    public long probeLargeEdge(int[] probes, int from, int to) {
        return read(() -> {
            long found = 0;
            for (int i = from; i < to; i++) {
                found += graph.getIncidenceSet(nodes[probes[i]]).contains(large) ? 1 : 0;
            }
            return found;
        });
    }

    @Override
    public void reopen() {
        graph.close();
        graph = HGEnvironment.get(directory.toString(), configuration);
    }

    @Override
    public void close() {
        graph.close();
    }
}
