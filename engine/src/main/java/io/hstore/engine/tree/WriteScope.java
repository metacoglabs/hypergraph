// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

public final class WriteScope {

    @FunctionalInterface
    public interface PageSink {
        Ref persist(Ref ref, TreeSchema<?> schema);
    }

    private final PageSink sink;
    private Object token = new Object();

    public WriteScope() {
        this(null);
    }

    public WriteScope(PageSink sink) {
        this.sink = sink;
    }

    public void freeze() {
        token = new Object();
    }

    public boolean canSpill() {
        return sink != null;
    }

    Object token() {
        return token;
    }

    Ref spill(Ref ref, TreeSchema<?> schema) {
        return sink == null ? ref : sink.persist(ref, schema);
    }
}
