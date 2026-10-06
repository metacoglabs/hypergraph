// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.page.PageId;

public sealed interface Ref {

    Ref EMPTY = new Empty();

    int MAX_UNITS_BYTES = 3;

    Summary summary();

    long count();

    record Empty() implements Ref {
        @Override
        public Summary summary() {
            return Summary.EMPTY;
        }

        @Override
        public long count() {
            return 0;
        }
    }

    record Stored(long pageId, int units, Summary summary) implements Ref {
        @Override
        public long count() {
            return summary.count();
        }

        @Override
        public String toString() {
            return "Stored[" + PageId.unpack(pageId) + ", n=" + summary.count() + "]";
        }
    }

    record Pending(Node node) implements Ref {
        @Override
        public Summary summary() {
            return node.summary();
        }

        @Override
        public long count() {
            return node.count();
        }
    }

    static boolean same(Ref a, Ref b) {
        return switch (a) {
            case Empty _ -> b instanceof Empty;
            case Stored(long page, _, _) -> b instanceof Stored(long other, _, _) && page == other;
            case Pending(Node node) -> b instanceof Pending(Node other) && node == other;
        };
    }

    static void write(ByteCursor out, Ref ref) {
        switch (ref) {
            case Empty _ -> out.putLong(PageId.NONE);
            case Stored(long pageId, int units, Summary summary) -> {
                out.putLong(pageId);
                out.putVarLong(units);
                summary.writeTo(out);
            }
            case Pending _ -> throw new IllegalStateException("pending reference cannot be encoded");
        }
    }

    static Ref read(ByteCursor in) {
        long pageId = in.getLong();
        return pageId == PageId.NONE ? EMPTY : new Stored(pageId, Math.toIntExact(in.getVarLong()), Summary.readFrom(in));
    }

    static int encodedSize(Ref ref) {
        return switch (ref) {
            case Empty _ -> 8;
            case Stored(long _, int units, Summary summary) -> 8 + ByteCursor.varLongSize(units) + summary.encodedSize();
            case Pending _ -> maxEncodedSize();
        };
    }

    static int maxEncodedSize() {
        return 8 + MAX_UNITS_BYTES + Summary.MAX_ENCODED_BYTES;
    }
}
