package com.elepay.reliability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Distributed Lock Service (Domain 15).
 * Coordinates mutual exclusion across cluster nodes using Redis SET NX PX
 * with safe unlock script, backed by PostgreSQL distributed_lock fallback and in-memory locks.
 */
@Service
public class DistributedLockService {
    private static final String REDIS_LOCK_PREFIX = "elepay:lock:";
    private static final String UNLOCK_LUA = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(UNLOCK_LUA, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final JdbcClient db;
    private final ConcurrentHashMap<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<String, String>> threadOwners = ThreadLocal.withInitial(ConcurrentHashMap::new);

    public DistributedLockService(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) JdbcClient db) {
        this.redisTemplate = redisTemplate;
        this.db = db;
    }

    public boolean tryLock(String lockKey, Duration leaseTime) {
        return tryLock(lockKey, leaseTime, Duration.ZERO);
    }

    public boolean tryLock(String lockKey, Duration leaseTime, Duration waitTime) {
        if (lockKey == null || lockKey.isBlank()) {
            throw new IllegalArgumentException("lockKey cannot be null or empty");
        }
        if (leaseTime == null || leaseTime.isZero() || leaseTime.isNegative()
                || waitTime == null || waitTime.isNegative()) {
            throw new IllegalArgumentException("Positive lease and non-negative wait required");
        }
        if (threadOwners.get().containsKey(lockKey)) return false;
        String owner = UUID.randomUUID().toString() + ":" + Thread.currentThread().getId();
        long start = System.currentTimeMillis();
        long waitMillis = waitTime.toMillis();

        do {
            // 1. Authoritative Redis store when configured (never fall back to DB per-request to prevent split-brain)
            if (redisTemplate != null) {
                try {
                    Boolean acquired = redisTemplate.opsForValue()
                            .setIfAbsent(REDIS_LOCK_PREFIX + lockKey, owner, leaseTime);
                    if (Boolean.TRUE.equals(acquired)) {
                        threadOwners.get().put(lockKey, owner);
                        return true;
                    }
                } catch (Exception ignored) {
                    // Fail closed when Redis is configured but unreachable
                }
            } else if (db != null) {
                // 2. Authoritative PostgreSQL distributed_lock store when Redis is not configured
                try {
                    Instant now = Instant.now();
                    Instant expires = now.plus(leaseTime);
                    db.sql("DELETE FROM distributed_lock WHERE lock_key = :k AND expires_at <= now()")
                            .param("k", lockKey).update();

                    int inserted = db.sql("""
                            INSERT INTO distributed_lock (lock_key, lock_owner, acquired_at, expires_at)
                            VALUES (:k, :o, :a, :e)
                            ON CONFLICT (lock_key) DO NOTHING
                            """)
                            .param("k", lockKey)
                            .param("o", owner)
                            .param("a", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                            .param("e", OffsetDateTime.ofInstant(expires, ZoneOffset.UTC))
                            .update();

                    if (inserted > 0) {
                        threadOwners.get().put(lockKey, owner);
                        return true;
                    }
                } catch (Exception ignored) {
                    // Fail closed when DB is configured but unreachable
                }
            } else {
                // 3. Local JVM lock only when neither Redis nor DB is configured
                ReentrantLock lock = localLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
                try {
                    if (lock.tryLock(Math.max(0, Math.min(50, waitMillis)), TimeUnit.MILLISECONDS)) {
                        threadOwners.get().put(lockKey, owner);
                        return true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            if (waitMillis > 0) {
                try {
                    Thread.sleep(Math.min(50, waitMillis));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        } while (System.currentTimeMillis() - start < waitMillis);

        return false;
    }

    public void unlock(String lockKey) {
        if (lockKey == null) return;
        String owner = threadOwners.get().remove(lockKey);
        if (owner == null) return;

        if (redisTemplate != null) {
            try {
                redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(REDIS_LOCK_PREFIX + lockKey), owner);
            } catch (Exception ignored) {}
        } else if (db != null) {
            try {
                db.sql("DELETE FROM distributed_lock WHERE lock_key = :k AND lock_owner = :o")
                        .param("k", lockKey).param("o", owner).update();
            } catch (Exception ignored) {}
        } else {
            ReentrantLock local = localLocks.get(lockKey);
            if (local != null && local.isHeldByCurrentThread()) {
                local.unlock();
            }
        }
    }

    public void withLock(String lockKey, Duration leaseTime, Duration waitTime, Runnable action) {
        withLock(lockKey, leaseTime, waitTime, () -> {
            action.run();
            return null;
        });
    }

    public <T> T withLock(String lockKey, Duration leaseTime, Duration waitTime, Supplier<T> supplier) {
        boolean acquired = tryLock(lockKey, leaseTime, waitTime);
        if (!acquired) {
            throw new IllegalStateException("Failed to acquire distributed lock for key: " + lockKey);
        }
        try {
            return supplier.get();
        } finally {
            unlock(lockKey);
        }
    }
}
