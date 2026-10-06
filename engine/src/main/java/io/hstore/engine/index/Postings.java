// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.index;

import io.hstore.engine.tree.Ref;

import java.util.Arrays;
import java.util.List;

public sealed interface Postings<P> {

    long size();

    record Inline<P>(long[] keys, List<P> values, long fingerprint, int bytes) implements Postings<P> {

        public Inline {
            if (keys.length != values.size()) {
                throw new IllegalArgumentException("posting keys and values differ in length");
            }
            values = List.copyOf(values);
        }

        @Override
        public long size() {
            return keys.length;
        }

        int search(long key) {
            return Arrays.binarySearch(keys, key);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Inline<?>(long[] otherKeys, List<?> otherValues, long _, int _)
                    && Arrays.equals(keys, otherKeys) && values.equals(otherValues);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(keys) + values.hashCode();
        }

        @Override
        public String toString() {
            return "Inline" + Arrays.toString(keys);
        }
    }

    record Promoted<P>(Ref root) implements Postings<P> {
        @Override
        public long size() {
            return root.count();
        }
    }
}
