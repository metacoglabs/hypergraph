package io.hstore.engine.tree;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.page.PageHeader;
import io.hstore.engine.page.PageId;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeCodecTest {

    private static final long PAGE = PageId.pack(2, 40, 1);

    private static final TreeSchema<Ref> NESTED = new TreeSchema<>(201, "nested", FingerprintMode.SET, new ValueCodec<>() {
        @Override
        public int maxSize(Ref value) {
            return Ref.encodedSize(value);
        }

        @Override
        public void encode(ByteCursor out, long[] keys, List<Ref> values) {
            values.forEach(value -> Ref.write(out, value));
        }

        @Override
        public void decode(ByteCursor in, long[] keys, Object[] into) {
            for (int i = 0; i < into.length; i++) {
                into[i] = Ref.read(in);
            }
        }

        @Override
        public boolean holdsRefs() {
            return true;
        }

        @Override
        public Ref mapRefs(Ref value, RefMapper mapper) {
            return mapper.map(value, TreeTest.LONGS);
        }

        @Override
        public void forEachRef(Ref value, RefVisitor visitor) {
            visitor.visit(value, TreeTest.LONGS);
        }
    }, (key, _, into) -> into.entry(key, Hashing.mix(key)));

    @Test
    void leavesWithoutNestedTreesAreMarkedPlain() {
        assertTrue(plain(Leaf.owned(TreeTest.LONGS, new Object(), new long[]{1, 2}, new Object[]{10L, 20L}, 2)));
        assertTrue(plain(Leaf.owned(NESTED, new Object(), new long[]{1, 2}, new Object[]{Ref.EMPTY, Ref.EMPTY}, 2)));
    }

    @Test
    void leavesWithANestedTreeAndBranchesAreNotPlain() {
        Ref nested = new Ref.Stored(PageId.pack(5, 0, 1), 2, Summary.EMPTY);
        Leaf leaf = Leaf.owned(NESTED, new Object(), new long[]{1, 2}, new Object[]{Ref.EMPTY, nested}, 2);
        assertFalse(plain(leaf));
        Leaf decoded = (Leaf) NodeCodec.decode(encode(leaf), PAGE, NESTED);
        assertEquals(nested, decoded.value(1));
        Ref child = new Ref.Stored(PageId.pack(5, 4, 1), 2, Summary.EMPTY);
        assertFalse(plain(Branch.owned(TreeTest.LONGS, new Object(), new long[]{1, 9}, new Ref[]{child, child}, 2, 1)));
    }

    private static boolean plain(Node node) {
        return PageHeader.isPlainLeaf(encode(node).asSlice(0, PageHeader.SIZE), PAGE);
    }

    private static MemorySegment encode(Node node) {
        return NodeCodec.encode(node, PAGE, 1, 4096);
    }
}
