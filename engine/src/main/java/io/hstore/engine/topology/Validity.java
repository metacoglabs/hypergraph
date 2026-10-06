// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.topology;

public record Validity(long from, long to) {

    public static final Validity ALWAYS = new Validity(Long.MIN_VALUE, Long.MAX_VALUE);

    public Validity {
        if (from >= to) {
            throw new IllegalArgumentException("empty validity interval [" + from + ", " + to + ")");
        }
    }

    public static Validity since(long from) {
        return new Validity(from, Long.MAX_VALUE);
    }

    public boolean contains(long instant) {
        return from <= instant && instant < to;
    }

    public boolean overlaps(long otherFrom, long otherTo) {
        return from < otherTo && otherFrom < to;
    }
}
