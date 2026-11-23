// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import io.hstore.engine.HStoreException;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

public final class IoTrace {

    private static final ScopedValue<IoTrace> CURRENT = ScopedValue.newInstance();

    private final long pageBudget;
    private final LongAdder pagesRead = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final AtomicLong visits = new AtomicLong();

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

    public static Optional<IoTrace> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    public static void recordHit() {
        if (CURRENT.isBound()) {
            IoTrace trace = CURRENT.get();
            trace.cacheHits.increment();
            trace.visit();
        }
    }

    public static void recordRead() {
        if (CURRENT.isBound()) {
            IoTrace trace = CURRENT.get();
            trace.pagesRead.increment();
            trace.visit();
        }
    }

    private void visit() {
        if (visits.incrementAndGet() > pageBudget) {
            throw HStoreException.limit("query exceeded its budget of " + pageBudget + " page visits");
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
