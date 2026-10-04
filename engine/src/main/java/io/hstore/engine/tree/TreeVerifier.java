package io.hstore.engine.tree;

import io.hstore.engine.HStoreException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TreeVerifier {

    public record Report(long pages, long entries, long nestedTrees, int height) {
        Report plus(Report other) {
            return new Report(pages + other.pages, entries + other.entries, nestedTrees + other.nestedTrees, Math.max(height, other.height));
        }
    }

    private static final Report NOTHING = new Report(0, 0, 0, 0);

    private final NodeSource source;
    private final Set<Long> verified = new HashSet<>();

    public TreeVerifier(NodeSource source) {
        this.source = source;
    }

    public Report verify(Ref root, TreeSchema<?> schema) {
        if (root instanceof Ref.Empty) {
            return NOTHING;
        }
        Visit visit = visit(root, schema, -1);
        if (!visit.summary().equals(root.summary())) {
            throw fault(root, schema + " root summary differs from its recomputed contents");
        }
        return visit.report();
    }

    private record Visit(Summary summary, Report report) {
    }

    private Visit visit(Ref ref, TreeSchema<?> schema, int expectedHeight) {
        Node node = Trees.node(source, schema, ref);
        if (expectedHeight >= 0 && node.height() != expectedHeight) {
            throw fault(ref, "unbalanced tree in " + schema + ": height " + node.height() + " where " + expectedHeight + " was expected");
        }
        boolean first = !(ref instanceof Ref.Stored(long pageId, int _, Summary _)) || verified.add(pageId);
        Summary.Builder builder = Summary.builder(schema.fingerprint());
        Report report = new Report(first && ref instanceof Ref.Stored ? 1 : 0, 0, 0, node.height() + 1);
        switch (node) {
            case Leaf leaf -> {
                for (int i = 0; i < leaf.size(); i++) {
                    if (i > 0 && leaf.key(i - 1) >= leaf.key(i)) {
                        throw fault(ref, "leaf keys out of order in " + schema);
                    }
                    schema.measureEntry(leaf.key(i), leaf.value(i), builder);
                    if (first) {
                        report = report.plus(nested(schema, leaf.value(i)));
                    }
                }
                report = report.plus(new Report(0, leaf.size(), 0, 0));
            }
            case Branch branch -> {
                Summary previous = null;
                for (int i = 0; i < branch.size(); i++) {
                    Visit child = visit(branch.child(i), schema, branch.height() - 1);
                    if (!child.summary().equals(branch.child(i).summary())) {
                        throw fault(ref, "stale child summary in " + schema);
                    }
                    if (previous != null && (previous.max() >= branch.separator(i) || branch.separator(i) > child.summary().min())) {
                        throw fault(ref, "separator does not route child " + i + " in " + schema);
                    }
                    builder.merge(child.summary());
                    previous = child.summary();
                    report = report.plus(first ? child.report() : NOTHING);
                }
            }
        }
        Summary summary = builder.build();
        if (!summary.equals(node.summary())) {
            throw fault(ref, "page summary differs from its contents in " + schema);
        }
        return new Visit(summary, report);
    }

    private record Nested(Ref root, TreeSchema<?> schema) {
    }

    private <V> Report nested(TreeSchema<V> schema, Object value) {
        if (!schema.codec().holdsRefs()) {
            return NOTHING;
        }
        List<Nested> nested = new ArrayList<>();
        schema.codec().mapRefs(schema.cast(value), (root, nestedSchema) -> {
            nested.add(new Nested(root, nestedSchema));
            return root;
        });
        Report total = NOTHING;
        for (Nested tree : nested) {
            total = total.plus(verify(tree.root(), tree.schema())).plus(new Report(0, 0, tree.root() instanceof Ref.Empty ? 0 : 1, 0));
        }
        return total;
    }

    private static RuntimeException fault(Ref ref, String message) {
        return ref instanceof Ref.Stored(long pageId, int _, Summary _) ? HStoreException.corrupt(pageId, message) : new IllegalStateException(message);
    }
}
