// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.engine.HStoreException;

import java.util.List;
import java.util.stream.IntStream;

public final class MotifClasses {

    static final int[][] PERMUTATIONS = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
    private static final int[] CANONICAL = IntStream.range(0, 128).map(MotifClasses::smallest).toArray();
    public static final List<Integer> CLASSES = IntStream.range(1, 128)
            .filter(code -> CANONICAL[code] == code && valid(code)).boxed().toList();

    private MotifClasses() {
    }

    public static int canonical(int code) {
        return CANONICAL[code];
    }

    public static int requireClass(int code) {
        if (code < 1 || code > 127 || !valid(code)) {
            throw HStoreException.invalid("motif " + code + " is not a class of three connected hyperedges; the classes are " + CLASSES);
        }
        return code;
    }

    public static boolean open(int code) {
        return !overlaps(code, 0, 1) || !overlaps(code, 1, 2) || !overlaps(code, 0, 2);
    }

    public static boolean duplicated(int code) {
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                boolean same = true;
                for (int region = 1; region < 8; region++) {
                    boolean inI = (region >> i & 1) != 0;
                    boolean inJ = (region >> j & 1) != 0;
                    if (inI != inJ && (code >> (region - 1) & 1) != 0) {
                        same = false;
                    }
                }
                if (same) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String shape(int code) {
        return duplicated(code) ? "duplicate" : open(code) ? "open" : "closed";
    }

    public static long[] regionSizes(long first, long second, long third, long firstSecond, long secondThird, long thirdFirst, long all) {
        long[] size = new long[8];
        size[1] = first - firstSecond - thirdFirst + all;
        size[2] = second - firstSecond - secondThird + all;
        size[4] = third - secondThird - thirdFirst + all;
        size[3] = firstSecond - all;
        size[6] = secondThird - all;
        size[5] = thirdFirst - all;
        size[7] = all;
        return size;
    }

    public static int code(long[] size) {
        int code = 0;
        for (int region = 1; region < 8; region++) {
            if (size[region] > 0) {
                code |= 1 << (region - 1);
            }
        }
        return code;
    }

    public static int classify(long first, long second, long third, long firstSecond, long secondThird, long thirdFirst, long all) {
        return CANONICAL[code(regionSizes(first, second, third, firstSecond, secondThird, thirdFirst, all))];
    }

    static int permute(int code, int[] p) {
        int moved = 0;
        for (int region = 1; region < 8; region++) {
            if ((code >> (region - 1) & 1) != 0) {
                int target = 0;
                for (int i = 0; i < 3; i++) {
                    if ((region >> i & 1) != 0) {
                        target |= 1 << p[i];
                    }
                }
                moved |= 1 << (target - 1);
            }
        }
        return moved;
    }

    private static int smallest(int code) {
        int best = code;
        for (int[] p : PERMUTATIONS) {
            best = Math.min(best, permute(code, p));
        }
        return best;
    }

    private static boolean overlaps(int code, int i, int j) {
        for (int region = 1; region < 8; region++) {
            if ((region >> i & 1) != 0 && (region >> j & 1) != 0 && (code >> (region - 1) & 1) != 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean valid(int code) {
        for (int i = 0; i < 3; i++) {
            boolean nonEmpty = false;
            for (int region = 1; region < 8; region++) {
                if ((region >> i & 1) != 0 && (code >> (region - 1) & 1) != 0) {
                    nonEmpty = true;
                }
            }
            if (!nonEmpty) {
                return false;
            }
        }
        int pairs = (overlaps(code, 0, 1) ? 1 : 0) + (overlaps(code, 1, 2) ? 1 : 0) + (overlaps(code, 0, 2) ? 1 : 0);
        return pairs >= 2;
    }
}
