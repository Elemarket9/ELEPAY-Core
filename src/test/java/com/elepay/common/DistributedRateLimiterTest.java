package com.elepay.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DistributedRateLimiterTest {

    @Test
    @DisplayName("TokenBucketRateLimiter consumes tokens and rejects over-limit requests")
    void burstAndExhaustion() {
        AtomicLong fakeNano = new AtomicLong(1_000_000_000L);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5.0, 1.0, fakeNano::get, null);

        // First 5 tokens acquire successfully
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("user:1")).isTrue();
        }

        // 6th request fails (exhausted)
        assertThat(limiter.tryAcquire("user:1")).isFalse();
        assertThat(limiter.getAvailableTokens("user:1")).isLessThan(1.0);

        // Advance fake time by 2 seconds -> should refill 2 tokens
        fakeNano.addAndGet(2_000_000_000L);
        assertThat(limiter.tryAcquire("user:1")).isTrue();
        assertThat(limiter.tryAcquire("user:1")).isTrue();
        assertThat(limiter.tryAcquire("user:1")).isFalse();
    }

    @Test
    @DisplayName("TokenBucketRateLimiter rejects invalid parameters")
    void invalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, -5));

        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1.0);
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire(null));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("k", 0));
        assertThrows(IllegalArgumentException.class, () -> limiter.tryAcquire("k", -1));
    }

    @Test
    @DisplayName("TokenBucketRateLimiter concurrency test enforces strict token conservation")
    void concurrentAcquisition() throws InterruptedException {
        int capacity = 50;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, 0.000001); // negligible refill

        int threads = 20;
        int attemptsPerThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successfulAcquires = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < attemptsPerThread; j++) {
                        if (limiter.tryAcquire("concurrent-key")) {
                            successfulAcquires.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        // Must never exceed initial burst capacity
        assertThat(successfulAcquires.get()).isEqualTo(capacity);
        assertThat(limiter.tryAcquire("concurrent-key")).isFalse();
    }

    @Test
    @DisplayName("TokenBucketRateLimiter reset clears bucket state")
    void resetClearsBucket() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2.0, 0.1);
        assertThat(limiter.tryAcquire("key1")).isTrue();
        assertThat(limiter.tryAcquire("key1")).isTrue();
        assertThat(limiter.tryAcquire("key1")).isFalse();

        limiter.reset("key1");
        // Fresh capacity available after reset
        assertThat(limiter.tryAcquire("key1")).isTrue();
    }
}
