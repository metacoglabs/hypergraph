// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import io.hstore.db.Reader;
import io.hstore.engine.HStoreException;
import io.hstore.engine.topology.Hyperedge;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.Weight;
import io.hstore.engine.txn.IncidentEdge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

public final class Expansion {

    public enum Stop { RESULT_SIZE, NO_USEFUL_CANDIDATE, BUDGET, FRONTIER_EXHAUSTED, FRONTIER_TRUNCATED }

    public interface Relevance {
        double bound(long edge);

        OptionalDouble score(long edge);

        Relevance NONE = new Relevance() {
            public double bound(long edge) {
                return 1;
            }

            public OptionalDouble score(long edge) {
                return OptionalDouble.of(1);
            }
        };
    }

    public record Model(Map<Integer, Double> typeFactors, Map<String, Double> exit, Map<String, Double> entry, OptionalLong validAt,
                        double hubDamping, double edgeDamping, double gate) {
        public Model {
            typeFactors = Map.copyOf(typeFactors);
            exit = Map.copyOf(exit);
            entry = Map.copyOf(entry);
            for (Map<?, Double> factors : List.of(typeFactors, exit, entry)) {
                factors.values().forEach(factor -> {
                    if (!(factor >= 0 && factor <= 1)) {
                        throw HStoreException.invalid("type and role factors must lie in [0, 1]");
                    }
                });
            }
            if (!(hubDamping >= 0 && hubDamping <= 4) || !(edgeDamping >= 0 && edgeDamping <= 4)) {
                throw HStoreException.invalid("damping exponents must lie in [0, 4]");
            }
            if (!(gate >= 0 && gate <= 1)) {
                throw HStoreException.invalid("GATE must lie in [0, 1]");
            }
        }

        public static Model plain() {
            return new Model(Map.of(), Map.of(), Map.of(), OptionalLong.empty(), 0.5, 0.5, 1.0);
        }
    }

    public record Accepted(long edge, double value, double relevance, long via, long parent) {
    }

    public record Result(List<Accepted> edges, List<Long> connectors, double bound, double droppedBound, Stop stop) {
        public Result {
            edges = List.copyOf(edges);
            connectors = List.copyOf(connectors);
        }
    }

    private sealed interface Entry permits AtomReached, Incidents, EdgeBound, EdgeExact {
        double key();

        int rank();

        long id();
    }

    private record AtomReached(long atom, double key, long parent) implements Entry {
        public int rank() {
            return 0;
        }

        public long id() {
            return atom;
        }
    }

    private record EdgeExact(long edge, double key, double relevance, long via) implements Entry {
        public int rank() {
            return 1;
        }

        public long id() {
            return edge;
        }
    }

    private record Incidents(long atom, double value, double hub, double key, long next) implements Entry {
        public int rank() {
            return 2;
        }

        public long id() {
            return atom;
        }
    }

    private record EdgeBound(long edge, double key, double base, long via) implements Entry {
        public int rank() {
            return 3;
        }

        public long id() {
            return edge;
        }
    }

    private static final Comparator<Entry> ORDER = Comparator.comparingDouble(Entry::key).reversed()
            .thenComparingInt(Entry::rank)
            .thenComparingLong(Entry::id);

    static final int CHUNK = 64;

    private final Reader reader;
    private final Map<Long, OptionalInt> types = new HashMap<>();
    private final Map<Long, Double> relevance = new HashMap<>();
    private final Map<Long, Long> parents = new HashMap<>();

    private Expansion(Reader reader) {
        this.reader = reader;
    }

    public static Expansion on(Reader reader) {
        return new Expansion(reader);
    }

    private double roleFactor(Map<String, Double> factors, int roleSet) {
        if (factors.isEmpty()) {
            return 1.0;
        }
        List<String> roles = reader.roles(roleSet);
        if (roles.isEmpty()) {
            return 1.0;
        }
        return roles.stream().mapToDouble(role -> factors.getOrDefault(role, 1.0)).max().orElse(1.0);
    }

    private OptionalInt edgeType(long edge) {
        return types.computeIfAbsent(edge, id -> reader.edgeAtom(id) ? reader.typeOf(id) : OptionalInt.empty());
    }

    private static double gate(Model model, double relevance) {
        return model.gate() + (1 - model.gate()) * relevance;
    }

    public Result run(Map<Long, Double> seeds, Model model, Relevance query, int k, double tau, int frontierCap, Budget.Meter meter) {
        if (k < 1) {
            throw HStoreException.invalid("TOP must be at least 1");
        }
        if (frontierCap < 1) {
            throw HStoreException.invalid("the frontier cap must be at least 1");
        }
        PriorityQueue<Entry> frontier = new PriorityQueue<>(ORDER);
        for (Map.Entry<Long, Double> seed : new TreeMap<>(seeds).entrySet()) {
            if (!(seed.getValue() > 0 && seed.getValue() <= 1)) {
                throw HStoreException.invalid("seed weights must lie in (0, 1]");
            }
            reader.require(seed.getKey());
            if (reader.edgeAtom(seed.getKey())) {
                frontier.add(new EdgeExact(seed.getKey(), seed.getValue(), 1.0, -1));
            }
            frontier.add(new AtomReached(seed.getKey(), seed.getValue(), -1));
        }
        if (frontier.isEmpty()) {
            throw HStoreException.invalid("an expansion needs at least one seed");
        }
        Set<Long> settled = new HashSet<>();
        Set<Long> acceptedEdges = new HashSet<>();
        Map<Long, Double> bestBound = new HashMap<>();
        List<Accepted> accepted = new ArrayList<>();
        List<Long> connectors = new ArrayList<>();
        double dropped = 0;
        double pending = 0;
        Stop stop = Stop.FRONTIER_EXHAUSTED;
        while (!frontier.isEmpty()) {
            Entry top = frontier.peek();
            if (accepted.size() >= k) {
                stop = Stop.RESULT_SIZE;
                break;
            }
            if (top.key() < tau) {
                stop = Stop.NO_USEFUL_CANDIDATE;
                break;
            }
            if (!meter.tryConsume(1)) {
                stop = Stop.BUDGET;
                break;
            }
            frontier.poll();
            switch (top) {
                case AtomReached(long atom, double value, long parent) -> {
                    if (!settled.add(atom) || !reader.visible(atom)) {
                        continue;
                    }
                    parents.put(atom, parent);
                    connectors.add(atom);
                    if (!meter.tryConsume(Math.max(1, reader.postingCount(atom)))) {
                        stop = Stop.BUDGET;
                        pending = value;
                        break;
                    }
                    double hub = Math.pow(Math.max(1, reader.degree(atom)), -model.hubDamping());
                    frontier.add(new Incidents(atom, value, hub, value * hub, Long.MIN_VALUE));
                }
                case Incidents(long atom, double value, double hub, double key, long next) -> {
                    List<io.hstore.engine.tree.Entry<io.hstore.engine.index.IndexValues.Incident>> chunk =
                            reader.incidentPostings(atom).range(next, Long.MAX_VALUE).limit(CHUNK + 1).toList();
                    int taken = Math.min(CHUNK, chunk.size());
                    if (!meter.tryConsume(taken)) {
                        frontier.add(top);
                        stop = Stop.BUDGET;
                        break;
                    }
                    boolean refused = false;
                    for (int i = 0; i < taken && !refused; i++) {
                        long edge = chunk.get(i).key();
                        var posting = chunk.get(i).value();
                        if (acceptedEdges.contains(edge)) {
                            continue;
                        }
                        OptionalInt type = edgeType(edge);
                        if (type.isEmpty() || (!model.typeFactors().isEmpty() && !model.typeFactors().containsKey(type.getAsInt()))) {
                            continue;
                        }
                        if (model.validAt().isPresent() && !posting.validAt(model.validAt().getAsLong())) {
                            continue;
                        }
                        double base = value * hub * roleFactor(model.exit(), posting.roleSet()) * model.typeFactors().getOrDefault(type.getAsInt(), 1.0);
                        if (base <= 0) {
                            continue;
                        }
                        double relevanceBound;
                        try {
                            relevanceBound = Math.max(0, Math.min(1, query.bound(edge)));
                        } catch (HStoreException.ResourceLimit exhausted) {
                            frontier.add(new Incidents(atom, value, hub, key, edge));
                            refused = true;
                            continue;
                        }
                        double bound = base * gate(model, relevanceBound);
                        if (bound <= bestBound.getOrDefault(edge, -1.0)) {
                            continue;
                        }
                        bestBound.put(edge, bound);
                        frontier.add(new EdgeBound(edge, bound, base, atom));
                    }
                    if (refused) {
                        stop = Stop.BUDGET;
                        break;
                    }
                    if (chunk.size() > CHUNK) {
                        frontier.add(new Incidents(atom, value, hub, key, chunk.get(CHUNK).key()));
                    }
                }
                case EdgeBound(long edge, double bound, double base, long via) -> {
                    if (acceptedEdges.contains(edge)) {
                        continue;
                    }
                    double r;
                    try {
                        r = relevance.computeIfAbsent(edge, id -> {
                            OptionalDouble found = query.score(id);
                            return found.isPresent() ? Math.max(0, Math.min(1, found.getAsDouble())) : 0.0;
                        });
                    } catch (HStoreException.ResourceLimit exhausted) {
                        frontier.add(top);
                        stop = Stop.BUDGET;
                        break;
                    }
                    if (base * gate(model, r) > 0) {
                        frontier.add(new EdgeExact(edge, base * gate(model, r), r, via));
                    }
                }
                case EdgeExact(long edge, double value, double r, long via) -> {
                    if (!acceptedEdges.add(edge)) {
                        continue;
                    }
                    accepted.add(new Accepted(edge, value, r, via, via < 0 ? -1L : parents.getOrDefault(via, -1L)));
                    if (accepted.size() >= k) {
                        pending = Math.max(pending, value);
                        continue;
                    }
                    Hyperedge hyperedge = reader.edge(edge);
                    double weightMax = hyperedge.summary().isEmpty() ? 0 : Weight.toDouble(hyperedge.summary().weightMax());
                    if (weightMax <= 0) {
                        continue;
                    }
                    long cardinality = Math.max(1, reader.cardinality(edge));
                    if (!meter.tryConsume(cardinality)) {
                        pending = value;
                        stop = Stop.BUDGET;
                        break;
                    }
                    double damping = Math.pow(cardinality, -model.edgeDamping());
                    for (var entry : hyperedge.members().stream().toList()) {
                        Incidence incidence = entry.value();
                        if (settled.contains(incidence.member())
                                || (model.validAt().isPresent() && !incidence.validAt(model.validAt().getAsLong()))) {
                            continue;
                        }
                        double out = value * roleFactor(model.entry(), incidence.roleSet()) * Math.max(0, incidence.weightValue()) / weightMax * damping;
                        if (out > 0) {
                            frontier.add(new AtomReached(incidence.member(), out, edge));
                        }
                    }
                }
            }
            if (stop == Stop.BUDGET) {
                break;
            }
            if (frontier.size() > frontierCap) {
                int keep = Math.max(1, frontierCap / 2);
                List<Entry> kept = new ArrayList<>(keep);
                for (int i = 0; i < keep; i++) {
                    kept.add(frontier.poll());
                }
                dropped = Math.max(dropped, frontier.peek().key());
                frontier.clear();
                frontier.addAll(kept);
            }
        }
        double bound = frontier.isEmpty() ? 0 : frontier.peek().key();
        if (stop == Stop.FRONTIER_EXHAUSTED && dropped > 0) {
            stop = Stop.FRONTIER_TRUNCATED;
        }
        return new Result(accepted, connectors, Math.max(Math.max(bound, dropped), pending), dropped, stop);
    }
}
