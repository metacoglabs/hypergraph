package io.hstore.engine.tree;

import io.hstore.engine.HStoreException;
import io.hstore.engine.page.IoTrace;
import io.hstore.engine.page.PageHeader;
import io.hstore.engine.page.PageStore;

import java.lang.foreign.MemorySegment;

public final class PagedNodeSource implements NodeSource {

    private final PageStore store;
    private final NodeCache cache;
    private final Layout layout;

    public PagedNodeSource(PageStore store, NodeCache cache) {
        this.store = store;
        this.cache = cache;
        this.layout = Layout.of(store.pageSize());
    }

    @Override
    public Node load(long pageId, TreeSchema<?> schema) {
        Node cached = cache.get(pageId);
        if (cached != null) {
            if (cached.schema.id() != schema.id()) {
                throw HStoreException.corrupt(pageId, "cached page belongs to " + cached.schema + ", expected " + schema);
            }
            IoTrace.recordHit();
            return cached;
        }
        IoTrace.recordRead();
        MemorySegment page = store.read(pageId);
        Node node = NodeCodec.decode(page, pageId, schema);
        cache.put(pageId, node, PageHeader.SIZE + PageHeader.payloadLength(page));
        return node;
    }

    public void admit(long pageId, Node frozen, int bytes) {
        cache.put(pageId, frozen, bytes);
    }

    @Override
    public Layout layout() {
        return layout;
    }

    public NodeCache cache() {
        return cache;
    }
}
