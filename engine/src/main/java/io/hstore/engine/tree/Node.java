// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

public sealed abstract class Node permits Leaf, Branch {

    final TreeSchema<?> schema;
    final Object owner;
    private volatile Summary summary;

    Node(TreeSchema<?> schema, Object owner, Summary summary) {
        this.schema = schema;
        this.owner = owner;
        this.summary = summary;
    }

    public final Summary summary() {
        Summary cached = summary;
        if (cached == null) {
            cached = computeSummary();
            summary = cached;
        }
        return cached;
    }

    public final TreeSchema<?> schema() {
        return schema;
    }

    final void invalidate() {
        summary = null;
    }

    final boolean writableBy(WriteScope scope) {
        return owner != null && owner == scope.token();
    }

    abstract Summary computeSummary();

    public abstract long count();

    public abstract int height();

    abstract int size();
}
