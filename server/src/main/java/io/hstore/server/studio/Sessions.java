package io.hstore.server.studio;

import io.hstore.db.query.Session;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

final class Sessions implements AutoCloseable {

    static final class Entry {
        private final String token;
        private final Session session;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile long lastUsed = System.nanoTime();

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

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final long idleNanos;

    Sessions(Duration idle) {
        this.idleNanos = idle.toNanos();
    }

    Entry open(Session session) {
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
        Optional.ofNullable(entries.remove(token)).ifPresent(entry -> entry.exclusively(session -> {
            session.close();
            return null;
        }));
    }

    int size() {
        return entries.size();
    }

    private void expire() {
        long now = System.nanoTime();
        entries.values().stream()
                .filter(entry -> now - entry.lastUsed > idleNanos && !entry.lock.isLocked())
                .map(Entry::token)
                .toList()
                .forEach(this::close);
    }

    @Override
    public void close() {
        entries.keySet().stream().toList().forEach(this::close);
    }
}
