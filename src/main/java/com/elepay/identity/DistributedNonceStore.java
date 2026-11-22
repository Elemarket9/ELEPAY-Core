package com.elepay.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Distributed Nonce Store for replay protection (Algorithm 077).
 * Coordinates nonce deduplication across cluster instances using Redis
 * with automatic fallback to PostgreSQL and local concurrency memory.
 */
@Component
public class DistributedNonceStore {
    private final ConcurrentHashMap<String, Instant> memoryNonces = new ConcurrentHashMap<>();
    private final JdbcClient db;
    private final StringRedisTemplate redisTemplate;

    public DistributedNonceStore() {
        this(null, null);
    }

    @Autowired
    public DistributedNonceStore(JdbcClient db, @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.db = db;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Attempts to record a nonce.
     * @return true if the nonce was newly recorded (not seen before), false if it was already used (replay attack)
     */
    public boolean recordNonceIfAbsent(String nonce, Duration ttl) {
        if (nonce == null || nonce.isBlank()) return false;
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);

        // PostgreSQL is authoritative across all instances, including during Redis outages.
        // A database failure must propagate rather than accept a replay on a different node.
        if (db != null) {
            int inserted = db.sql("""
                    INSERT INTO request_nonce (nonce, created_at, expires_at) VALUES (:n, :c, :e)
                    ON CONFLICT (nonce) DO UPDATE SET created_at = EXCLUDED.created_at,
                    expires_at = EXCLUDED.expires_at WHERE request_nonce.expires_at <= :c
                    """).param("n", nonce).param("c", now).param("e", expiresAt).update();
            return inserted == 1;
        }
        // Explicit unit-test instances only; the application constructor requires JdbcClient.
        if (memoryNonces.size() > 5000) memoryNonces.entrySet().removeIf(e -> !e.getValue().isAfter(now));
        java.util.concurrent.atomic.AtomicBoolean accepted = new java.util.concurrent.atomic.AtomicBoolean(false);
        memoryNonces.compute(nonce, (key, previous) -> {
            if (previous == null || !previous.isAfter(now)) {
                accepted.set(true);
                return expiresAt;
            }
            return previous;
        });
        return accepted.get();
    }

    public void clear() {
        memoryNonces.clear();
    }
}
