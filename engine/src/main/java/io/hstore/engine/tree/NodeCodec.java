package io.hstore.engine.tree;

import io.hstore.engine.HStoreException;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.page.PageHeader;
import io.hstore.engine.page.PageType;

import java.lang.foreign.MemorySegment;
import java.util.List;

public final class NodeCodec {

    private NodeCodec() {
    }

    public static MemorySegment encode(Node node, long pageId, long epoch, int pageSize) {
        ByteCursor out = ByteCursor.fixed(pageSize);
        out.position(PageHeader.SIZE);
        Summary summary = node.summary();
        out.putSignedVarLong(summary.weightSum())
                .putSignedVarLong(summary.weightMin())
                .putSignedVarLong(summary.weightMax())
                .putSignedVarLong(summary.timeMin())
                .putSignedVarLong(summary.timeMax())
                .putLong(summary.roleBits());
        try {
            switch (node) {
                case Leaf leaf -> encodeLeaf(out, leaf);
                case Branch branch -> encodeBranch(out, branch);
            }
        } catch (IndexOutOfBoundsException overflow) {
            throw new IllegalStateException("node of " + node.size() + " entries overflows a " + pageSize + " byte page", overflow);
        }
        int payloadLength = Math.toIntExact(out.position() - PageHeader.SIZE);
        PageType type = node instanceof Leaf ? PageType.LEAF : PageType.INTERNAL;
        int flags = node instanceof Leaf leaf && !holdsNestedRefs(leaf) ? PageHeader.NO_NESTED_REFS : 0;
        new PageHeader(type, node.schema.id(), flags, pageId, epoch, payloadLength, node.size(), summary.count(),
                summary.min(), summary.max(), node.height(), summary.fingerprint()).writeTo(out);
        MemorySegment image = out.segment().asSlice(0, PageHeader.SIZE + payloadLength);
        PageHeader.seal(image, payloadLength);
        return image;
    }

    private static boolean holdsNestedRefs(Leaf leaf) {
        if (!leaf.schema.codec().holdsRefs()) {
            return false;
        }
        boolean[] found = {false};
        for (int i = 0; i < leaf.size() && !found[0]; i++) {
            visitRefs(leaf.schema, leaf.value(i), (ref, _) -> found[0] |= !(ref instanceof Ref.Empty));
        }
        return found[0];
    }

    private static <V> void visitRefs(TreeSchema<V> schema, Object value, ValueCodec.RefVisitor visitor) {
        schema.codec().forEachRef(schema.cast(value), visitor);
    }

    private static void encodeLeaf(ByteCursor out, Leaf leaf) {
        long[] keys = leaf.keyArray();
        out.putSignedVarLong(keys[0]);
        for (int i = 1; i < keys.length; i++) {
            out.putVarLong(keys[i] - keys[i - 1]);
        }
        encodeValues(out, leaf.schema, keys, leaf);
    }

    private static <V> void encodeValues(ByteCursor out, TreeSchema<V> schema, long[] keys, Leaf leaf) {
        @SuppressWarnings("unchecked")
        List<V> values = (List<V>) leaf.valueList();
        schema.codec().encode(out, keys, values);
    }

    private static void encodeBranch(ByteCursor out, Branch branch) {
        long[] separators = branch.separatorArray();
        Ref[] children = branch.childArray();
        out.putSignedVarLong(separators[0]);
        for (int i = 1; i < separators.length; i++) {
            out.putVarLong(separators[i] - separators[i - 1]);
        }
        for (Ref child : children) {
            Ref.write(out, child);
        }
    }

    public static Node decode(MemorySegment page, long pageId, TreeSchema<?> schema) {
        PageHeader header = PageHeader.verify(page, pageId);
        if (header.schema() != schema.id()) {
            throw HStoreException.corrupt(pageId, "page belongs to schema " + header.schema() + ", expected " + schema);
        }
        ByteCursor in = ByteCursor.over(page).position(PageHeader.SIZE);
        Summary summary = new Summary(header.logicalCount(), header.lowerBound(), header.upperBound(),
                in.getSignedVarLong(), in.getSignedVarLong(), in.getSignedVarLong(),
                in.getSignedVarLong(), in.getSignedVarLong(), in.getLong(), header.fingerprint());
        int count = header.slotCount();
        long[] keys = new long[count];
        keys[0] = in.getSignedVarLong();
        for (int i = 1; i < count; i++) {
            keys[i] = keys[i - 1] + in.getVarLong();
        }
        return switch (header.type()) {
            case LEAF -> {
                Object[] values = new Object[count];
                schema.codec().decode(in, keys, values);
                yield Leaf.decoded(schema, keys, values, summary);
            }
            case INTERNAL -> {
                Ref[] children = new Ref[count];
                for (int i = 0; i < count; i++) {
                    children[i] = Ref.read(in);
                }
                yield Branch.decoded(schema, keys, children, header.height(), summary);
            }
        };
    }
}
