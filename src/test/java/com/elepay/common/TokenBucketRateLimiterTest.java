package com.elepay.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    @Test
    void burstAndExhaustion() {
        AtomicLong clock = new AtomicLong(0);
        // Capacity: 5 tokens, Refill: 2 tokens/second
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5.0, 2.0, clock::get);

        // Can burst 5 tokens
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("key1", 1), "Should acquire token " + (i + 1));
        }

        // 6th attempt exhausted
        assertFalse(limiter.tryAcquire("key1", 1), "Should be rejected upon exhaustion");
    }

    @Test
    void refillRateOverTime() {
        AtomicLong clock = new AtomicLong(0);
        // Capacity: 10 tokens, Refill: 5 tokens/second (5e-9 tokens/nano)
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10.0, 5.0, clock::get);

        // Exhaust all 10 tokens
        assertTrue(limiter.tryAcquire("key1", 10));
        assertFalse(limiter.tryAcquire("key1", 1));

        // Advance clock by 1 second (1,000,000,000 nanos) -> 5 tokens replenished
        clock.addAndGet(1_000_000_000L);
        assertTrue(limiter.tryAcquire("key1", 5), "5 tokens should have refilled");
        assertFalse(limiter.tryAcquire("key1", 1), "Should be exhausted after taking 5");

        // Advance by 10 seconds -> should cap at max capacity (10 tokens)
        clock.addAndGet(10_000_000_000L);
        assertEquals(10.0, limiter.getAvailableTokens("key1"), 0.001);
        assertTrue(limiter.tryAcquire("key1", 10));
        assertFalse(limiter.tryAcquire("key1", 1));
    }

    @Test
    void perKeyIsolation() {
        AtomicLong clock = new AtomicLong(0);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3.0, 1.0, clock::get);

        // Exhaust key1
        assertTrue(limiter.tryAcquire("key1", 3));
        assertFalse(limiter.tryAcquire("key1", 1));

        // key2 has independent tokens
        assertTrue(limiter.tryAcquire("key2", 3));
        assertFalse(limiter.tryAcquire("key2", 1));
    }

    @Test
    void concurrentThreadSafety() throws InterruptedException {
        int capacity = 50;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, 1.0);

        int threads = 10;
        int attemptsPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger totalAcquired = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < attemptsPerThread; j++) {
                        if (limiter.tryAcquire("concurrent-key", 1)) {
                            totalAcquired.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Exactly capacity tokens (plus negligible elapsed refill) should be granted, never exceeding capacity + 1
        assertTrue(totalAcquired.get() >= capacity, "Must acquire at least initial capacity");
        assertTrue(totalAcquired.get() <= capacity + 5, "Must not over-grant tokens concurrently");
    }
}
