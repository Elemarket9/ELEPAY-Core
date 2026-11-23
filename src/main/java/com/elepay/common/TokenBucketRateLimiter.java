package com.elepay.common;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Thread-safe Token Bucket rate limiter (Algorithm 088 / Domain 14).
 * Supports burst capacity and refill rate.
 * Enhanced with distributed Redis backend and automatic in-memory fallback
 * for resilient multi-instance deployments.
 */
public class TokenBucketRateLimiter {
    private static final String REDIS_LUA_TRY_ACQUIRE = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refillRatePerSec = tonumber(ARGV[2])
            local requested = tonumber(ARGV[3])
            -- Use the Redis server clock so app nodes with skewed clocks cannot over-grant tokens.
            local t = redis.call('TIME')
            local nowMillis = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

            local lastRefill = tonumber(redis.call('HGET', key, 'last') or nowMillis)
            local currentTokens = tonumber(redis.call('HGET', key, 'tokens') or capacity)

            local elapsedSec = math.max(0, (nowMillis - lastRefill) / 1000.0)
            local refilledTokens = math.min(capacity, currentTokens + (elapsedSec * refillRatePerSec))

            if refilledTokens >= requested then
                local newTokens = refilledTokens - requested
                redis.call('HSET', key, 'tokens', tostring(newTokens), 'last', tostring(nowMillis))
                redis.call('EXPIRE', key, math.ceil(capacity / refillRatePerSec * 2 + 60))
                return 1
            else
                redis.call('HSET', key, 'tokens', tostring(refilledTokens), 'last', tostring(nowMillis))
                redis.call('EXPIRE', key, math.ceil(capacity / refillRatePerSec * 2 + 60))
                return 0
            end
            """;

    private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>(REDIS_LUA_TRY_ACQUIRE, Long.class);

    private final double capacity;
    private final double refillTokensPerSecond;
    private final double refillTokensPerNano;
    private final LongSupplier nanoTimeSupplier;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final RedisBreaker redisBreaker = new RedisBreaker(5_000);
    /**
     * Upper bound on in-memory buckets. Without it an attacker rotating source addresses grows the map
     * until the JVM runs out of memory. See {@link #localBucket(String)}.
     */
    private volatile int maxLocalKeys = DEFAULT_MAX_LOCAL_KEYS;
    public static final int DEFAULT_MAX_LOCAL_KEYS = 100_000;

    public TokenBucketRateLimiter(double capacity, double refillTokensPerSecond) {
        this(capacity, refillTokensPerSecond, System::nanoTime, null);
    }

    public TokenBucketRateLimiter(double capacity, double refillTokensPerSecond, LongSupplier nanoTimeSupplier) {
        this(capacity, refillTokensPerSecond, nanoTimeSupplier, null);
    }

    public TokenBucketRateLimiter(double capacity, double refillTokensPerSecond, StringRedisTemplate redisTemplate) {
        this(capacity, refillTokensPerSecond, System::nanoTime, redisTemplate);
    }

    public TokenBucketRateLimiter(double capacity, double refillTokensPerSecond, LongSupplier nanoTimeSupplier, StringRedisTemplate redisTemplate) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacity);
        }
        if (refillTokensPerSecond <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive: " + refillTokensPerSecond);
        }
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.refillTokensPerNano = refillTokensPerSecond / 1_000_000_000.0;
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier);
        this.redisTemplate = redisTemplate;
    }

    public static TokenBucketRateLimiter perMinute(double capacity, double tokensPerMinute) {
        return new TokenBucketRateLimiter(capacity, tokensPerMinute / 60.0);
    }

    public static TokenBucketRateLimiter perMinute(double capacity, double tokensPerMinute, LongSupplier nanoSupplier) {
        return new TokenBucketRateLimiter(capacity, tokensPerMinute / 60.0, nanoSupplier);
    }

    public static TokenBucketRateLimiter perMinute(double capacity, double tokensPerMinute, StringRedisTemplate redisTemplate) {
        return new TokenBucketRateLimiter(capacity, tokensPerMinute / 60.0, redisTemplate);
    }

    /** Production abuse limits must not reset independently on every application node. */
    public boolean tryAcquireShared(String key) {
        if (redisTemplate == null || key == null) return false;
        if (!redisBreaker.allow()) return false; // shared limit unavailable: fail closed, fast
        try {
            Long result = redisTemplate.execute(SCRIPT, Collections.singletonList("elepay:ratelimit:" + key),
                    String.valueOf(capacity), String.valueOf(refillTokensPerSecond), "1",
                    String.valueOf(System.currentTimeMillis()));
            return Long.valueOf(1L).equals(result);
        } catch (RuntimeException unavailable) {
            redisBreaker.failure();
            return false;
        }
    }

    public boolean tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    public boolean tryAcquire(String key, int tokens) {
        if (key == null) throw new IllegalArgumentException("Key cannot be null");
        if (tokens <= 0) throw new IllegalArgumentException("Requested tokens must be positive: " + tokens);

        // 1. Try distributed Redis backend if available
        if (redisTemplate != null && redisBreaker.allow()) {
            try {
                String redisKey = "elepay:ratelimit:" + key;
                long nowMillis = System.currentTimeMillis();
                Long result = redisTemplate.execute(
                        SCRIPT,
                        Collections.singletonList(redisKey),
                        String.valueOf(capacity),
                        String.valueOf(refillTokensPerSecond),
                        String.valueOf(tokens),
                        String.valueOf(nowMillis)
                );
                if (result != null) {
                    return result == 1L;
                }
            } catch (Exception e) {
                redisBreaker.failure(); // Redis unavailable / timeout -> fallback to memory for a cool-down
            }
        }

        // 2. In-memory fallback (bounded)
        Bucket bucket = localBucket(key);
        if (bucket == null) {
            return false; // table saturated by active clients: fail closed rather than exhaust memory
        }
        return bucket.tryAcquire(tokens, capacity, refillTokensPerNano, nanoTimeSupplier.getAsLong());
    }

    /**
     * Returns the bucket for {@code key}, creating it if there is room. When the table is full, buckets that
     * have refilled to capacity are evicted first: a full bucket behaves exactly like a missing one, so this
     * eviction is lossless. If every bucket is still draining (a large distributed flood), new keys are refused
     * and the caller treats the request as rate limited.
     */
    private Bucket localBucket(String key) {
        Bucket existing = buckets.get(key);
        if (existing != null) return existing;
        if (buckets.size() >= maxLocalKeys) {
            long now = nanoTimeSupplier.getAsLong();
            buckets.entrySet().removeIf(e -> e.getValue().isFull(capacity, refillTokensPerNano, now));
            if (buckets.size() >= maxLocalKeys) return null;
        }
        return buckets.computeIfAbsent(key, k -> new Bucket(capacity, nanoTimeSupplier.getAsLong()));
    }

    /** Sets the in-memory key bound (minimum 1). */
    public TokenBucketRateLimiter withMaxLocalKeys(int maxKeys) {
        this.maxLocalKeys = Math.max(1, maxKeys);
        return this;
    }

    public int localKeyCount() {
        return buckets.size();
    }

    public double getAvailableTokens(String key) {
        if (redisTemplate != null) {
            try {
                String redisKey = "elepay:ratelimit:" + key;
                Object rawTokens = redisTemplate.opsForHash().get(redisKey, "tokens");
                Object rawLast = redisTemplate.opsForHash().get(redisKey, "last");
                if (rawTokens != null && rawLast != null) {
                    double currentTokens = Double.parseDouble(rawTokens.toString());
                    long lastMillis = Long.parseLong(rawLast.toString());
                    long elapsed = Math.max(0, System.currentTimeMillis() - lastMillis);
                    return Math.min(capacity, currentTokens + ((elapsed / 1000.0) * refillTokensPerSecond));
                }
            } catch (Exception ignored) {}
        }

        Bucket b = buckets.get(key);
        if (b == null) return capacity;
        return b.getTokens(capacity, refillTokensPerNano, nanoTimeSupplier.getAsLong());
    }

    public void reset(String key) {
        if (key == null) return;
        buckets.remove(key);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete("elepay:ratelimit:" + key);
            } catch (Exception ignored) {}
        }
    }

    public void clear() {
        buckets.clear();
    }

    public double getCapacity() {
        return capacity;
    }

    public double getRefillTokensPerSecond() {
        return refillTokensPerSecond;
    }

    private static class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(double capacity, long nowNanos) {
            this.tokens = capacity;
            this.lastRefillNanos = nowNanos;
        }

        synchronized boolean tryAcquire(int requestedTokens, double maxCapacity, double refillRatePerNano, long nowNanos) {
            refill(maxCapacity, refillRatePerNano, nowNanos);
            if (tokens >= requestedTokens) {
                tokens -= requestedTokens;
                return true;
            }
            return false;
        }

        synchronized boolean isFull(double maxCapacity, double refillRatePerNano, long nowNanos) {
            refill(maxCapacity, refillRatePerNano, nowNanos);
            return tokens >= maxCapacity;
        }

        synchronized double getTokens(double maxCapacity, double refillRatePerNano, long nowNanos) {
            refill(maxCapacity, refillRatePerNano, nowNanos);
            return tokens;
        }

        private void refill(double maxCapacity, double refillRatePerNano, long nowNanos) {
            long elapsed = Math.max(0, nowNanos - lastRefillNanos);
            if (elapsed > 0) {
                tokens = Math.min(maxCapacity, tokens + (elapsed * refillRatePerNano));
                lastRefillNanos = nowNanos;
            }
        }
    }
}
