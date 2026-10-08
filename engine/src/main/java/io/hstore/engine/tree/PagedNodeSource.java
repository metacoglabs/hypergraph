package io.hstore.engine.tree;

import io.hstore.engine.HStoreException;
import io.hstore.engine.page.IoTrace;
import io.hstore.engine.page.PageHeader;
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
            public Node loadUnlessPlainLeaf(long pageId, TreeSchema<?> schema) {
                Node cached = cached(pageId, schema);
                return cached != null || PageHeader.isPlainLeaf(store.readHeader(pageId), pageId) ? cached : decode(pageId, schema, false);
            }

            @Override
            public Layout layout() {
                return layout;
            }
        };
    }

    @Override
    public Node loadUnlessPlainLeaf(long pageId, TreeSchema<?> schema) {
        Node cached = cached(pageId, schema);
        return cached != null || PageHeader.isPlainLeaf(store.readHeader(pageId), pageId) ? cached : decode(pageId, schema, true);
    }

    private Node load(long pageId, TreeSchema<?> schema, boolean admit) {
        Node cached = cached(pageId, schema);
        return cached != null ? cached : decode(pageId, schema, admit);
    }

    private Node cached(long pageId, TreeSchema<?> schema) {
        Node cached = cache.get(pageId);
        if (cached != null) {
            if (cached.schema.id() != schema.id()) {
                throw HStoreException.corrupt(pageId, "cached page belongs to " + cached.schema + ", expected " + schema);
            }
            IoTrace.recordHit();
        }
        return cached;
    }

    private Node decode(long pageId, TreeSchema<?> schema, boolean admit) {
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
