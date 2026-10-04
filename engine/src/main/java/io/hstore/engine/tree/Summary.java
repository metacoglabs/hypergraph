package io.hstore.engine.tree;

import io.hstore.engine.page.ByteCursor;

public record Summary(
        long count,
        long min,
        long max,
        long weightSum,
        long weightMin,
        long weightMax,
        long timeMin,
        long timeMax,
        long roleBits,
        long fingerprint) {

    public static final Summary EMPTY = new Summary(0, Long.MAX_VALUE, Long.MIN_VALUE, 0,
            Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, 0, 0);

    public static final int MAX_ENCODED_BYTES = 8 * 10 + 16;

    public boolean isEmpty() {
        return count == 0;
    }

    public boolean overlaps(long lowInclusive, long highInclusive) {
        return !isEmpty() && min <= highInclusive && max >= lowInclusive;
    }

    public boolean disjointFrom(Summary other) {
        return isEmpty() || other.isEmpty() || max < other.min || other.max < min;
    }

    public boolean mayBeValidAt(long instant) {
        return !isEmpty() && timeMin <= instant && instant < timeMax;
    }

    public boolean mayOverlapInterval(long from, long to) {
        return !isEmpty() && timeMin < to && from < timeMax;
    }

    public boolean mayHaveWeightIn(long low, long high) {
        return !isEmpty() && weightMax >= low && weightMin <= high;
    }

    public boolean mayHaveRoles(long mask) {
        return (roleBits & mask) != 0;
    }

    public void writeTo(ByteCursor out) {
        out.putVarLong(count);
        if (isEmpty()) {
            return;
        }
        out.putSignedVarLong(min)
                .putVarLong(max - min)
                .putSignedVarLong(weightSum)
                .putSignedVarLong(weightMin)
                .putSignedVarLong(weightMax)
                .putSignedVarLong(timeMin)
                .putSignedVarLong(timeMax)
                .putLong(roleBits)
                .putLong(fingerprint);
    }

    public int encodedSize() {
        if (isEmpty()) {
            return 1;
        }
        return ByteCursor.varLongSize(count) + ByteCursor.signedVarLongSize(min) + ByteCursor.varLongSize(max - min)
                + ByteCursor.signedVarLongSize(weightSum) + ByteCursor.signedVarLongSize(weightMin)
                + ByteCursor.signedVarLongSize(weightMax) + ByteCursor.signedVarLongSize(timeMin)
                + ByteCursor.signedVarLongSize(timeMax) + 16;
    }

    public static Summary readFrom(ByteCursor in) {
        long count = in.getVarLong();
        if (count == 0) {
            return EMPTY;
        }
        long min = in.getSignedVarLong();
        long max = min + in.getVarLong();
        return new Summary(count, min, max, in.getSignedVarLong(), in.getSignedVarLong(), in.getSignedVarLong(),
                in.getSignedVarLong(), in.getSignedVarLong(), in.getLong(), in.getLong());
    }

    public static Builder builder(FingerprintMode mode) {
        return new Builder(mode);
    }

    public static final class Builder {
        private final FingerprintMode mode;
        private long count;
        private long min = Long.MAX_VALUE;
        private long max = Long.MIN_VALUE;
        private long weightSum;
        private long weightMin = Long.MAX_VALUE;
        private long weightMax = Long.MIN_VALUE;
        private long timeMin = Long.MAX_VALUE;
        private long timeMax = Long.MIN_VALUE;
        private long roleBits;
        private long fingerprint;

        private Builder(FingerprintMode mode) {
            this.mode = mode;
        }

        public Builder entry(long key, long hash) {
            count++;
            min = Math.min(min, key);
            max = Math.max(max, key);
            fingerprint = mode.append(fingerprint, hash);
            return this;
        }

        public Builder weight(long weight) {
            weightSum = Math.addExact(weightSum, weight);
            weightMin = Math.min(weightMin, weight);
            weightMax = Math.max(weightMax, weight);
            return this;
        }

        public Builder validity(long from, long to) {
            timeMin = Math.min(timeMin, from);
            timeMax = Math.max(timeMax, to);
            return this;
        }

        public Builder roles(long bits) {
            roleBits |= bits;
            return this;
        }

        public Builder merge(Summary other) {
            if (other.isEmpty()) {
                return this;
            }
            fingerprint = mode.concat(fingerprint, other.fingerprint, other.count);
            count += other.count;
            min = Math.min(min, other.min);
            max = Math.max(max, other.max);
            weightSum = Math.addExact(weightSum, other.weightSum);
            weightMin = Math.min(weightMin, other.weightMin);
            weightMax = Math.max(weightMax, other.weightMax);
            timeMin = Math.min(timeMin, other.timeMin);
            timeMax = Math.max(timeMax, other.timeMax);
            roleBits |= other.roleBits;
            return this;
        }

        public Summary build() {
            return count == 0 ? EMPTY
                    : new Summary(count, min, max, weightSum, weightMin, weightMax, timeMin, timeMax, roleBits, fingerprint);
        }
    }
}
