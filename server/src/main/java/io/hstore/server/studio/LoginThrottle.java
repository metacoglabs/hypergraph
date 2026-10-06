// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server.studio;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

final class LoginThrottle {

    private record Window(int failures, long resetAt) {
    }

    private static final int TRACKED_ADDRESSES = 4096;

    private final int limit;
    private final long windowNanos;
    private final LongSupplier clock;
    private final Map<String, Window> failures = new ConcurrentHashMap<>();

    LoginThrottle(int limit, Duration window, LongSupplier clock) {
        this.limit = limit;
        this.windowNanos = window.toNanos();
        this.clock = clock;
    }

    long secondsUntilAllowed(String address) {
        Window window = failures.get(address);
        long now = clock.getAsLong();
        if (window == null || window.failures() < limit || now >= window.resetAt()) {
            return 0;
        }
        return Math.max(1, Duration.ofNanos(window.resetAt() - now).toSeconds());
    }

    void failed(String address) {
        long now = clock.getAsLong();
        if (failures.size() >= TRACKED_ADDRESSES) {
            failures.values().removeIf(window -> now >= window.resetAt());
        }
        failures.merge(address, new Window(1, now + windowNanos),
                (old, fresh) -> now >= old.resetAt() ? fresh : new Window(old.failures() + 1, old.resetAt()));
    }

    void succeeded(String address) {
        failures.remove(address);
    }
}
