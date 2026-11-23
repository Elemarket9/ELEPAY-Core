package com.elepay.enterprise.connectors;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token-bucket rate limiter for enterprise connector requests.
 */
public class RateLimiter {

    private final String name;
    private final long capacity;
    private final long refillTokensPerSecond;
    private final Clock clock;

    private final AtomicLong availableTokens;
    private volatile long lastRefillTimestamp;

    public RateLimiter(String name, long requestsPerMinute, Clock clock) {
        this.name = name;
        this.capacity = Math.max(1L, requestsPerMinute);
        this.refillTokensPerSecond = Math.max(1L, requestsPerMinute / 60);
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.availableTokens = new AtomicLong(this.capacity);
        this.lastRefillTimestamp = Instant.now(this.clock).toEpochMilli();
    }

    public RateLimiter(String name, long requestsPerMinute) {
        this(name, requestsPerMinute, Clock.systemUTC());
    }

    public String getName() {
        return name;
    }

    public synchronized boolean tryAcquire() {
        refill();
        long current = availableTokens.get();
        if (current > 0) {
            availableTokens.decrementAndGet();
            return true;
        }
        return false;
    }

    public long getAvailableTokens() {
        refill();
        return availableTokens.get();
    }

    private synchronized void refill() {
        long now = Instant.now(clock).toEpochMilli();
        long elapsedSeconds = (now - lastRefillTimestamp) / 1000L;
        if (elapsedSeconds > 0) {
            long tokensToAdd = elapsedSeconds * refillTokensPerSecond;
            long updated = Math.min(capacity, availableTokens.get() + tokensToAdd);
            availableTokens.set(updated);
            lastRefillTimestamp = now;
        }
    }
}
