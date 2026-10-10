package io.hstore.engine.tree;

import java.util.function.LongPredicate;

public final class TreeWalker {

    private static final int UNKNOWN_HEIGHT = -1;

    private final NodeSource source;

    public TreeWalker(NodeSource source) {
        this.source = source;
    }

    public void visit(Ref root, TreeSchema<?> schema, LongPredicate firstVisit) {
        visit(root, schema, UNKNOWN_HEIGHT, firstVisit);
    }

    private void visit(Ref ref, TreeSchema<?> schema, int height, LongPredicate firstVisit) {
        if (!(ref instanceof Ref.Stored(long pageId, int _, Summary _)) || !firstVisit.test(pageId)) {
            return;
        }
        if (height == 0 && !schema.codec().holdsRefs()) {
            return;
        }
        switch (source.load(pageId, schema)) {
            case Leaf leaf -> {
                for (int i = 0; i < leaf.size(); i++) {
                    visitNested(schema, leaf.value(i), firstVisit);
                }
            }
            case Branch branch -> {
                for (int i = 0; i < branch.size(); i++) {
                    visit(branch.child(i), schema, branch.height() - 1, firstVisit);
                }
            }
        }
    }

    private <V> void visitNested(TreeSchema<V> schema, Object value, LongPredicate firstVisit) {
        if (schema.codec().holdsRefs()) {
            schema.codec().mapRefs(schema.cast(value), (nested, nestedSchema) -> {
                visit(nested, nestedSchema, UNKNOWN_HEIGHT, firstVisit);
                return nested;
            });
        }
    }
}
