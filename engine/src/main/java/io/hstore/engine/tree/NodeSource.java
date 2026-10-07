package io.hstore.engine.tree;

public interface NodeSource {

    Node load(long pageId, TreeSchema<?> schema);

    Layout layout();

    default Node loadUnlessPlainLeaf(long pageId, TreeSchema<?> schema) {
        return load(pageId, schema);
    }

    static NodeSource ephemeral(int pageSize) {
        Layout layout = Layout.of(pageSize);
        return new NodeSource() {
            @Override
            public Node load(long pageId, TreeSchema<?> schema) {
                throw new IllegalStateException("ephemeral trees have no stored pages");
            }

            @Override
            public Layout layout() {
                return layout;
            }
        };
    }
}
