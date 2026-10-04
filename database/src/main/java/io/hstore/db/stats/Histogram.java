package io.hstore.db.stats;

import java.util.Arrays;

public record Histogram(long[] buckets, long total, long maximum, long sum) {

    public static Histogram of(long[] values) {
        long[] buckets = new long[65];
        long maximum = 0;
        long sum = 0;
        for (long value : values) {
            buckets[bucket(value)]++;
            maximum = Math.max(maximum, value);
            sum += value;
        }
        return new Histogram(buckets, values.length, maximum, sum);
    }

    public static int bucket(long value) {
        return value <= 0 ? 0 : 64 - Long.numberOfLeadingZeros(value);
    }

    public double mean() {
        return total == 0 ? 0 : (double) sum / total;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Histogram(long[] otherBuckets, long otherTotal, long otherMaximum, long otherSum)
                && Arrays.equals(buckets, otherBuckets) && total == otherTotal && maximum == otherMaximum && sum == otherSum;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(buckets) * 31 + Long.hashCode(total);
    }

    @Override
    public String toString() {
        return "Histogram[total=" + total + ", max=" + maximum + ", mean=" + Math.round(mean() * 100) / 100.0 + "]";
    }
}
