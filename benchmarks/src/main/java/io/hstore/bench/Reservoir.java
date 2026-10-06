// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.bench;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.stream.LongStream;

final class Reservoir {

    private final long[] sample;
    private final SplittableRandom random;
    private long seen;
    private long max;

    Reservoir(int capacity, long seed) {
        this.sample = new long[capacity];
        this.random = new SplittableRandom(seed);
    }

    void add(long nanos) {
        max = Math.max(max, nanos);
        if (seen < sample.length) {
            sample[(int) seen] = nanos;
        } else {
            long slot = random.nextLong(seen + 1);
            if (slot < sample.length) {
                sample[(int) slot] = nanos;
            }
        }
        seen++;
    }

    long seen() {
        return seen;
    }

    static Latency merge(Reservoir... reservoirs) {
        return Latency.of(Arrays.stream(reservoirs)
                .flatMapToLong(reservoir -> LongStream.concat(
                        Arrays.stream(reservoir.sample, 0, (int) Math.min(reservoir.seen, reservoir.sample.length)),
                        LongStream.of(reservoir.max)))
                .toArray());
    }
}
