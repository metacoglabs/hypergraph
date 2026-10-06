// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import io.hstore.engine.HStoreException;

import java.util.Optional;
import java.util.function.UnaryOperator;

final class TreeWriter<V> {

    private sealed interface Outcome {
        Outcome SAME = new Same();

        record Same() implements Outcome {
        }

        record One(Ref ref) implements Outcome {
        }

        record Two(Ref left, long separator, Ref right) implements Outcome {
        }
    }

    private final TreeSchema<V> schema;
    private final NodeSource source;
    private final WriteScope scope;
    private final Layout layout;

    TreeWriter(TreeSchema<V> schema, NodeSource source, WriteScope scope) {
        this.schema = schema;
        this.source = source;
        this.scope = scope;
        this.layout = source.layout();
    }

    Ref update(Ref root, long key, UnaryOperator<Optional<V>> change) {
        if (root instanceof Ref.Empty) {
            return change.apply(Optional.empty())
                    .map(value -> singleton(key, value))
                    .orElse(Ref.EMPTY);
        }
        return switch (descend(root, key, change)) {
            case Outcome.Same _ -> root;
            case Outcome.One(Ref ref) -> collapse(ref);
            case Outcome.Two(Ref left, long separator, Ref right) -> grow(left, separator, right);
        };
    }

    Ref join(Ref left, Ref right) {
        if (left instanceof Ref.Empty) {
            return right;
        }
        if (right instanceof Ref.Empty) {
            return left;
        }
        int leftHeight = node(left).height();
        int rightHeight = node(right).height();
        if (leftHeight == rightHeight) {
            return joinLevel(left, right);
        }
        Outcome outcome = leftHeight > rightHeight
                ? attach(left, right, rightHeight, right.summary().min(), true)
                : attach(right, left, leftHeight, right.summary().min(), false);
        return switch (outcome) {
            case Outcome.Same _ -> throw new IllegalStateException("join produced no change");
            case Outcome.One(Ref ref) -> ref;
            case Outcome.Two(Ref l, long separator, Ref r) -> grow(l, separator, r);
        };
    }

    private Ref joinLevel(Ref left, Ref right) {
        Node a = node(left);
        Node b = node(right);
        long separator = right.summary().min();
        Outcome merged = switch (a) {
            case Leaf la when b instanceof Leaf lb -> combineLeaves(la, lb);
            case Branch ba when b instanceof Branch bb -> combineBranches(ba, separator, bb);
            default -> throw new IllegalStateException("join of mismatched node kinds");
        };
        return switch (merged) {
            case Outcome.One(Ref ref) -> ref;
            case Outcome.Two(Ref l, long s, Ref r) -> grow(l, s, r);
            case Outcome.Same _ -> throw new IllegalStateException();
        };
    }

    private Outcome attach(Ref host, Ref subtree, int subtreeHeight, long boundary, boolean append) {
        Branch branch = writable((Branch) node(host));
        if (branch.height() == subtreeHeight + 1) {
            if (append) {
                branch.insertChild(branch.size(), boundary, subtree);
            } else {
                branch.setSeparator(0, branch.child(0).summary().min());
                branch.insertChild(0, Long.MIN_VALUE, subtree);
            }
        } else {
            int index = append ? branch.size() - 1 : 0;
            Outcome child = attach(branch.child(index), subtree, subtreeHeight, boundary, append);
            apply(branch, index, child);
        }
        return finish(branch);
    }

    private Outcome descend(Ref ref, long key, UnaryOperator<Optional<V>> change) {
        return switch (node(ref)) {
            case Leaf leaf -> updateLeaf(leaf, key, change);
            case Branch branch -> updateBranch(branch, key, change);
        };
    }

    private Outcome updateLeaf(Leaf leaf, long key, UnaryOperator<Optional<V>> change) {
        int position = leaf.search(key);
        Optional<V> current = position >= 0 ? Optional.of(schema.cast(leaf.value(position))) : Optional.empty();
        Optional<V> next = change.apply(current);
        if (next.equals(current)) {
            return Outcome.SAME;
        }
        Leaf target = writable(leaf);
        if (next.isEmpty()) {
            target.remove(position);
        } else {
            V value = checked(next.get());
            if (position >= 0) {
                target.replace(position, value);
            } else {
                target.insert(-position - 1, key, value);
            }
        }
        if (target.size() == 0) {
            return new Outcome.One(Ref.EMPTY);
        }
        if (target.bytes() > layout.leafBudget()) {
            Leaf right = target.splitOff(layout.leafBudget());
            return new Outcome.Two(pending(target), right.firstKey(), pending(right));
        }
        return new Outcome.One(pending(target));
    }

    private Outcome updateBranch(Branch branch, long key, UnaryOperator<Optional<V>> change) {
        int index = branch.route(key);
        Outcome child = descend(branch.child(index), key, change);
        if (child instanceof Outcome.Same) {
            return Outcome.SAME;
        }
        Branch target = writable(branch);
        apply(target, index, child);
        return finish(target);
    }

    private void apply(Branch target, int index, Outcome child) {
        switch (child) {
            case Outcome.One(Ref ref) when ref instanceof Ref.Empty -> target.removeChild(index);
            case Outcome.One(Ref ref) -> {
                target.setChild(index, ref);
                repair(target, index);
            }
            case Outcome.Two(Ref left, long separator, Ref right) -> {
                target.setChild(index, left);
                target.insertChild(index + 1, separator, right);
            }
            case Outcome.Same _ -> {
            }
        }
    }

    private Outcome finish(Branch target) {
        if (target.size() == 0) {
            return new Outcome.One(Ref.EMPTY);
        }
        if (target.size() > layout.maxFanout()) {
            Branch right = target.splitOff();
            return new Outcome.Two(pending(target), right.separator(0), pending(right));
        }
        return new Outcome.One(pending(target));
    }

    private void repair(Branch parent, int index) {
        if (parent.size() < 2) {
            return;
        }
        boolean underfull = switch (node(parent.child(index))) {
            case Leaf leaf -> layout.leafUnderfull(leaf);
            case Branch branch -> layout.branchUnderfull(branch);
        };
        if (!underfull) {
            return;
        }
        int left = index > 0 ? index - 1 : index;
        long leftSeparator = parent.separator(left);
        long rightSeparator = parent.separator(left + 1);
        Node a = node(parent.child(left));
        Node b = node(parent.child(left + 1));
        Outcome combined = switch (a) {
            case Leaf la when b instanceof Leaf lb -> combineLeaves(la, lb);
            case Branch ba when b instanceof Branch bb -> combineBranches(ba, rightSeparator, bb);
            default -> throw new IllegalStateException("siblings at different heights");
        };
        switch (combined) {
            case Outcome.One(Ref ref) -> parent.replacePair(left, new Ref[]{ref}, new long[]{leftSeparator});
            case Outcome.Two(Ref l, long separator, Ref r) ->
                    parent.replacePair(left, new Ref[]{l, r}, new long[]{leftSeparator, separator});
            case Outcome.Same _ -> throw new IllegalStateException();
        }
    }

    private Outcome combineLeaves(Leaf left, Leaf right) {
        Leaf merged = Leaf.concat(schema, scope.token(), left, right);
        if (merged.bytes() <= layout.leafBudget()) {
            return new Outcome.One(pending(merged));
        }
        Leaf tail = merged.splitOff(layout.leafBudget());
        return new Outcome.Two(pending(merged), tail.firstKey(), pending(tail));
    }

    private Outcome combineBranches(Branch left, long separator, Branch right) {
        Branch merged = Branch.concat(schema, scope.token(), left, separator, right);
        if (merged.size() <= layout.maxFanout()) {
            return new Outcome.One(pending(merged));
        }
        Branch tail = merged.splitOff();
        return new Outcome.Two(pending(merged), tail.separator(0), pending(tail));
    }

    private Ref grow(Ref left, long separator, Ref right) {
        return pending(Branch.of(schema, scope.token(), node(left).height() + 1, Long.MIN_VALUE, left, separator, right));
    }

    private Ref collapse(Ref ref) {
        Ref current = ref;
        while (!(current instanceof Ref.Empty) && node(current) instanceof Branch branch && branch.size() == 1) {
            current = branch.child(0);
        }
        return current;
    }

    private Ref singleton(long key, V value) {
        long[] keys = new long[8];
        Object[] values = new Object[8];
        keys[0] = key;
        values[0] = checked(value);
        return pending(Leaf.owned(schema, scope.token(), keys, values, 1));
    }

    private V checked(V value) {
        int bytes = schema.codec().maxSize(value);
        if (bytes > layout.maxValueBytes()) {
            throw HStoreException.limit("value of " + bytes + " bytes exceeds the " + layout.maxValueBytes() + " byte inline limit of " + schema);
        }
        return value;
    }

    private Leaf writable(Leaf leaf) {
        return leaf.writableBy(scope) ? leaf : leaf.copyFor(scope);
    }

    private Branch writable(Branch branch) {
        return branch.writableBy(scope) ? branch : branch.copyFor(scope);
    }

    private Node node(Ref ref) {
        return Trees.node(source, schema, ref);
    }

    private static Ref pending(Node node) {
        return new Ref.Pending(node);
    }
}
