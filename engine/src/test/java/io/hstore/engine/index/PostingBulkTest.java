// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.index;

import io.hstore.engine.EngineOptions;
import io.hstore.engine.StorageEngine;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.NodeSource;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeDiff;
import io.hstore.engine.tree.ValueCodec;
import io.hstore.engine.tree.WriteScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostingBulkTest {

    private static final PostingIndex<Long> INDEX = PostingIndex.of(211, 212, "bulk-test",
            ValueCodec.rows(ByteCursor::signedVarLongSize, ByteCursor::putSignedVarLong, ByteCursor::getSignedVarLong), value -> value, 16);

    @TempDir
    Path directory;

    @Test
    void bulkBuiltPostingsMatchPostingsAddedOneAtATime() {
        try (StorageEngine engine = StorageEngine.open(directory, EngineOptions.defaults().withPageSize(1024))) {
            NodeSource source = engine.transactions().source();
            WriteScope scope = new WriteScope();
            Random random = new Random(11);
            TreeMap<Long, TreeMap<Long, Long>> expected = new TreeMap<>();
            Map<Integer, Long> keyOfSize = new TreeMap<>();
            int[] sizes = {1, 2, 5, 15, 16, 17, 40, 300};
            for (int group = 0; group < sizes.length; group++) {
                long key = random.nextLong();
                keyOfSize.put(sizes[group], key);
                TreeMap<Long, Long> postings = expected.computeIfAbsent(key, _ -> new TreeMap<>());
                while (postings.size() < sizes[group]) {
                    postings.put(random.nextLong(), group == 3 ? random.nextLong() : (long) random.nextInt(4));
                }
            }
            List<long[]> shuffled = new ArrayList<>();
            expected.forEach((key, postings) -> postings.forEach((posting, value) -> shuffled.add(new long[] {key, posting, value})));
            Collections.shuffle(shuffled, random);
            Tree<Postings<Long>> added = Tree.empty(INDEX.schema(), source);
            for (long[] entry : shuffled) {
                added = INDEX.add(added, scope, entry[0], entry[1], entry[2]);
            }
            PostingIndex<Long>.Bulk bulk = INDEX.bulk(source, scope);
            expected.forEach((key, postings) -> postings.forEach((posting, value) -> bulk.add(key, posting, value)));
            Tree<Postings<Long>> built = bulk.build();
            assertTrue(TreeDiff.sameContent(added, built), TreeDiff.difference(added, built).orElse(""));
            assertEquals(expected.size(), built.size());
            assertInstanceOf(Postings.Inline.class, built.get(keyOfSize.get(1)).orElseThrow());
            assertInstanceOf(Postings.Promoted.class, built.get(keyOfSize.get(15)).orElseThrow());
            assertInstanceOf(Postings.Promoted.class, built.get(keyOfSize.get(300)).orElseThrow());
            assertEquals(0, INDEX.bulk(source, scope).build().size());
        }
    }

    @Test
    void bulkPostingsMustArriveInAscendingOrder() {
        try (StorageEngine engine = StorageEngine.open(directory, EngineOptions.defaults().withPageSize(1024))) {
            NodeSource source = engine.transactions().source();
            assertThrows(IllegalArgumentException.class, () -> INDEX.bulk(source, new WriteScope()).add(5, 2, 1L).add(5, 1, 1L));
            assertThrows(IllegalArgumentException.class, () -> INDEX.bulk(source, new WriteScope()).add(5, 2, 1L).add(5, 2, 1L));
            assertThrows(IllegalArgumentException.class, () -> INDEX.bulk(source, new WriteScope()).add(5, 1, 1L).add(4, 9, 1L));
        }
    }
}
