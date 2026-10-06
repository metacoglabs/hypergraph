// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.txn;

import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.Generation;
import io.hstore.engine.catalog.RootVector;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.tree.NodeSource;
import io.hstore.engine.tree.Tree;

import java.util.concurrent.atomic.AtomicBoolean;

public final class Snapshot extends View implements AutoCloseable {

    private final TransactionManager manager;
    private final Generation generation;
    private final Branch branch;
    private final AtomicBoolean open = new AtomicBoolean(true);

    Snapshot(TransactionManager manager, Generation generation, Branch branch) {
        this.manager = manager;
        this.generation = generation;
        this.branch = branch;
    }

    @Override
    <V> Tree<V> tree(Slot<V> slot) {
        return new Tree<>(slot.schema(), manager.source(), branch.roots().get(slot));
    }

    @Override
    public long generation() {
        return generation.id();
    }

    @Override
    public int branch() {
        return branch.id();
    }

    @Override
    public NodeSource source() {
        return manager.source();
    }

    public Generation catalog() {
        return generation;
    }

    public RootVector roots() {
        return branch.roots();
    }

    public long wallTime() {
        return generation.wallTime();
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            manager.unpin(generation.id());
        }
    }
}
