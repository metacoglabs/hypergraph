// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import io.hstore.db.Reader;
import io.hstore.db.evidence.EvidencePolicy;
import io.hstore.db.evidence.Provenance;
import io.hstore.engine.HStoreException;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.txn.IncidentEdge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

public final class Diffusion {

    public record RoleFactors(Map<String, Double> exit, Map<String, Double> entry) {
        public static final RoleFactors NONE = new RoleFactors(Map.of(), Map.of());

        public RoleFactors {
            exit = Map.copyOf(exit);
            entry = Map.copyOf(entry);
            for (Map<String, Double> factors : List.of(exit, entry)) {
                factors.forEach((role, factor) -> {
                    if (!(factor >= 0) || Double.isInfinite(factor)) {
                        throw HStoreException.invalid("role factor for " + role + " must be a finite number of at least 0");
                    }
                });
            }
        }
    }

    public record Model(Set<Integer> edgeTypes, Map<Integer, Double> typeWeight, RoleFactors roles, OptionalLong validAt,
                        EvidencePolicy policy) {
        public Model {
            edgeTypes = Set.copyOf(edgeTypes);
            typeWeight = Map.copyOf(typeWeight);
            typeWeight.forEach((type, weight) -> {
                if (!(weight >= 0) || Double.isInfinite(weight)) {
                    throw HStoreException.invalid("type weights must be finite numbers of at least 0");
                }
            });
        }

        public static Model plain() {
            return new Model(Set.of(), Map.of(), RoleFactors.NONE, OptionalLong.empty(), EvidencePolicy.ANY);
        }
    }

    public record Params(double alpha, double epsilon, int topAtoms, int topEdges) {
        public Params {
            if (!(alpha > 0 && alpha < 1)) {
                throw HStoreException.invalid("ALPHA must lie strictly between 0 and 1");
            }
            if (!(epsilon > 0) || epsilon > 1) {
                throw HStoreException.invalid("EPSILON must lie in (0, 1]");
            }
            if (topAtoms < 0 || topEdges < 0) {
                throw HStoreException.invalid("result sizes must not be negative");
            }
        }
    }

    public record Scored(long id, double estimate, double upper) {
    }

    public record Result(List<Scored> atoms, List<Scored> edges, double residual, long reads, boolean exhausted) {
        public Result {
            atoms = List.copyOf(atoms);
            edges = List.copyOf(edges);
        }
    }

    private record Due(boolean edge, long id, double ratio) {
    }

    private static final Comparator<Due> ORDER = Comparator.comparingDouble(Due::ratio).reversed()
            .thenComparing(Due::edge)
            .thenComparingLong(Due::id);

    private final Reader reader;
    private final Map<Long, Double> atomResidual = new HashMap<>();
    private final Map<Long, Double> edgeResidual = new HashMap<>();
    private final Map<Long, Double> estimate = new TreeMap<>();
    private final Map<Long, Double> arrived = new TreeMap<>();
    private final Map<Long, Boolean> owned = new HashMap<>();
    private final Map<Long, OptionalInt> edgeTypes = new HashMap<>();
    private final PriorityQueue<Due> due = new PriorityQueue<>(ORDER);
    private Map<Long, Double> atomSeeds = Map.of();
    private Map<Long, Double> edgeSeeds = Map.of();
    private Model model;
    private Params params;
    private long reads;

    private Diffusion(Reader reader) {
        this.reader = reader;
    }

    public static Diffusion on(Reader reader) {
        return new Diffusion(reader);
    }

    public Result run(Map<Long, Double> seeds, Model model, Params params, Budget.Meter meter) {
        this.model = model;
        this.params = params;
        double total = 0;
        Map<Long, Double> atoms = new LinkedHashMap<>();
        Map<Long, Double> edges = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> seed : new TreeMap<>(seeds).entrySet()) {
            if (!(seed.getValue() > 0) || Double.isInfinite(seed.getValue())) {
                throw HStoreException.invalid("seed weights must be finite and positive");
            }
            reader.require(seed.getKey());
            (reader.edgeAtom(seed.getKey()) ? edges : atoms).put(seed.getKey(), seed.getValue());
            total += seed.getValue();
        }
        if (total == 0) {
            throw HStoreException.invalid("diffusion needs at least one seed");
        }
        double sum = total;
        atoms.replaceAll((_, weight) -> weight / sum);
        edges.replaceAll((_, weight) -> weight / sum);
        atomSeeds = atoms;
        edgeSeeds = edges;
        atoms.forEach((atom, weight) -> addAtom(atom, weight));
        edges.forEach((edge, weight) -> addEdge(edge, weight));
        boolean exhausted = false;
        while (!due.isEmpty()) {
            Due next = due.poll();
            double mass = next.edge() ? edgeResidual.getOrDefault(next.id(), 0.0) : atomResidual.getOrDefault(next.id(), 0.0);
            long cost = next.edge() ? edgeCost(next.id()) : atomCost(next.id());
            if (mass <= 0 || mass < params.epsilon() * cost) {
                continue;
            }
            if (!meter.tryConsume(cost)) {
                exhausted = true;
                break;
            }
            reads += cost;
            if (next.edge()) {
                pushEdge(next.id(), mass);
            } else {
                pushAtom(next.id(), mass);
            }
        }
        double residual = 0;
        for (double value : atomResidual.values()) {
            residual += value;
        }
        for (double value : edgeResidual.values()) {
            residual += value;
        }
        double certificate = residual;
        List<Scored> atomScores = estimate.entrySet().stream()
                .filter(entry -> owns(entry.getKey()))
                .map(entry -> new Scored(entry.getKey(), entry.getValue(), entry.getValue() + certificate))
                .sorted(Comparator.comparingDouble(Scored::estimate).reversed().thenComparingLong(Scored::id))
                .limit(params.topAtoms())
                .toList();
        List<Scored> edgeScores = arrived.entrySet().stream()
                .map(entry -> new Scored(entry.getKey(), params.alpha() * entry.getValue(),
                        params.alpha() * entry.getValue() + (1 - params.alpha()) * certificate))
                .sorted(Comparator.comparingDouble(Scored::estimate).reversed().thenComparingLong(Scored::id))
                .limit(params.topEdges())
                .toList();
        return new Result(atomScores, edgeScores, certificate, reads, exhausted);
    }

    private boolean owns(long atom) {
        return owned.computeIfAbsent(atom, id -> reader.owns(id) && reader.visible(id));
    }

    private long atomCost(long atom) {
        return owns(atom) ? Math.max(1, reader.degree(atom)) : 1;
    }

    private long edgeCost(long edge) {
        return Math.max(1, reader.cardinality(edge));
    }

    private void addAtom(long atom, double mass) {
        double now = atomResidual.merge(atom, mass, Double::sum);
        long cost = atomCost(atom);
        if (now >= params.epsilon() * cost) {
            due.add(new Due(false, atom, now / (params.epsilon() * cost)));
        }
    }

    private void addEdge(long edge, double mass) {
        double now = edgeResidual.merge(edge, mass, Double::sum);
        long cost = edgeCost(edge);
        if (now >= params.epsilon() * cost) {
            due.add(new Due(true, edge, now / (params.epsilon() * cost)));
        }
    }

    private void restart(double mass) {
        atomSeeds.forEach((atom, weight) -> addAtom(atom, mass * weight));
        edgeSeeds.forEach((edge, weight) -> addEdge(edge, mass * weight));
    }

    private double roleFactor(Map<String, Double> factors, int roleSet) {
        if (factors.isEmpty()) {
            return 1.0;
        }
        List<String> roles = reader.roles(roleSet);
        if (roles.isEmpty()) {
            return 1.0;
        }
        double best = -1;
        for (String role : roles) {
            best = Math.max(best, factors.getOrDefault(role, 1.0));
        }
        return best;
    }

    private OptionalInt edgeType(long edge) {
        return edgeTypes.computeIfAbsent(edge, id -> reader.edgeAtom(id) ? reader.typeOf(id) : OptionalInt.empty());
    }

    private boolean admittedByEvidence(long edge, long member) {
        if (model.policy().equals(EvidencePolicy.ANY)) {
            return true;
        }
        return reader.incidence(edge, member).map(incidence -> model.policy().admits(Provenance.qualifier(reader, incidence.qualifier())))
                .orElse(false);
    }

    private void pushAtom(long atom, double mass) {
        atomResidual.put(atom, 0.0);
        estimate.merge(atom, params.alpha() * mass, Double::sum);
        double onward = (1 - params.alpha()) * mass;
        if (!owns(atom)) {
            restart(onward);
            return;
        }
        List<long[]> targets = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0;
        for (IncidentEdge incident : reader.incidentEdges(atom).toList()) {
            long edge = incident.edge();
            OptionalInt type = edgeType(edge);
            if (type.isEmpty() || (!model.edgeTypes().isEmpty() && !model.edgeTypes().contains(type.getAsInt()))) {
                continue;
            }
            if (model.validAt().isPresent() && !(incident.validFrom() <= model.validAt().getAsLong() && model.validAt().getAsLong() < incident.validTo())) {
                continue;
            }
            double weight = model.typeWeight().getOrDefault(type.getAsInt(), 1.0) * roleFactor(model.roles().exit(), incident.roleSet());
            if (weight <= 0 || !admittedByEvidence(edge, atom)) {
                continue;
            }
            targets.add(new long[]{edge});
            weights.add(weight);
            total += weight;
        }
        if (total <= 0) {
            restart(onward);
            return;
        }
        for (int i = 0; i < targets.size(); i++) {
            double share = onward * weights.get(i) / total;
            arrived.merge(targets.get(i)[0], share, Double::sum);
            addEdge(targets.get(i)[0], share);
        }
    }

    private void pushEdge(long edge, double mass) {
        edgeResidual.put(edge, 0.0);
        List<Long> targets = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0;
        for (var entry : reader.edge(edge).members().stream().toList()) {
            Incidence incidence = entry.value();
            if (model.validAt().isPresent() && !incidence.validAt(model.validAt().getAsLong())) {
                continue;
            }
            double weight = Math.max(incidence.weightValue(), 0) * roleFactor(model.roles().entry(), incidence.roleSet());
            if (weight <= 0 || (!model.policy().equals(EvidencePolicy.ANY)
                    && !model.policy().admits(Provenance.qualifier(reader, incidence.qualifier())))) {
                continue;
            }
            targets.add(incidence.member());
            weights.add(weight);
            total += weight;
        }
        if (total <= 0) {
            restart(mass);
            return;
        }
        for (int i = 0; i < targets.size(); i++) {
            addAtom(targets.get(i), mass * weights.get(i) / total);
        }
    }
}
