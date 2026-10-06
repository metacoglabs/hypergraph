// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import io.hstore.engine.HStoreException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

public record Budget(long maxRows, int maxDepth, Duration maxTime) {

    public static final Budget DEFAULT = new Budget(1_000_000, 8, Duration.ofSeconds(30));
    public static final Budget UNBOUNDED = new Budget(Long.MAX_VALUE, Integer.MAX_VALUE, Duration.ofDays(365));

    public Budget withRows(long rows) {
        return new Budget(rows, maxDepth, maxTime);
    }

    public Budget withDepth(int depth) {
        return new Budget(maxRows, depth, maxTime);
    }

    public Meter start() {
        return new Meter(this);
    }

    public static final class Meter {
        private final Budget budget;
        private final long deadline;
        private final AtomicLong rows = new AtomicLong();

        private Meter(Budget budget) {
            this.budget = budget;
            long nanos = budget.maxTime().toNanos();
            this.deadline = System.nanoTime() + (nanos <= 0 ? Long.MAX_VALUE / 2 : Math.min(nanos, Long.MAX_VALUE / 2));
        }

        public Budget budget() {
            return budget;
        }

        public boolean tryConsume(long count) {
            return rows.addAndGet(count) <= budget.maxRows() && System.nanoTime() < deadline;
        }

        public void consume(long count) {
            if (!tryConsume(count)) {
                throw HStoreException.limit("operator exceeded its budget of " + budget.maxRows() + " rows or " + budget.maxTime());
            }
        }

        public long consumed() {
            return rows.get();
        }
    }
}
