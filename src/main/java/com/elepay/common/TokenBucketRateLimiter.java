package com.elepay.common;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Thread-safe in-memory Token Bucket rate limiter (Algorithm 088).
 *
 * <p>Supports configurable burst capacity and refill rate (per second or per minute).
 */
public class TokenBucketRateLimiter {
    private final double capacity;
    private final double refillTokensPerNano;
    private final LongSupplier nanoTimeSupplier;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(double capacity, double refillTokensPerSecond) {
        this(capacity, refillTokensPerSecond, System::nanoTime);
    }

    public TokenBucketRateLimiter(double capacity, double refillTokensPerSecond, LongSupplier nanoTimeSupplier) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacity);
        }
        if (refillTokensPerSecond <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive: " + refillTokensPerSecond);
        }
        this.capacity = capacity;
        this.refillTokensPerNano = refillTokensPerSecond / 1_000_000_000.0;
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier);
    }

    public static TokenBucketRateLimiter perMinute(double capacity, double tokensPerMinute) {
        return new TokenBucketRateLimiter(capacity, tokensPerMinute / 60.0);
    }

    public static TokenBucketRateLimiter perMinute(double capacity, double tokensPerMinute, LongSupplier nanoSupplier) {
        return new TokenBucketRateLimiter(capacity, tokensPerMinute / 60.0, nanoSupplier);
    }

    public boolean tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    public boolean tryAcquire(String key, int tokens) {
        if (key == null) throw new IllegalArgumentException("Key cannot be null");
        if (tokens <= 0) throw new IllegalArgumentException("Requested tokens must be positive: " + tokens);
        return buckets.computeIfAbsent(key, k -> new Bucket(capacity, nanoTimeSupplier.getAsLong()))
                .tryAcquire(tokens, capacity, refillTokensPerNano, nanoTimeSupplier.getAsLong());
    }

    public double getAvailableTokens(String key) {
        Bucket b = buckets.get(key);
        if (b == null) return capacity;
        return b.getTokens(capacity, refillTokensPerNano, nanoTimeSupplier.getAsLong());
    }

    public void reset(String key) {
        buckets.remove(key);
    }

    public void clear() {
        buckets.clear();
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
