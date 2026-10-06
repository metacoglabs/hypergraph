// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import io.hstore.engine.HStoreException;

import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

public final class IoTrace {

    private static final ScopedValue<IoTrace> CURRENT = ScopedValue.newInstance();

    private final long pageBudget;
    private final LongAdder pagesRead = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();

    private IoTrace(long pageBudget) {
        this.pageBudget = pageBudget;
    }

    public static IoTrace unbounded() {
        return new IoTrace(Long.MAX_VALUE);
    }

    public static IoTrace withBudget(long pages) {
        return new IoTrace(pages);
    }

    public <T> T call(Supplier<T> work) {
        return ScopedValue.where(CURRENT, this).call(work::get);
    }

    public void run(Runnable work) {
        ScopedValue.where(CURRENT, this).run(work);
    }

    public static void recordHit() {
        if (CURRENT.isBound()) {
            CURRENT.get().cacheHits.increment();
        }
    }

    public static void recordRead() {
        if (CURRENT.isBound()) {
            IoTrace trace = CURRENT.get();
            trace.pagesRead.increment();
            if (trace.pagesRead.sum() + trace.cacheHits.sum() > trace.pageBudget) {
                throw HStoreException.limit("query exceeded its budget of " + trace.pageBudget + " page visits");
            }
        }
    }

    public long pagesRead() {
        return pagesRead.sum();
    }

    public long cacheHits() {
        return cacheHits.sum();
    }

    public long pageVisits() {
        return pagesRead() + cacheHits();
    }
}
