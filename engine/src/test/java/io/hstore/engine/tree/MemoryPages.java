package io.hstore.engine.tree;

import io.hstore.engine.page.PageId;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;

final class MemoryPages implements NodeSource {

    private final Layout layout;
    private final Map<Long, MemorySegment> pages = new HashMap<>();
    private int next;
    int loads;

    MemoryPages(int pageSize) {
        this.layout = Layout.of(pageSize);
    }

    <V> Tree<V> persist(Tree<V> tree) {
        Materializer materializer = new Materializer(new Materializer.Sink() {
            @Override
            public long allocate(int length) {
                long pageId = PageId.pack(1, next, 1);
                next += PageId.unitsFor(length);
                return pageId;
            }

            @Override
            public void accept(long pageId, MemorySegment image, Node frozen) {
                MemorySegment copy = MemorySegment.ofArray(new byte[(int) image.byteSize()]);
                copy.copyFrom(image);
                pages.put(pageId, copy);
            }
        }, 1, layout.pageSize());
        return tree.withRoot(materializer.materialize(tree.root(), tree.schema()));
    }

    @Override
    public Node load(long pageId, TreeSchema<?> schema) {
        loads++;
        return NodeCodec.decode(pages.get(pageId), pageId, schema);
    }

    @Override
    public Layout layout() {
        return layout;
    }
}
