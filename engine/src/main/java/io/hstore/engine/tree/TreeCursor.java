// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

public final class TreeCursor<V> implements Iterator<Entry<V>> {

    private static final int MAX_DEPTH = 48;

    private final Tree<V> tree;
    private final Predicate<Summary> admit;
    private final boolean descending;
    private final int step;
    private final Branch[] path = new Branch[MAX_DEPTH];
    private final int[] slots = new int[MAX_DEPTH];
    private int depth = -1;
    private Leaf leaf;
    private int position;

    TreeCursor(Tree<V> tree, Predicate<Summary> admit, boolean descending) {
        this.tree = tree;
        this.admit = admit;
        this.descending = descending;
        this.step = descending ? -1 : 1;
        rewind();
    }

    public void rewind() {
        depth = -1;
        leaf = null;
        Ref root = tree.root();
        if (!(root instanceof Ref.Empty) && admit.test(root.summary()) && !descendEdge(root)) {
            advanceLeaf();
        }
    }

    public boolean seek(long key) {
        if (leaf != null && seekWithinLeaf(key)) {
            return true;
        }
        depth = -1;
        leaf = null;
        Ref root = tree.root();
        if (root instanceof Ref.Empty || !admit.test(root.summary())) {
            return false;
        }
        Node node = node(root);
        while (node instanceof Branch branch) {
            int slot = descending ? lastChildUpTo(branch, branch.route(key), key) : firstChildFrom(branch, branch.route(key), key);
            if (slot < 0) {
                return advanceLeaf();
            }
            push(branch, slot);
            node = node(branch.child(slot));
        }
        leaf = (Leaf) node;
        int found = leaf.search(key);
        position = found >= 0 ? found : descending ? -found - 2 : -found - 1;
        return inLeaf() || advanceLeaf();
    }

    private boolean seekWithinLeaf(long key) {
        if (leaf.size() == 0) {
            return false;
        }
        boolean inside = descending
                ? key >= leaf.firstKey() && key <= leaf.key(position)
                : key <= leaf.lastKey() && key >= leaf.key(position);
        if (!inside) {
            return false;
        }
        int found = leaf.search(key);
        position = found >= 0 ? found : descending ? -found - 2 : -found - 1;
        return inLeaf();
    }

    @Override
    public boolean hasNext() {
        return leaf != null;
    }

    public long key() {
        if (leaf == null) {
            throw new NoSuchElementException();
        }
        return leaf.key(position);
    }

    public V value() {
        if (leaf == null) {
            throw new NoSuchElementException();
        }
        return tree.schema().cast(leaf.value(position));
    }

    @Override
    public Entry<V> next() {
        Entry<V> entry = new Entry<>(key(), value());
        advance();
        return entry;
    }

    public void advance() {
        position += step;
        if (!inLeaf()) {
            advanceLeaf();
        }
    }

    private boolean inLeaf() {
        return position >= 0 && position < leaf.size();
    }

    private boolean advanceLeaf() {
        leaf = null;
        while (depth >= 0) {
            Branch branch = path[depth];
            int slot = nextAdmitted(branch, slots[depth] + step);
            if (slot < 0) {
                depth--;
                continue;
            }
            slots[depth] = slot;
            if (descendEdge(branch.child(slot))) {
                return true;
            }
        }
        return false;
    }

    private boolean descendEdge(Ref ref) {
        Node node = node(ref);
        while (node instanceof Branch branch) {
            int slot = nextAdmitted(branch, descending ? branch.size() - 1 : 0);
            if (slot < 0) {
                return false;
            }
            push(branch, slot);
            node = node(branch.child(slot));
        }
        Leaf candidate = (Leaf) node;
        if (candidate.size() == 0) {
            return false;
        }
        leaf = candidate;
        position = descending ? candidate.size() - 1 : 0;
        return true;
    }

    private int nextAdmitted(Branch branch, int from) {
        for (int slot = from; slot >= 0 && slot < branch.size(); slot += step) {
            if (admit.test(branch.child(slot).summary())) {
                return slot;
            }
        }
        return -1;
    }

    private int firstChildFrom(Branch branch, int from, long key) {
        for (int slot = from; slot < branch.size(); slot++) {
            Summary summary = branch.child(slot).summary();
            if (summary.max() >= key && admit.test(summary)) {
                return slot;
            }
        }
        return -1;
    }

    private int lastChildUpTo(Branch branch, int from, long key) {
        for (int slot = from; slot >= 0; slot--) {
            Summary summary = branch.child(slot).summary();
            if (summary.min() <= key && admit.test(summary)) {
                return slot;
            }
        }
        return -1;
    }

    private void push(Branch branch, int slot) {
        depth++;
        path[depth] = branch;
        slots[depth] = slot;
    }

    private Node node(Ref ref) {
        return Trees.node(tree.source(), tree.schema(), ref);
    }
}
