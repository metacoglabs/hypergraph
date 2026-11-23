// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.Reader;
import io.hstore.db.hora.Budget;
import io.hstore.engine.HStoreException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

public final class MotifCensus {

    public record Count(int code, double estimate, double standardError) {
    }

    public record Result(List<Count> counts, boolean exact, long samples, long requested, long population) {
        public Result {
            counts = List.copyOf(counts);
        }
    }

    private final Reader reader;
    private final List<Integer> types;

    private MotifCensus(Reader reader, List<Integer> types) {
        this.reader = reader;
        this.types = List.copyOf(types);
    }

    public static MotifCensus of(Reader reader, OptionalInt type) {
        if (type.isEmpty()) {
            return new MotifCensus(reader, reader.readableEdgeTypes());
        }
        if (!reader.readableEdgeTypes().contains(type.getAsInt())) {
            throw HStoreException.invalid("type " + type.getAsInt() + " is not a readable hyperedge type");
        }
        return new MotifCensus(reader, List.of(type.getAsInt()));
    }

    private Projection projection(Budget.Meter meter) {
        return new Projection(reader, new HashSet<>(types), meter);
    }

    public long population() {
        return types.stream().mapToLong(reader::countOfType).sum();
    }

    public Result exact(Budget.Meter meter) {
        Projection projection = projection(meter);
        long[] counts = new long[128];
        for (int type : types) {
            reader.atomsOfType(type).forEach(centre -> projection.centred(centre, (a, b, c, ab, bc, ca) -> {
                meter.consume(1);
                counts[MotifClasses.canonical(projection.classify(a, b, c, ab, bc, ca))]++;
            }));
        }
        List<Count> result = new ArrayList<>();
        for (int code : MotifClasses.CLASSES) {
            if (counts[code] > 0) {
                result.add(new Count(code, counts[code], 0));
            }
        }
        return new Result(result, true, 0, 0, population());
    }

    public Result sample(long samples, long seed, Budget.Meter meter) {
        if (samples < 1) {
            throw HStoreException.invalid("SAMPLE needs at least one draw");
        }
        long[] sizes = types.stream().mapToLong(reader::countOfType).toArray();
        long population = java.util.Arrays.stream(sizes).sum();
        if (population == 0) {
            return new Result(List.of(), false, 0, samples, 0);
        }
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(seed);
        Projection projection = projection(meter);
        double scale = population / 3.0;
        double[] sum = new double[128];
        double[] squares = new double[128];
        long done = 0;
        long[] counts = new long[128];
        try {
            for (; done < samples; done++) {
                long centre = draw(sizes, random.nextLong(population));
                java.util.Arrays.fill(counts, 0);
                projection.containing(centre, (a, b, c, ab, bc, ca) -> {
                    meter.consume(1);
                    counts[MotifClasses.canonical(projection.classify(a, b, c, ab, bc, ca))]++;
                });
                for (int code : MotifClasses.CLASSES) {
                    double value = counts[code] * scale;
                    sum[code] += value;
                    squares[code] += value * value;
                }
            }
        } catch (HStoreException.ResourceLimit exhausted) {
            if (done == 0) {
                throw exhausted;
            }
        }
        List<Count> result = new ArrayList<>();
        for (int code : MotifClasses.CLASSES) {
            if (sum[code] > 0) {
                double mean = sum[code] / done;
                double variance = done > 1 ? Math.max(0, (squares[code] - done * mean * mean) / (done - 1)) : Double.NaN;
                result.add(new Count(code, mean, Math.sqrt(variance / done)));
            }
        }
        return new Result(result, false, done, samples, population);
    }

    public Result auto(long seed, long rows) {
        long quarter = Math.max(1, rows / 4);
        try {
            return exact(rowsOnly(2 * quarter).start());
        } catch (HStoreException.ResourceLimit tooLarge) {
            long draws;
            try {
                draws = sample(Long.MAX_VALUE, seed ^ PILOT, rowsOnly(quarter / 2).start()).samples();
            } catch (HStoreException.ResourceLimit tooSmall) {
                throw HStoreException.limit("the budget does not cover even one sampled hyperedge; use MOTIFS SAMPLE with a narrower type");
            }
            return sample(draws, seed, rowsOnly(quarter + quarter / 2).start());
        }
    }

    private static final long PILOT = 0x9E3779B97F4A7C15L;

    private static Budget rowsOnly(long rows) {
        return new Budget(rows, Budget.DEFAULT.maxDepth(), SAFETY);
    }

    private static final java.time.Duration SAFETY = Budget.DEFAULT.maxTime().dividedBy(3);

    public List<Count> profile(long edge, Budget.Meter meter) {
        reader.require(edge);
        Projection projection = projection(meter);
        if (!projection.inScope(edge)) {
            throw HStoreException.invalid("atom " + edge + " is not a hyperedge in the scope of this census");
        }
        long[] counts = new long[128];
        projection.containing(edge, (a, b, c, ab, bc, ca) -> {
            meter.consume(1);
            counts[MotifClasses.canonical(projection.classify(a, b, c, ab, bc, ca))]++;
        });
        List<Count> result = new ArrayList<>();
        for (int code : MotifClasses.CLASSES) {
            if (counts[code] > 0) {
                result.add(new Count(code, counts[code], 0));
            }
        }
        return result;
    }

    private long draw(long[] sizes, long rank) {
        long remaining = rank;
        for (int i = 0; i < sizes.length; i++) {
            if (remaining < sizes[i]) {
                return reader.atomOfTypeAt(types.get(i), remaining);
            }
            remaining -= sizes[i];
        }
        throw new IllegalStateException("rank " + rank + " beyond the population");
    }
}
