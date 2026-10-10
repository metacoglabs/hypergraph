package io.hstore.engine.tree;

import java.util.Arrays;

public final class Branch extends Node {

    private static final int CHILD_HEAP_BYTES = 128;

    private long[] separators;
    private Ref[] children;
    private int size;
    private final int height;
    private long count;

    private Branch(TreeSchema<?> schema, Object owner, long[] separators, Ref[] children, int size, int height,
                   Summary summary) {
        super(schema, owner, summary);
        this.separators = separators;
        this.children = children;
        this.size = size;
        this.height = height;
        this.count = summary == null ? -1 : summary.count();
    }

    static Branch owned(TreeSchema<?> schema, Object owner, long[] separators, Ref[] children, int size, int height) {
        return new Branch(schema, owner, separators, children, size, height, null);
    }

    static Branch decoded(TreeSchema<?> schema, long[] separators, Ref[] children, int height, Summary summary) {
        return new Branch(schema, null, separators, children, children.length, height, summary);
    }

    static Branch of(TreeSchema<?> schema, Object owner, int height, long leftSeparator, Ref left, long rightSeparator, Ref right) {
        long[] separators = new long[8];
        Ref[] children = new Ref[8];
        separators[0] = leftSeparator;
        separators[1] = rightSeparator;
        children[0] = left;
        children[1] = right;
        return owned(schema, owner, separators, children, 2, height);
    }

    int route(long key) {
        int low = 1;
        int high = size - 1;
        int result = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (separators[mid] <= key) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return result;
    }

    Ref child(int index) {
        return children[index];
    }

    long separator(int index) {
        return separators[index];
    }

    Branch copyFor(WriteScope scope) {
        int capacity = size + 4;
        Branch copy = new Branch(schema, scope.token(), Arrays.copyOf(separators, capacity), Arrays.copyOf(children, capacity), size, height, null);
        copy.count = count;
        return copy;
    }

    Branch frozenWith(Ref[] storedChildren) {
        long[] seps = Arrays.copyOf(separators, size);
        seps[0] = storedChildren[0].summary().min();
        return new Branch(schema, null, seps, Arrays.copyOf(storedChildren, size), size, height, summary());
    }

    Ref[] childArray() {
        return Arrays.copyOf(children, size);
    }

    long[] separatorArray() {
        return Arrays.copyOf(separators, size);
    }

    void setChild(int index, Ref child) {
        children[index] = child;
        changed();
    }

    void setSeparator(int index, long separator) {
        separators[index] = separator;
    }

    void insertChild(int index, long separator, Ref child) {
        if (size == children.length) {
            int capacity = size + Math.max(4, size >> 1);
            separators = Arrays.copyOf(separators, capacity);
            children = Arrays.copyOf(children, capacity);
        }
        System.arraycopy(separators, index, separators, index + 1, size - index);
        System.arraycopy(children, index, children, index + 1, size - index);
        separators[index] = separator;
        children[index] = child;
        size++;
        changed();
    }

    void removeChild(int index) {
        System.arraycopy(separators, index + 1, separators, index, size - index - 1);
        System.arraycopy(children, index + 1, children, index, size - index - 1);
        children[--size] = null;
        changed();
    }

    void replacePair(int index, Ref[] replacement, long[] replacementSeparators) {
        removeChild(index + 1);
        removeChild(index);
        for (int i = replacement.length - 1; i >= 0; i--) {
            insertChild(index, replacementSeparators[i], replacement[i]);
        }
    }

    Branch splitOff() {
        int cut = size / 2;
        int rightSize = size - cut;
        long[] rightSeparators = Arrays.copyOfRange(separators, cut, cut + rightSize + 4);
        Ref[] rightChildren = Arrays.copyOfRange(children, cut, cut + rightSize + 4);
        Branch right = owned(schema, owner, rightSeparators, rightChildren, rightSize, height);
        Arrays.fill(children, cut, size, null);
        size = cut;
        changed();
        return right;
    }

    static Branch concat(TreeSchema<?> schema, Object owner, Branch left, long rightSeparator, Branch right) {
        int total = left.size + right.size;
        long[] separators = Arrays.copyOf(left.separators, total + 4);
        Ref[] children = Arrays.copyOf(left.children, total + 4);
        System.arraycopy(right.separators, 0, separators, left.size, right.size);
        System.arraycopy(right.children, 0, children, left.size, right.size);
        separators[left.size] = rightSeparator;
        return owned(schema, owner, separators, children, total, left.height);
    }

    private void changed() {
        count = -1;
        invalidate();
    }

    @Override
    Summary computeSummary() {
        Summary.Builder builder = Summary.builder(schema.fingerprint());
        for (int i = 0; i < size; i++) {
            builder.merge(children[i].summary());
        }
        return builder.build();
    }

    @Override
    public long count() {
        long cached = count;
        if (cached < 0) {
            cached = 0;
            for (int i = 0; i < size; i++) {
                cached += children[i].count();
            }
            count = cached;
        }
        return cached;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    int heapBytes() {
        return NODE_HEAP_BYTES + 2 * ARRAY_HEAP_BYTES + Long.BYTES * separators.length + Integer.BYTES * children.length
                + CHILD_HEAP_BYTES * size;
    }

    @Override
    int size() {
        return size;
    }
}
