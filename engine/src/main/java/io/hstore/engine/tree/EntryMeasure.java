// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import java.util.function.ToLongFunction;

@FunctionalInterface
public interface EntryMeasure<V> {

    void measure(long key, V value, Summary.Builder into);

    static <V> EntryMeasure<V> keyed(ToLongFunction<V> valueHash) {
        return (key, value, into) -> into.entry(key, Hashing.combine(Hashing.mix(key), valueHash.applyAsLong(value)));
    }
}
