// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.Reader;
import io.hstore.db.hora.Budget;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;

final class Projection {

    private static final long MEMO_LONGS = 8_000_000;
    private static final int SCOPE_ENTRIES = 1_000_000;
    private static final int CHARGE_EVERY = 1_024;

    record Neighbourhood(long[] edges, long[] overlaps) {
        long overlap(long edge) {
            int at = Arrays.binarySearch(edges, edge);
            return at < 0 ? 0 : overlaps[at];
        }

        int size() {
            return edges.length;
        }
    }

    private final Reader reader;
    private final Set<Integer> types;
    private final Budget.Meter meter;
    private final Map<Long, long[]> members = new HashMap<>();
    private final Map<Long, Neighbourhood> neighbours = new HashMap<>();
    private final Map<Long, Boolean> scope = new HashMap<>();
    private long memoised;

    Projection(Reader reader, Set<Integer> types, Budget.Meter meter) {
        this.reader = reader;
        this.types = Set.copyOf(types);
        this.meter = meter;
    }

    Reader reader() {
        return reader;
    }

    Budget.Meter meter() {
        return meter;
    }

    boolean inScope(long edge) {
        Boolean known = scope.get(edge);
        if (known != null) {
            return known;
        }
        OptionalInt type = reader.isEdge(edge) ? reader.typeOf(edge) : OptionalInt.empty();
        boolean inside = type.isPresent() && types.contains(type.getAsInt());
        if (scope.size() < SCOPE_ENTRIES) {
            scope.put(edge, inside);
        }
        return inside;
    }

    long[] members(long edge) {
        long[] known = members.get(edge);
        if (known != null) {
            return known;
        }
        long[] found = reader.memberIds(edge).filter(reader::owns).sorted().toArray();
        meter.consume(1 + found.length);
        if (memoised + found.length < MEMO_LONGS) {
            members.put(edge, found);
            memoised += found.length;
        }
        return found;
    }

    long cardinality(long edge) {
        return members(edge).length;
    }

    boolean contains(long edge, long member) {
        return Arrays.binarySearch(members(edge), member) >= 0;
    }

    Neighbourhood of(long edge) {
        Neighbourhood known = neighbours.get(edge);
        if (known != null) {
            return known;
        }
        Map<Long, Long> overlap = new TreeMap<>();
        long[] postings = {0};
        for (long member : members(edge)) {
            reader.forEachIncidentEdge(member, other -> {
                if (++postings[0] == CHARGE_EVERY) {
                    meter.consume(CHARGE_EVERY);
                    postings[0] = 0;
                }
                if (other != edge && inScope(other)) {
                    overlap.merge(other, 1L, Long::sum);
                }
            });
        }
        meter.consume(1 + postings[0]);
        long[] edges = new long[overlap.size()];
        long[] counts = new long[overlap.size()];
        int i = 0;
        for (Map.Entry<Long, Long> entry : overlap.entrySet()) {
            edges[i] = entry.getKey();
            counts[i++] = entry.getValue();
        }
        Neighbourhood found = new Neighbourhood(edges, counts);
        if (memoised + 2L * edges.length < MEMO_LONGS) {
            neighbours.put(edge, found);
            memoised += 2L * edges.length;
        }
        return found;
    }

    long overlap(long a, long b) {
        Neighbourhood known = neighbours.get(a);
        if (known == null) {
            known = neighbours.get(b);
            if (known != null) {
                return known.overlap(a);
            }
        } else {
            return known.overlap(b);
        }
        long[] left = members(a);
        long[] right = members(b);
        meter.consume(1 + Math.min(left.length, right.length));
        return intersect(left, right);
    }

    long common(long a, long b, long c) {
        long[] first = members(a);
        long[] second = members(b);
        long[] third = members(c);
        long[] smallest = first.length <= second.length && first.length <= third.length ? first : second.length <= third.length ? second : third;
        meter.consume(1 + smallest.length);
        long count = 0;
        for (long member : smallest) {
            if (Arrays.binarySearch(first, member) >= 0 && Arrays.binarySearch(second, member) >= 0 && Arrays.binarySearch(third, member) >= 0) {
                count++;
            }
        }
        return count;
    }

    private static long intersect(long[] left, long[] right) {
        long[] small = left.length <= right.length ? left : right;
        long[] large = small == left ? right : left;
        long count = 0;
        for (long member : small) {
            if (Arrays.binarySearch(large, member) >= 0) {
                count++;
            }
        }
        return count;
    }

    interface TripleSink {
        void accept(long first, long second, long third, long firstSecond, long secondThird, long thirdFirst);
    }

    void centred(long centre, TripleSink sink) {
        Neighbourhood around = of(centre);
        for (int a = 0; a < around.size(); a++) {
            for (int b = a + 1; b < around.size(); b++) {
                long left = around.edges()[a];
                long right = around.edges()[b];
                long between = overlap(left, right);
                if (between == 0 || centre < Math.min(left, right)) {
                    sink.accept(centre, left, right, around.overlaps()[a], between, around.overlaps()[b]);
                }
            }
        }
    }

    void containing(long edge, TripleSink sink) {
        Neighbourhood around = of(edge);
        for (int a = 0; a < around.size(); a++) {
            long second = around.edges()[a];
            Neighbourhood next = of(second);
            for (int b = a + 1; b < around.size(); b++) {
                long third = around.edges()[b];
                sink.accept(edge, second, third, around.overlaps()[a], next.overlap(third), around.overlaps()[b]);
            }
            for (int c = 0; c < next.size(); c++) {
                long third = next.edges()[c];
                if (third != edge && around.overlap(third) == 0) {
                    sink.accept(edge, second, third, around.overlaps()[a], next.overlaps()[c], 0);
                }
            }
        }
    }

    int classify(long first, long second, long third, long firstSecond, long secondThird, long thirdFirst) {
        return MotifClasses.code(regionSizes(first, second, third, firstSecond, secondThird, thirdFirst));
    }

    long[] regionSizes(long first, long second, long third, long firstSecond, long secondThird, long thirdFirst) {
        long all = firstSecond > 0 && secondThird > 0 && thirdFirst > 0 ? common(first, second, third) : 0;
        return MotifClasses.regionSizes(cardinality(first), cardinality(second), cardinality(third), firstSecond, secondThird, thirdFirst, all);
    }
}
