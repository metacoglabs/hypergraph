// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

public record TreeSchema<V>(int id, String name, FingerprintMode fingerprint, ValueCodec<V> codec, EntryMeasure<V> measure) {

    public static final int KEY_BYTES = 10;

    public TreeSchema {
        if (id < 1 || id > 255) {
            throw new IllegalArgumentException("schema id must fit in one byte: " + id);
        }
    }

    @SuppressWarnings("unchecked")
    public V cast(Object value) {
        return (V) value;
    }

    int entryBytes(Object value) {
        return codec.maxSize(cast(value));
    }

    int leafOverhead(int entries) {
        return codec.leafOverhead(entries);
    }

    void measureEntry(long key, Object value, Summary.Builder into) {
        measure.measure(key, cast(value), into);
    }

    @Override
    public String toString() {
        return name + "#" + id;
    }
}
