// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.security;

import io.hstore.engine.HStoreException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class SignIns {

    private record Window(int attempts, long resetAt) {
    }

    private record Key(String name, int limit) {
    }

    public static final int ACCOUNT_LIMIT = 10;
    public static final int ORIGIN_LIMIT = 20;
    public static final int SPREAD_WARNING = 50;
    public static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int TRACKED_KEYS = 16_384;
    private static final System.Logger LOG = System.getLogger("hstore.security");

    private final int accountLimit;
    private final int originLimit;
    private final long windowNanos;
    private final LongSupplier clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Map<String, Window> spread = new ConcurrentHashMap<>();

    public SignIns() {
        this(ACCOUNT_LIMIT, ORIGIN_LIMIT, WINDOW, System::nanoTime);
    }

    public SignIns(int accountLimit, int originLimit, Duration window, LongSupplier clock) {
        this.accountLimit = accountLimit;
        this.originLimit = originLimit;
        this.windowNanos = window.toNanos();
        this.clock = clock;
    }

    public <T> Optional<T> attempt(String user, Optional<String> origin, Supplier<Optional<T>> check) {
        Key account = new Key(origin.map(address -> "account:" + user + "\n" + address).orElse("account:" + user), accountLimit);
        List<Key> keys = Stream.concat(Stream.of(account), origin.map(address -> new Key("origin:" + address, originLimit)).stream()).toList();
        long now = clock.getAsLong();
        evictExpired(windows, now);
        for (int reserved = 0; reserved < keys.size(); reserved++) {
            long wait = reserve(keys.get(reserved), now);
            if (wait > 0) {
                keys.subList(0, reserved).forEach(this::release);
                throw HStoreException.limit("too many failed sign-in attempts; try again in " + wait + " seconds");
            }
        }
        Optional<T> result = check.get();
        if (result.isPresent()) {
            windows.remove(account.name());
            keys.stream().filter(key -> key != account).forEach(this::release);
        } else {
            failed(user, now);
        }
        return result;
    }

    private void failed(String user, long now) {
        evictExpired(spread, now);
        Window window = spread.compute(user, (_, current) -> current == null || now >= current.resetAt()
                ? new Window(1, now + windowNanos)
                : new Window(current.attempts() + 1, current.resetAt()));
        if (window.attempts() == SPREAD_WARNING) {
            LOG.log(System.Logger.Level.WARNING, "user \"{0}\" failed to sign in {1} times within {2} minutes; someone may be guessing its "
                    + "password from several addresses", user, SPREAD_WARNING, WINDOW.toMinutes());
        }
    }

    private static void evictExpired(Map<String, Window> tracked, long now) {
        if (tracked.size() >= TRACKED_KEYS) {
            tracked.values().removeIf(window -> now >= window.resetAt());
        }
    }

    private long reserve(Key key, long now) {
        long[] wait = {0};
        windows.compute(key.name(), (_, window) -> {
            if (window == null || now >= window.resetAt()) {
                return new Window(1, now + windowNanos);
            }
            if (window.attempts() >= key.limit()) {
                wait[0] = Math.max(1, Duration.ofNanos(window.resetAt() - now).toSeconds());
                return window;
            }
            return new Window(window.attempts() + 1, window.resetAt());
        });
        return wait[0];
    }

    private void release(Key key) {
        windows.computeIfPresent(key.name(), (_, window) -> window.attempts() <= 1 ? null : new Window(window.attempts() - 1, window.resetAt()));
    }
}
