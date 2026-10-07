package io.hstore.engine.tree;

import io.hstore.engine.HStoreException;
import io.hstore.engine.page.IoTrace;
import io.hstore.engine.page.PageStore;

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
        return load(pageId, schema, true);
    }

    public NodeSource scanning() {
        return new NodeSource() {
            @Override
            public Node load(long pageId, TreeSchema<?> schema) {
                return PagedNodeSource.this.load(pageId, schema, false);
            }

            @Override
            public Layout layout() {
                return layout;
            }
        };
    }

    private Node load(long pageId, TreeSchema<?> schema, boolean admit) {
        Node cached = cache.get(pageId);
        if (cached != null) {
            if (cached.schema.id() != schema.id()) {
                throw HStoreException.corrupt(pageId, "cached page belongs to " + cached.schema + ", expected " + schema);
            }
            IoTrace.recordHit();
            return cached;
        }
        IoTrace.recordRead();
        Node node = NodeCodec.decode(store.read(pageId), pageId, schema);
        if (admit) {
            cache.put(pageId, node);
        }
        return node;
    }

    public void admit(long pageId, Node frozen) {
        cache.put(pageId, frozen);
    }

    @Override
    public Layout layout() {
        return layout;
    }

    public NodeCache cache() {
        return cache;
    }
}
