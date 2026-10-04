package io.hstore.engine.tree;

import java.nio.charset.StandardCharsets;

public final class Hashing {

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private Hashing() {
    }

    public static long mix(long x) {
        x ^= x >>> 33;
        x *= 0xFF51AFD7ED558CCDL;
        x ^= x >>> 33;
        x *= 0xC4CEB9FE1A85EC53L;
        x ^= x >>> 33;
        return x;
    }

    public static long combine(long seed, long value) {
        return mix(seed * GOLDEN + value + 0x632BE59BD9B4E019L);
    }

    public static long of(long first, long... rest) {
        long h = mix(first + GOLDEN);
        for (long value : rest) {
            h = combine(h, value);
        }
        return h;
    }

    public static long of(byte[] bytes) {
        long h = mix(bytes.length);
        int i = 0;
        for (; i + 8 <= bytes.length; i += 8) {
            long word = 0;
            for (int b = 7; b >= 0; b--) {
                word = (word << 8) | (bytes[i + b] & 0xFF);
            }
            h = combine(h, word);
        }
        long tail = 0;
        for (int b = bytes.length - 1; b >= i; b--) {
            tail = (tail << 8) | (bytes[b] & 0xFF);
        }
        return combine(h, tail);
    }

    public static long of(String text) {
        return of(text.getBytes(StandardCharsets.UTF_8));
    }
}
