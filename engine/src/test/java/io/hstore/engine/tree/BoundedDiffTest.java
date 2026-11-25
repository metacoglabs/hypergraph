// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import io.hstore.engine.catalog.RootVector;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.catalog.SlotRegistry;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.txn.ChangeLog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedDiffTest {

    private static final TreeSchema<Long> LONGS = new TreeSchema<>(220, "bounded-diff-test", FingerprintMode.SET,
            ValueCodec.rows(ByteCursor::signedVarLongSize, ByteCursor::putSignedVarLong, ByteCursor::getSignedVarLong),
            (key, value, into) -> into.entry(key, Hashing.of(key, value)));

    private static Tree<Long> built(MemoryPages pages, int entries) {
        BulkBuilder<Long> builder = Tree.empty(LONGS, pages).builder(new WriteScope());
        for (long key = 0; key < entries; key++) {
            builder.add(key * 3, key);
        }
        return builder.build();
    }

    @Test
    void aDiffBetweenTreesThatShareNoPagesGivesUpAfterAFewNodes() {
        MemoryPages pages = new MemoryPages(1024);
        Tree<Long> stored = pages.persist(built(pages, 50_000));
        Tree<Long> rebuilt = built(pages, 50_000);
        pages.loads = 0;
        assertEquals(Optional.empty(), TreeDiff.bounded(stored, rebuilt, 4096));
        int bounded = pages.loads;
        pages.loads = 0;
        assertEquals(0, TreeDiff.diff(stored, rebuilt).count());
        int unbounded = pages.loads;
        assertTrue(bounded < 100 && bounded * 4 < unbounded, "pages read with a bound " + bounded + ", without " + unbounded);
    }

    @Test
    void aBoundedDiffOfAnEditedTreeFindsEveryChange() {
        MemoryPages pages = new MemoryPages(1024);
        Tree<Long> stored = pages.persist(built(pages, 50_000));
        WriteScope scope = new WriteScope();
        Random random = new Random(3);
        Tree<Long> edited = stored;
        for (int i = 0; i < 300; i++) {
            long key = random.nextLong(160_000);
            edited = random.nextInt(3) == 0 ? edited.remove(scope, key) : edited.put(scope, key, -key);
        }
        var expected = TreeDiff.diff(stored, edited).toList();
        assertTrue(expected.size() > 200, String.valueOf(expected.size()));
        assertEquals(Optional.of(expected), TreeDiff.bounded(stored, edited, 4096));
        assertEquals(Optional.empty(), TreeDiff.bounded(stored, edited, 100));
    }

    @Test
    void theChangeLogOfARebuiltTreeRecordsItsRootWithoutReadingTheOldTree() {
        MemoryPages pages = new MemoryPages(1024);
        Slot<Long> slot = Slot.derived(200, "bounded-diff-slot", LONGS);
        SlotRegistry slots = new SlotRegistry(List.of(slot));
        Tree<Long> stored = pages.persist(built(pages, 50_000));
        Tree<Long> rebuilt = built(pages, 50_000);
        RootVector before = new RootVector(Map.of(slot.id(), stored.root()));
        pages.loads = 0;
        byte[] payload = ChangeLog.encode(before, new RootVector(Map.of(slot.id(), rebuilt.root())), slots, pages,
                (pending, schema) -> pages.persist(new Tree<>(LONGS, pages, pending)).root());
        assertTrue(pages.loads < 200, "pages loaded: " + pages.loads);
        RootVector applied = ChangeLog.apply(before, payload, slots, pages, new WriteScope());
        assertTrue(TreeDiff.sameContent(stored, new Tree<>(LONGS, pages, applied.get(slot.id()))));
    }
}
