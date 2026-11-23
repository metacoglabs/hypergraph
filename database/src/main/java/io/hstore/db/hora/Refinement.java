// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import io.hstore.db.Reader;
import io.hstore.db.hora.NestingSlots.ChildLink;
import io.hstore.engine.HStoreException;
import io.hstore.engine.topology.Hyperedge;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.Weight;
import io.hstore.engine.tree.Summary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.LongStream;

public final class Refinement {

    public enum Mode { ALL, DISTINCT }

    public record Level(int edgeType, List<String> roles) {
        public Level {
            roles = List.copyOf(roles);
        }
    }

    public record LeafSpec(OptionalInt type, List<String> roles, OptionalLong anchor) {
        public LeafSpec {
            roles = List.copyOf(roles);
        }
    }

    public record Spec(List<Long> roots, OptionalInt rootType, List<Level> levels, LeafSpec leaf, OptionalLong validAt,
                       double weightLow, double weightHigh, Mode mode, int k) {
        public Spec {
            roots = List.copyOf(roots);
            levels = List.copyOf(levels);
            if (k < 1) {
                throw HStoreException.invalid("TOP must be at least 1");
            }
            if (weightLow > weightHigh) {
                throw HStoreException.invalid("weight range is empty");
            }
            if (weightLow < 0 || weightHigh > 1) {
                throw HStoreException.invalid("REFINE scores multiply membership weights and needs a weight range inside [0, 1]");
            }
        }
    }

    public record Result(List<Long> path, long leaf, double score) {
        public Result {
            path = List.copyOf(path);
        }
    }

    public record Outcome(List<Result> results, boolean prefix) {
    }

    private sealed interface Item permits Item.Edge, Item.Leaf {
        double bound();

        long[] path();

        record Edge(long edge, int level, double prefix, double bound, boolean opened, long[] path) implements Item {
        }

        record Leaf(long member, double bound, long[] path) implements Item {
        }
    }

    private static final Comparator<Item> ORDER = Comparator.comparingDouble(Item::bound).reversed()
            .thenComparingInt(item -> item instanceof Item.Leaf ? 0 : 1)
            .thenComparing(Item::path, Arrays::compare)
            .thenComparingLong(item -> item instanceof Item.Leaf(long member, double _, long[] _) ? member : 0);

    private final Reader reader;

    private Refinement(Reader reader) {
        this.reader = reader;
    }

    public static Refinement on(Reader reader) {
        return new Refinement(reader);
    }

    public Outcome run(Spec spec, Budget.Meter meter) {
        for (long root : spec.roots()) {
            reader.require(root);
        }
        if (spec.levels().size() + 1 > meter.budget().maxDepth()) {
            throw HStoreException.invalid("a refinement of " + (spec.levels().size() + 1) + " levels exceeds the depth limit of "
                    + meter.budget().maxDepth());
        }
        return spec.leaf().anchor().isPresent() ? bottomUp(spec, meter) : topDown(spec, meter);
    }

    private long roleMask(List<String> roles) {
        long mask = 0;
        for (String role : roles) {
            for (int roleSet : reader.database().engine().dictionary().roleSetsContaining(role)) {
                mask |= Incidence.roleBit(roleSet);
            }
        }
        return mask;
    }

    private boolean rolesMatch(List<String> wanted, int roleSet) {
        return wanted.isEmpty() || reader.roles(roleSet).stream().anyMatch(wanted::contains);
    }

    private boolean passes(Spec spec, List<String> roles, int roleSet, long weight, long validFrom, long validTo) {
        double value = Weight.toDouble(weight);
        return rolesMatch(roles, roleSet) && value >= spec.weightLow() && value <= spec.weightHigh()
                && spec.validAt().stream().allMatch(t -> validFrom <= t && t < validTo);
    }

    private boolean summaryPasses(Spec spec, Summary summary, List<String> roles) {
        if (summary.isEmpty()) {
            return false;
        }
        if (!roles.isEmpty() && !summary.mayHaveRoles(roleMask(roles))) {
            return false;
        }
        if (spec.validAt().isPresent() && !summary.mayBeValidAt(spec.validAt().getAsLong())) {
            return false;
        }
        return summary.mayHaveWeightIn(Weight.of(spec.weightLow()), Weight.of(spec.weightHigh()));
    }

    private static long[] extend(long[] path, long edge) {
        long[] next = Arrays.copyOf(path, path.length + 1);
        next[path.length] = edge;
        return next;
    }

    private static boolean contains(long[] path, long edge) {
        for (long on : path) {
            if (on == edge) {
                return true;
            }
        }
        return false;
    }

    private Outcome topDown(Spec spec, Budget.Meter meter) {
        PriorityQueue<Item> frontier = new PriorityQueue<>(ORDER);
        LongStream roots = spec.roots().isEmpty() && spec.rootType().isPresent()
                ? reader.atomsOfType(spec.rootType().getAsInt())
                : spec.roots().stream().mapToLong(Long::longValue).distinct();
        roots.forEach(root -> {
            meter.consume(1);
            if (!reader.edgeAtom(root) || spec.rootType().stream().anyMatch(type -> type != reader.typeOf(root).orElse(-1))) {
                return;
            }
            frontier.add(new Item.Edge(root, 0, 1.0, 1.0, false, new long[]{root}));
        });
        List<Result> results = new ArrayList<>();
        Set<Long> emitted = new HashSet<>();
        while (!frontier.isEmpty() && results.size() < spec.k()) {
            Item item = frontier.poll();
            if (!meter.tryConsume(1)) {
                return new Outcome(results, true);
            }
            switch (item) {
                case Item.Leaf(long member, double score, long[] path) -> {
                    if (contains(path, member) || !reader.visible(member)
                            || spec.leaf().type().stream().anyMatch(type -> type != reader.typeOf(member).orElse(-1))) {
                        continue;
                    }
                    if (spec.mode() == Mode.DISTINCT && !emitted.add(member)) {
                        continue;
                    }
                    results.add(new Result(Arrays.stream(path).boxed().toList(), member, score));
                }
                case Item.Edge(long edge, int level, double prefix, double bound, boolean opened, long[] path) -> {
                    Hyperedge hyperedge = reader.edge(edge);
                    Summary summary = hyperedge.summary();
                    if (!opened) {
                        double tightened = summary.isEmpty() ? 0 : prefix * Math.min(Weight.toDouble(summary.weightMax()), spec.weightHigh());
                        Item next = frontier.peek();
                        if (next != null && tightened < next.bound()) {
                            frontier.add(new Item.Edge(edge, level, prefix, tightened, true, path));
                            continue;
                        }
                    }
                    if (level < spec.levels().size()) {
                        Level child = spec.levels().get(level);
                        reader.childLinks(edge).scan(found -> summaryPasses(spec, found, child.roles())).forEach(entry -> {
                            long childEdge = entry.key();
                            ChildLink link = entry.value();
                            meter.consume(1);
                            if (contains(path, childEdge) || !reader.owns(childEdge)
                                    || !passes(spec, child.roles(), link.roleSet(), link.weight(), link.validFrom(), link.validTo())
                                    || reader.typeOf(childEdge).orElse(-1) != child.edgeType()) {
                                return;
                            }
                            double reached = prefix * Weight.toDouble(link.weight());
                            frontier.add(new Item.Edge(childEdge, level + 1, reached, reached, false, extend(path, childEdge)));
                        });
                    } else {
                        hyperedge.members().scan(found -> summaryPasses(spec, found, spec.leaf().roles())).forEach(entry -> {
                            Incidence incidence = entry.value();
                            meter.consume(1);
                            if (passes(spec, spec.leaf().roles(), incidence.roleSet(), incidence.weight(), incidence.validFrom(), incidence.validTo())) {
                                frontier.add(new Item.Leaf(incidence.member(), prefix * incidence.weightValue(), path));
                            }
                        });
                    }
                }
            }
        }
        return new Outcome(results, false);
    }

    private Outcome bottomUp(Spec spec, Budget.Meter meter) {
        long anchor = spec.leaf().anchor().getAsLong();
        reader.require(anchor);
        if (spec.leaf().type().stream().anyMatch(type -> type != reader.typeOf(anchor).orElse(-1))) {
            return new Outcome(List.of(), false);
        }
        Set<Long> roots = new HashSet<>(spec.roots());
        if (roots.isEmpty() && spec.rootType().isEmpty()) {
            return new Outcome(List.of(), false);
        }
        List<Result> found = new ArrayList<>();
        boolean[] stopped = {false};
        int lastLevel = spec.levels().size();
        reader.incidentEdges(anchor).forEach(posting -> {
            meter.consume(1);
            if (!rolesMatch(spec.leaf().roles(), posting.roleSet())
                    || spec.validAt().stream().anyMatch(t -> !(posting.validFrom() <= t && t < posting.validTo()))) {
                return;
            }
            long edge = posting.edge();
            reader.incidence(edge, anchor).ifPresent(incidence -> {
                if (passes(spec, spec.leaf().roles(), incidence.roleSet(), incidence.weight(), incidence.validFrom(), incidence.validTo())
                        && edgeOfLevel(spec, edge, lastLevel)) {
                    climb(spec, roots, edge, lastLevel, incidence.weightValue(), new ArrayList<>(List.of(anchor, edge)), anchor, found, meter, stopped);
                }
            });
        });
        found.sort(Comparator.comparingDouble(Result::score).reversed()
                .thenComparing(result -> result.path().stream().mapToLong(Long::longValue).toArray(), Arrays::compare));
        List<Result> results = new ArrayList<>();
        for (Result result : found) {
            if (results.size() >= spec.k() || (spec.mode() == Mode.DISTINCT && !results.isEmpty())) {
                break;
            }
            results.add(result);
        }
        return new Outcome(results, stopped[0]);
    }

    private boolean edgeOfLevel(Spec spec, long edge, int level) {
        if (!reader.edgeAtom(edge)) {
            return false;
        }
        int type = reader.typeOf(edge).orElse(-1);
        return level == 0 ? spec.rootType().stream().allMatch(wanted -> wanted == type) : spec.levels().get(level - 1).edgeType() == type;
    }

    private void climb(Spec spec, Set<Long> roots, long edge, int level, double score, List<Long> pathUp, long leaf,
                       List<Result> found, Budget.Meter meter, boolean[] stopped) {
        meter.consume(1);
        if (level == 0) {
            if (roots.isEmpty() ? spec.rootType().stream().allMatch(type -> reader.typeOf(edge).orElse(-1) == type) : roots.contains(edge)) {
                List<Long> path = new ArrayList<>(pathUp.subList(1, pathUp.size()));
                java.util.Collections.reverse(path);
                found.add(new Result(path, leaf, score));
            }
            return;
        }
        Level through = spec.levels().get(level - 1);
        reader.incidentEdges(edge).forEach(posting -> {
            meter.consume(1);
            long parent = posting.edge();
            if (!rolesMatch(through.roles(), posting.roleSet())
                    || spec.validAt().stream().anyMatch(t -> !(posting.validFrom() <= t && t < posting.validTo()))) {
                return;
            }
            if (pathUp.contains(parent) || !edgeOfLevel(spec, parent, level - 1)) {
                return;
            }
            reader.incidence(parent, edge).ifPresent(incidence -> {
                if (passes(spec, through.roles(), incidence.roleSet(), incidence.weight(), incidence.validFrom(), incidence.validTo())) {
                    pathUp.add(parent);
                    climb(spec, roots, parent, level - 1, score * incidence.weightValue(), pathUp, leaf, found, meter, stopped);
                    pathUp.removeLast();
                }
            });
        });
    }
}
