package io.hstore.engine.tree;

import io.hstore.engine.page.PageHeader;
import io.hstore.engine.page.PageId;

import java.lang.foreign.MemorySegment;

public final class Materializer {

    public interface Sink {
        long allocate(int length);

        void accept(long pageId, MemorySegment image, Node frozen);
    }

    private final Sink sink;
    private final long epoch;
    private final int pageSize;
    private long pages;

    public Materializer(Sink sink, long epoch, int pageSize) {
        this.sink = sink;
        this.epoch = epoch;
        this.pageSize = pageSize;
    }

    public Ref materialize(Ref ref, TreeSchema<?> schema) {
        if (!(ref instanceof Ref.Pending(Node node))) {
            return ref;
        }
        Node frozen = switch (node) {
            case Leaf leaf -> freeze(leaf);
            case Branch branch -> freeze(branch);
        };
        MemorySegment image = NodeCodec.encode(frozen, PageId.NONE, epoch, pageSize);
        long pageId = sink.allocate(Math.toIntExact(image.byteSize()));
        PageHeader.assign(image, pageId);
        sink.accept(pageId, image, frozen);
        pages++;
        return new Ref.Stored(pageId, frozen.summary());
    }

    public long pagesWritten() {
        return pages;
    }

    public static long pendingNodes(Ref ref, TreeSchema<?> schema) {
        if (!(ref instanceof Ref.Pending(Node node))) {
            return 0;
        }
        long[] count = {1};
        switch (node) {
            case Leaf leaf -> {
                if (leaf.schema.codec().holdsRefs()) {
                    leaf.valueList().forEach(value -> countNested(leaf.schema, value, count));
                }
            }
            case Branch branch -> {
                for (Ref child : branch.childArray()) {
                    count[0] += pendingNodes(child, branch.schema);
                }
            }
        }
        return count[0];
    }

    private static <V> void countNested(TreeSchema<V> schema, Object value, long[] count) {
        schema.codec().mapRefs(schema.cast(value), (nested, nestedSchema) -> {
            count[0] += pendingNodes(nested, nestedSchema);
            return nested;
        });
    }

    private Leaf freeze(Leaf leaf) {
        Object[] values = leaf.valueList().toArray();
        if (leaf.schema.codec().holdsRefs()) {
            for (int i = 0; i < values.length; i++) {
                values[i] = mapRefs(leaf.schema, values[i]);
            }
        }
        return leaf.frozenWith(values);
    }

    private <V> Object mapRefs(TreeSchema<V> schema, Object value) {
        return schema.codec().mapRefs(schema.cast(value), this::materialize);
    }

    private Branch freeze(Branch branch) {
        Ref[] children = branch.childArray();
        for (int i = 0; i < children.length; i++) {
            children[i] = materialize(children[i], branch.schema);
        }
        return branch.frozenWith(children);
    }
}
