// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

public enum FingerprintMode {

    SET {
        @Override
        long append(long accumulated, long entryHash) {
            return accumulated + entryHash;
        }

        @Override
        long concat(long left, long right, long rightCount) {
            return left + right;
        }
    },

    SEQUENCE {
        @Override
        long append(long accumulated, long entryHash) {
            return accumulated * BASE + entryHash;
        }

        @Override
        long concat(long left, long right, long rightCount) {
            return left * power(rightCount) + right;
        }
    };

    private static final long BASE = 0x9E3779B97F4A7C15L;

    abstract long append(long accumulated, long entryHash);

    abstract long concat(long left, long right, long rightCount);

    private static long power(long exponent) {
        long result = 1;
        long base = BASE;
        for (long e = exponent; e > 0; e >>>= 1) {
            if ((e & 1) != 0) {
                result *= base;
            }
            base *= base;
        }
        return result;
    }
}
