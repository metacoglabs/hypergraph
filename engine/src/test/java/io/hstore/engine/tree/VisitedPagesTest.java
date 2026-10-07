package io.hstore.engine.tree;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VisitedPagesTest {

    @Test
    void behavesLikeAMapThatKeepsTheFirstValue() {
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(5);
        VisitedPages pages = new VisitedPages();
        Map<Long, Integer> oracle = new HashMap<>();
        for (int i = 0; i < 200_000; i++) {
            long pageId = 1 + random.nextLong(50_000);
            int units = random.nextInt(256);
            assertEquals(oracle.putIfAbsent(pageId, units) == null, pages.add(pageId, units));
        }
        assertEquals(oracle.size(), pages.size());
        oracle.keySet().forEach(pageId -> assertEquals(true, pages.contains(pageId)));
        assertEquals(false, pages.contains(60_000));
        Map<Long, Integer> seen = new HashMap<>();
        pages.forEach(seen::put);
        assertEquals(oracle, seen);
    }

    @Test
    void pageIdZeroIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VisitedPages().add(0, 1));
    }
}
