package io.hstore.engine.tree;

import org.junit.jupiter.api.Test;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeCacheTest {

    private static Node node(long key) {
        return Leaf.owned(TreeTest.LONGS, null, new long[]{key}, new Object[]{key}, 1);
    }

    @Test
    void residentBytesStayWithinTheBudget() {
        long budget = 1 << 20;
        NodeCache cache = new NodeCache(budget);
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(3);
        for (long page = 0; page < 20_000; page++) {
            cache.put(page, node(page), 100 + random.nextInt(16 * 1024));
            assertTrue(cache.residentBytes() <= budget, "resident " + cache.residentBytes() + " after page " + page);
            if (page % 7 == 0) {
                cache.get(random.nextLong(page + 1));
            }
        }
        assertTrue(cache.residentBytes() > budget / 2, "cache should stay mostly full, holds " + cache.residentBytes());
    }

    @Test
    void pagesThatKeepBeingReadSurviveColdInserts() {
        NodeCache cache = new NodeCache(1 << 20);
        long[] hot = LongStream.range(0, 32).toArray();
        for (long page : hot) {
            cache.put(page, node(page), 4096);
        }
        for (long cold = 1_000; cold < 50_000; cold++) {
            cache.put(cold, node(cold), 4096);
            for (long page : hot) {
                cache.get(page);
            }
        }
        for (long page : hot) {
            assertTrue(cache.get(page) != null, "hot page " + page + " was evicted");
        }
    }

    @Test
    void aNodeLargerThanItsShardIsNotCached() {
        NodeCache cache = new NodeCache(1 << 20);
        cache.put(7, node(7), 128 * 1024);
        assertNull(cache.get(7));
        assertEquals(0, cache.residentBytes());
    }

    @Test
    void puttingAPageAgainReplacesItAndItsSize() {
        NodeCache cache = new NodeCache(1 << 20);
        Node first = node(1);
        Node second = node(2);
        cache.put(9, first, 1_000);
        cache.put(9, second, 5_000);
        assertSame(second, cache.get(9));
        assertEquals(5_000, cache.residentBytes());
        cache.clear();
        assertEquals(0, cache.residentBytes());
        assertNull(cache.get(9));
    }
}
