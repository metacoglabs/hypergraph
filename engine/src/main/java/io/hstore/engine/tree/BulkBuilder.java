package io.hstore.engine.tree;

import io.hstore.engine.page.ByteCursor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.BinaryOperator;

public final class BulkBuilder<V> {

    private static final int SPILL_BATCH = 32;

    private final TreeSchema<V> schema;
    private final NodeSource source;
    private final WriteScope scope;
    private final Layout layout;
    private final List<Ref> leaves = new ArrayList<>();
    private BinaryOperator<V> merge = (_, latest) -> latest;
    private long[] keys = new long[64];
    private Object[] values = new Object[64];
    private int size;
    private int bytes;
    private int unspilled;
    private boolean any;
    private long lastKey;

    BulkBuilder(TreeSchema<V> schema, NodeSource source, WriteScope scope) {
        this.schema = schema;
        this.source = source;
        this.scope = scope;
        this.layout = source.layout();
    }

    public BulkBuilder<V> mergingWith(BinaryOperator<V> duplicateMerge) {
        this.merge = duplicateMerge;
        return this;
    }

    public BulkBuilder<V> add(long key, V value) {
        if (any && key < lastKey) {
            throw new IllegalArgumentException("bulk keys must ascend: " + key + " after " + lastKey);
        }
        if (any && key == lastKey) {
            int last = size - 1;
            V merged = merge.apply(schema.cast(values[last]), value);
            bytes += schema.entryBytes(merged) - schema.entryBytes(values[last]);
            values[last] = merged;
            return this;
        }
        int entryBytes = schema.entryBytes(value);
        if (size > 0 && bytes + entryBytes + ByteCursor.varLongSize(key - keys[size - 1]) > layout.leafBudget()) {
            sealLeaf();
        }
        entryBytes += size == 0 ? ByteCursor.signedVarLongSize(key) : ByteCursor.varLongSize(key - keys[size - 1]);
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            values = Arrays.copyOf(values, size * 2);
        }
        keys[size] = key;
        values[size] = value;
        size++;
        bytes += entryBytes;
        any = true;
        lastKey = key;
        return this;
    }

    public BulkBuilder<V> addAll(Iterator<Entry<V>> entries) {
        entries.forEachRemaining(entry -> add(entry.key(), entry.value()));
        return this;
    }

    public Tree<V> build() {
        if (size > 0) {
            sealLeaf();
        }
        balanceTail();
        List<Ref> level = leaves;
        int height = 0;
        while (level.size() > 1) {
            level = parents(level, ++height);
        }
        return new Tree<>(schema, source, level.isEmpty() ? Ref.EMPTY : level.getFirst());
    }

    private void sealLeaf() {
        Leaf leaf = Leaf.owned(schema, scope.token(), Arrays.copyOf(keys, size), Arrays.copyOf(values, size), size);
        leaves.add(new Ref.Pending(leaf));
        Arrays.fill(values, 0, size, null);
        size = 0;
        bytes = 0;
        if (++unspilled > SPILL_BATCH && scope.canSpill()) {
            for (int i = leaves.size() - unspilled; i < leaves.size() - 2; i++) {
                leaves.set(i, scope.spill(leaves.get(i), schema));
            }
            unspilled = 2;
        }
    }

    private void balanceTail() {
        int count = leaves.size();
        if (count < 2 || !(leaves.get(count - 1) instanceof Ref.Pending(Leaf last)) || !layout.leafUnderfull(last)
                || !(leaves.get(count - 2) instanceof Ref.Pending(Leaf previous))) {
            return;
        }
        Leaf merged = Leaf.concat(schema, scope.token(), previous, last);
        leaves.removeLast();
        leaves.removeLast();
        if (merged.bytes() <= layout.leafBudget()) {
            leaves.add(new Ref.Pending(merged));
        } else {
            Leaf tail = merged.splitOff(layout.leafBudget());
            leaves.add(new Ref.Pending(merged));
            leaves.add(new Ref.Pending(tail));
        }
    }

    private List<Ref> parents(List<Ref> children, int height) {
        int groups = (children.size() + layout.maxFanout() - 1) / layout.maxFanout();
        List<Ref> parents = new ArrayList<>(groups);
        int start = 0;
        for (int group = 0; group < groups; group++) {
            int end = start + (children.size() - start) / (groups - group);
            int width = end - start;
            long[] separators = new long[width];
            Ref[] slice = new Ref[width];
            for (int i = 0; i < width; i++) {
                slice[i] = children.get(start + i);
                separators[i] = slice[i].summary().min();
            }
            parents.add(new Ref.Pending(Branch.owned(schema, scope.token(), separators, slice, width, height)));
            start = end;
        }
        return parents;
    }
}
