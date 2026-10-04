package io.hstore.engine.tree;

final class Trees {

    private Trees() {
    }

    static Node node(NodeSource source, TreeSchema<?> schema, Ref ref) {
        return switch (ref) {
            case Ref.Pending(Node node) -> node;
            case Ref.Stored(long pageId, Summary _) -> source.load(pageId, schema);
            case Ref.Empty _ -> throw new IllegalStateException("empty reference has no node");
        };
    }
}
