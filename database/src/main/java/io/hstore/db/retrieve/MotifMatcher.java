// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.Reader;
import io.hstore.db.hora.Budget;
import io.hstore.engine.HStoreException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Set;

public final class MotifMatcher {

    public enum Weight { INDEPENDENT, DISJOINT, COMMON }

    public record Region(int region, OptionalInt memberType, OptionalLong member, List<Set<String>> roles) {
        public Region {
            if (region < 1 || region > 7) {
                throw HStoreException.invalid("a region is one of 001 to 111, got " + region);
            }
            roles = roles.stream().map(Set::copyOf).toList();
            if (!roles.isEmpty() && roles.size() != Integer.bitCount(region)) {
                throw HStoreException.invalid("region " + name(region) + " lies in " + Integer.bitCount(region)
                        + " hyperedges, so ROLES needs that many entries");
            }
        }
    }

    public record Query(int code, List<OptionalInt> types, OptionalLong anchor, List<Region> regions, boolean nested, Optional<Weight> rankBy,
                        int limit) {
        public Query {
            if (code < 1 || code > 127) {
                throw HStoreException.invalid("motif " + code + " is not a class of three connected hyperedges; the classes are " + MotifClasses.CLASSES);
            }
            MotifClasses.requireClass(MotifClasses.canonical(code));
            if (types.size() != 3) {
                throw HStoreException.invalid("a motif has three positions");
            }
            types = List.copyOf(types);
            regions = List.copyOf(regions);
            for (Region region : regions) {
                if ((code >> (region.region() - 1) & 1) == 0) {
                    throw HStoreException.invalid("region " + name(region.region()) + " is empty in motif " + code);
                }
            }
            if (limit < 1) {
                throw HStoreException.invalid("LIMIT must be at least 1");
            }
        }
    }

    public record Occurrence(long first, long second, long third, int code, double weight) {
        long[] sorted() {
            long[] ids = {first, second, third};
            Arrays.sort(ids);
            return ids;
        }
    }

    public record Result(List<Occurrence> occurrences, boolean complete) {
        public Result {
            occurrences = List.copyOf(occurrences);
        }
    }

    private static final Comparator<Occurrence> BEST_FIRST = Comparator.comparingDouble(Occurrence::weight).reversed()
            .thenComparing(Occurrence::sorted, Arrays::compare);

    private static final class Full extends RuntimeException {
        Full() {
            super(null, null, false, false);
        }
    }

    private final Reader reader;
    private final Query query;
    private final Projection projection;
    private final Budget.Meter meter;
    private final List<Occurrence> found = new ArrayList<>();
    private final PriorityQueue<Occurrence> best = new PriorityQueue<>(BEST_FIRST.reversed());

    private MotifMatcher(Reader reader, Query query, Budget.Meter meter) {
        this.reader = reader;
        this.query = query;
        this.meter = meter;
        Set<Integer> scope = new HashSet<>();
        if (query.types().stream().allMatch(OptionalInt::isPresent)) {
            query.types().forEach(type -> scope.add(type.getAsInt()));
        } else {
            scope.addAll(reader.readableEdgeTypes());
        }
        for (OptionalInt type : query.types()) {
            if (type.isPresent() && !reader.readableEdgeTypes().contains(type.getAsInt())) {
                throw HStoreException.invalid("type " + type.getAsInt() + " is not a readable hyperedge type");
            }
        }
        this.projection = new Projection(reader, scope, meter);
    }

    public static Result find(Reader reader, Query query, Budget.Meter meter) {
        return new MotifMatcher(reader, query, meter).run();
    }

    static String name(int region) {
        return "" + (region >> 2 & 1) + (region >> 1 & 1) + (region & 1);
    }

    private Result run() {
        query.anchor().ifPresent(reader::require);
        query.regions().forEach(region -> region.member().ifPresent(reader::require));
        if (query.anchor().isPresent() && !projection.inScope(query.anchor().getAsLong())) {
            throw HStoreException.invalid("atom " + query.anchor().getAsLong() + " is not a hyperedge of the motif's types");
        }
        boolean complete = true;
        try {
            enumerate();
        } catch (Full done) {
            complete = true;
        } catch (HStoreException.ResourceLimit exhausted) {
            complete = false;
        }
        if (query.rankBy().isPresent()) {
            List<Occurrence> ranked = new ArrayList<>(best);
            ranked.sort(BEST_FIRST);
            return new Result(ranked, complete);
        }
        return new Result(found, complete);
    }

    private void enumerate() {
        if (query.anchor().isPresent()) {
            projection.containing(query.anchor().getAsLong(), this::consider);
            return;
        }
        OptionalLong fixed = query.regions().stream().filter(region -> region.member().isPresent()).map(Region::member).findFirst()
                .orElse(OptionalLong.empty());
        if (fixed.isPresent()) {
            long member = fixed.getAsLong();
            List<Long> holders = new ArrayList<>();
            reader.forEachIncidentEdge(member, edge -> {
                if (projection.inScope(edge)) {
                    holders.add(edge);
                }
            });
            holders.stream().sorted().distinct().forEach(edge -> projection.containing(edge, (a, b, c, ab, bc, ca) -> {
                if (!(b < a && projection.contains(b, member)) && !(c < a && projection.contains(c, member))) {
                    consider(a, b, c, ab, bc, ca);
                }
            }));
            return;
        }
        Set<Integer> scope = new HashSet<>();
        if (query.types().stream().allMatch(OptionalInt::isPresent)) {
            query.types().forEach(type -> scope.add(type.getAsInt()));
        } else {
            scope.addAll(reader.readableEdgeTypes());
        }
        scope.stream().sorted().forEach(type -> reader.atomsOfType(type).forEach(centre -> projection.centred(centre, this::consider)));
    }

    private double bound(long ab, long bc, long ca) {
        return switch (query.rankBy().orElseThrow()) {
            case COMMON, DISJOINT -> Math.min(ab, Math.min(bc, ca));
            case INDEPENDENT -> Double.POSITIVE_INFINITY;
        };
    }

    private static double weight(Weight weight, long[] size) {
        return switch (weight) {
            case COMMON -> size[7];
            case DISJOINT -> Math.min(size[3], Math.min(size[5], size[6])) / (1.0 + size[7]);
            case INDEPENDENT -> Math.min(size[1], Math.min(size[2], size[4])) / (1.0 + size[3] + size[5] + size[6] + size[7]);
        };
    }

    private void consider(long a, long b, long c, long ab, long bc, long ca) {
        if (query.rankBy().isPresent() && best.size() >= query.limit() && bound(ab, bc, ca) < best.peek().weight()) {
            return;
        }
        meter.consume(1);
        long[] size = projection.regionSizes(a, b, c, ab, bc, ca);
        int code = MotifClasses.code(size);
        if (MotifClasses.canonical(code) != MotifClasses.canonical(query.code())) {
            return;
        }
        long[] data = {a, b, c};
        for (int[] p : MotifClasses.PERMUTATIONS) {
            if (MotifClasses.permute(code, p) != query.code()) {
                continue;
            }
            long[] positions = new long[3];
            for (int i = 0; i < 3; i++) {
                positions[p[i]] = data[i];
            }
            if (typed(positions) && (!query.nested() || nested(positions)) && query.regions().stream().allMatch(region -> holds(region, positions))) {
                emit(new Occurrence(positions[0], positions[1], positions[2], query.code(),
                        query.rankBy().map(weight -> weight(weight, size)).orElse(0.0)));
                return;
            }
        }
    }

    private void emit(Occurrence occurrence) {
        if (query.rankBy().isEmpty()) {
            found.add(occurrence);
            if (found.size() >= query.limit()) {
                throw new Full();
            }
            return;
        }
        best.add(occurrence);
        if (best.size() > query.limit()) {
            best.poll();
        }
    }

    private boolean typed(long[] positions) {
        for (int i = 0; i < 3; i++) {
            OptionalInt type = query.types().get(i);
            if (type.isPresent() && reader.typeOf(positions[i]).orElse(-1) != type.getAsInt()) {
                return false;
            }
        }
        return true;
    }

    private boolean nested(long[] positions) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (i != j && projection.contains(positions[j], positions[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean holds(Region region, long[] positions) {
        long[] smallest = null;
        for (int i = 0; i < 3; i++) {
            if ((region.region() >> i & 1) != 0) {
                long[] members = projection.members(positions[i]);
                if (smallest == null || members.length < smallest.length) {
                    smallest = members;
                }
            }
        }
        for (long member : smallest) {
            meter.consume(1);
            if (inRegion(region.region(), positions, member) && satisfies(region, positions, member)) {
                return true;
            }
        }
        return false;
    }

    private boolean inRegion(int region, long[] positions, long member) {
        for (int i = 0; i < 3; i++) {
            if (projection.contains(positions[i], member) != ((region >> i & 1) != 0)) {
                return false;
            }
        }
        return true;
    }

    private boolean satisfies(Region region, long[] positions, long member) {
        if (region.member().isPresent() && region.member().getAsLong() != member) {
            return false;
        }
        if (region.memberType().isPresent() && reader.typeOf(member).orElse(-1) != region.memberType().getAsInt()) {
            return false;
        }
        int index = 0;
        for (int i = 0; i < 3; i++) {
            if ((region.region() >> i & 1) == 0) {
                continue;
            }
            Set<String> allowed = region.roles().isEmpty() ? Set.of() : region.roles().get(index);
            index++;
            if (allowed.isEmpty()) {
                continue;
            }
            int roleSet = reader.edge(positions[i]).get(member).map(incidence -> incidence.roleSet()).orElse(0);
            if (reader.roles(roleSet).stream().noneMatch(allowed::contains)) {
                return false;
            }
        }
        return true;
    }
}
