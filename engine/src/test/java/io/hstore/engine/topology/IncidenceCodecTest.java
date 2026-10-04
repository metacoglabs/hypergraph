package io.hstore.engine.topology;

import io.hstore.engine.page.ByteCursor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidenceCodecTest {

    private static Incidence incidence(RandomGenerator random, long member, boolean everything) {
        boolean[] present = new boolean[6];
        for (int i = 0; i < present.length; i++) {
            present[i] = everything || random.nextBoolean();
        }
        return new Incidence(member,
                present[0] ? 1 + random.nextInt(1000) : Incidence.NO_ROLES,
                present[1] ? Weight.of(random.nextDouble()) : Weight.ONE,
                present[2] ? random.nextLong(-1_000_000, 1_000_000) : Long.MIN_VALUE,
                present[3] ? random.nextLong(1_000_000, 2_000_000) : Long.MAX_VALUE,
                present[4] ? 1 + random.nextLong(1L << 40) : 0,
                present[5] ? 1 + random.nextLong(1L << 40) : 0);
    }

    private static int encoded(IncidenceCodec codec, long[] keys, List<Incidence> values) {
        ByteCursor out = ByteCursor.growable(64);
        codec.encode(out, keys, values);
        return Math.toIntExact(out.position());
    }

    private static int accounted(IncidenceCodec codec, List<Incidence> values) {
        return values.stream().mapToInt(codec::maxSize).sum() + codec.leafOverhead(values.size());
    }

    @Test
    void leafAccountingNeverUnderestimatesTheEncoding() {
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(11);
        for (IncidenceCodec codec : List.of(IncidenceCodec.KEYED, IncidenceCodec.SEQUENCED)) {
            for (int size = 1; size <= 300; size++) {
                long[] keys = new long[size];
                List<Incidence> values = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    keys[i] = i * 3L + 1;
                    values.add(incidence(random, keys[i], false));
                }
                assertTrue(encoded(codec, keys, values) <= accounted(codec, values), "leaf of " + size);
            }
        }
    }

    @Test
    void leafAccountingIsExactWhenEveryColumnIsPresent() {
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(12);
        for (int size : new int[]{1, 7, 8, 9, 64, 255}) {
            long[] keys = new long[size];
            List<Incidence> values = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                keys[i] = i + 1;
                values.add(incidence(random, keys[i], true));
            }
            assertEquals(accounted(IncidenceCodec.KEYED, values), encoded(IncidenceCodec.KEYED, keys, values), "leaf of " + size);
        }
    }
}
