// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.topology;

public final class Weight {

    public static final long SCALE = 1_000_000_000L;
    public static final long ONE = SCALE;

    private Weight() {
    }

    public static long of(double value) {
        if (!Double.isFinite(value) || Math.abs(value) > Long.MAX_VALUE / (double) SCALE) {
            throw new IllegalArgumentException("weight out of fixed-point range: " + value);
        }
        return Math.round(value * SCALE);
    }

    public static double toDouble(long fixed) {
        return (double) fixed / SCALE;
    }
}
