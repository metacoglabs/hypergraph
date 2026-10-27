// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server.studio;

import io.hstore.db.query.Session;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

final class Sessions implements AutoCloseable {

    static final class Entry {
        private final String token;
        private final Session session;
        private final ReentrantLock lock = new ReentrantLock();
        private final long opened = System.nanoTime();
        private volatile long lastUsed = opened;

        private Entry(String token, Session session) {
            this.token = token;
            this.session = session;
        }

        String token() {
            return token;
        }

        Session session() {
            return session;
        }

        <T> T exclusively(Function<Session, T> work) {
            lock.lock();
            try {
                lastUsed = System.nanoTime();
                return work.apply(session);
            } finally {
                lock.unlock();
            }
        }
    }

    private static final System.Logger LOG = System.getLogger("hstore.studio");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long LIFETIME_NANOS = Duration.ofHours(12).toNanos();
    private static final int MAX_SESSIONS = 4096;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final long idleNanos;
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("studio-session-sweeper").factory());

    Sessions(Duration idle) {
        this.idleNanos = idle.toNanos();
        long period = Math.clamp(idle.toMillis() / 4, 10, Duration.ofMinutes(1).toMillis());
        sweeper.scheduleWithFixedDelay(this::sweep, period, period, TimeUnit.MILLISECONDS);
    }

    Entry open(Session session) {
        expire();
        if (entries.size() >= MAX_SESSIONS) {
            throw HttpFailure.tooManyRequests("too many open studio sessions; try again later");
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        Entry entry = new Entry(Base64.getUrlEncoder().withoutPadding().encodeToString(secret), session);
        entries.put(entry.token, entry);
        return entry;
    }

    Optional<Entry> find(String token) {
        expire();
        Entry entry = entries.get(token);
        if (entry != null) {
            entry.lastUsed = System.nanoTime();
        }
        return Optional.ofNullable(entry);
    }

    void close(String token) {
        Optional.ofNullable(entries.remove(token)).ifPresent(entry -> {
            try {
                entry.exclusively(session -> {
                    session.close();
                    return null;
                });
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "closing a studio session failed", failure);
            }
        });
    }

    int size() {
        return entries.size();
    }

    private void sweep() {
        try {
            expire();
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "closing idle studio sessions failed; the next sweep will try again", failure);
        }
    }

    void expire() {
        long now = System.nanoTime();
        entries.values().stream()
                .filter(entry -> (now - entry.lastUsed > idleNanos || now - entry.opened > LIFETIME_NANOS) && !entry.lock.isLocked())
                .map(Entry::token)
                .toList()
                .forEach(this::close);
    }

    @Override
    public void close() {
        sweeper.shutdown();
        entries.keySet().stream().toList().forEach(this::close);
    }
}
