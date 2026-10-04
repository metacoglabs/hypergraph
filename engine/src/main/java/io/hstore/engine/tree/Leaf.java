package io.hstore.engine.tree;

import io.hstore.engine.page.ByteCursor;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;

public final class Leaf extends Node {

    private long[] keys;
    private Object[] values;
    private int size;
    private int keyBytes;
    private int valueBytes;

    private Leaf(TreeSchema<?> schema, Object owner, long[] keys, Object[] values, int size, Summary summary) {
        super(schema, owner, summary);
        this.keys = keys;
        this.values = values;
        this.size = size;
        remeasure();
    }

    static Leaf owned(TreeSchema<?> schema, Object owner, long[] keys, Object[] values, int size) {
        return new Leaf(schema, owner, keys, values, size, null);
    }

    static Leaf decoded(TreeSchema<?> schema, long[] keys, Object[] values, Summary summary) {
        return new Leaf(schema, null, keys, values, keys.length, summary);
    }

    static int keyStreamBytes(long[] keys, int size) {
        if (size == 0) {
            return 0;
        }
        int total = ByteCursor.signedVarLongSize(keys[0]);
        for (int i = 1; i < size; i++) {
            total += ByteCursor.varLongSize(keys[i] - keys[i - 1]);
        }
        return total;
    }

    private static int gap(long low, long high) {
        return ByteCursor.varLongSize(high - low);
    }

    private void remeasure() {
        keyBytes = keyStreamBytes(keys, size);
        valueBytes = 0;
        for (int i = 0; i < size; i++) {
            valueBytes += schema.entryBytes(values[i]);
        }
    }

    int search(long key) {
        return Arrays.binarySearch(keys, 0, size, key);
    }

    long key(int index) {
        return keys[index];
    }

    Object value(int index) {
        return values[index];
    }

    int bytes() {
        return keyBytes + valueBytes + schema.leafOverhead(size);
    }

    long firstKey() {
        return keys[0];
    }

    long lastKey() {
        return keys[size - 1];
    }

    List<Object> valueList() {
        return new AbstractList<>() {
            @Override
            public Object get(int index) {
                return values[index];
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    long[] keyArray() {
        return size == keys.length ? keys : Arrays.copyOf(keys, size);
    }

    Leaf copyFor(WriteScope scope) {
        int capacity = size + Math.max(4, size >> 3);
        return new Leaf(schema, scope.token(), Arrays.copyOf(keys, capacity), Arrays.copyOf(values, capacity), size, null);
    }

    Leaf frozenWith(Object[] mappedValues) {
        return new Leaf(schema, null, keyArray(), Arrays.copyOf(mappedValues, size), size, summary());
    }

    void insert(int position, long key, Object value) {
        if (size == keys.length) {
            int capacity = Math.max(8, size + (size >> 1));
            keys = Arrays.copyOf(keys, capacity);
            values = Arrays.copyOf(values, capacity);
        }
        keyBytes += insertionDelta(position, key);
        System.arraycopy(keys, position, keys, position + 1, size - position);
        System.arraycopy(values, position, values, position + 1, size - position);
        keys[position] = key;
        values[position] = value;
        size++;
        valueBytes += schema.entryBytes(value);
        invalidate();
    }

    private int insertionDelta(int position, long key) {
        if (size == 0) {
            return ByteCursor.signedVarLongSize(key);
        }
        if (position == 0) {
            return ByteCursor.signedVarLongSize(key) - ByteCursor.signedVarLongSize(keys[0]) + gap(key, keys[0]);
        }
        if (position == size) {
            return gap(keys[size - 1], key);
        }
        return gap(keys[position - 1], key) + gap(key, keys[position]) - gap(keys[position - 1], keys[position]);
    }

    void replace(int position, Object value) {
        valueBytes += schema.entryBytes(value) - schema.entryBytes(values[position]);
        values[position] = value;
        invalidate();
    }

    void remove(int position) {
        long key = keys[position];
        valueBytes -= schema.entryBytes(values[position]);
        System.arraycopy(keys, position + 1, keys, position, size - position - 1);
        System.arraycopy(values, position + 1, values, position, size - position - 1);
        values[--size] = null;
        keyBytes -= insertionDelta(position, key);
        invalidate();
    }

    Leaf splitOff(int budget) {
        int total = bytes();
        int cut = 1;
        int running = cost(0);
        while (cut < size - 1 && running < total / 2) {
            running += cost(cut++);
        }
        while (cut < size - 1 && total - running > budget) {
            running += cost(cut++);
        }
        int rightSize = size - cut;
        long[] rightKeys = Arrays.copyOfRange(keys, cut, cut + rightSize + 4);
        Object[] rightValues = Arrays.copyOfRange(values, cut, cut + rightSize + 4);
        Leaf right = owned(schema, owner, rightKeys, rightValues, rightSize);
        Arrays.fill(values, cut, size, null);
        size = cut;
        remeasure();
        invalidate();
        return right;
    }

    private int cost(int index) {
        int key = index == 0 ? ByteCursor.signedVarLongSize(keys[0]) : gap(keys[index - 1], keys[index]);
        return key + schema.entryBytes(values[index]);
    }

    static Leaf concat(TreeSchema<?> schema, Object owner, Leaf left, Leaf right) {
        int total = left.size + right.size;
        long[] keys = Arrays.copyOf(left.keys, total + 4);
        Object[] values = Arrays.copyOf(left.values, total + 4);
        System.arraycopy(right.keys, 0, keys, left.size, right.size);
        System.arraycopy(right.values, 0, values, left.size, right.size);
        return owned(schema, owner, keys, values, total);
    }

    @Override
    Summary computeSummary() {
        Summary.Builder builder = Summary.builder(schema.fingerprint());
        for (int i = 0; i < size; i++) {
            schema.measureEntry(keys[i], values[i], builder);
        }
        return builder.build();
    }

    @Override
    public long count() {
        return size;
    }

    @Override
    public int height() {
        return 0;
    }

    @Override
    int size() {
        return size;
    }
}
