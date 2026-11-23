package com.elepay.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketMemoryBoundTest {

    @Test
    void rotatingClientKeysCannotGrowMemoryWithoutBound() {
        AtomicLong nanos = new AtomicLong();
        var limiter = new TokenBucketRateLimiter(5, 1, nanos::get).withMaxLocalKeys(1_000);
        for (int i = 0; i < 50_000; i++) {
            limiter.tryAcquire("attacker-" + i);
        }
        assertThat(limiter.localKeyCount()).isLessThanOrEqualTo(1_000);
    }

    @Test
    void refilledBucketsAreEvictedLosslesslyToAdmitNewClients() {
        AtomicLong nanos = new AtomicLong();
        var limiter = new TokenBucketRateLimiter(2, 1, nanos::get).withMaxLocalKeys(10);
        for (int i = 0; i < 10; i++) limiter.tryAcquire("old-" + i);
        assertThat(limiter.tryAcquire("new-client")).isFalse(); // table full of draining buckets: fail closed
        nanos.addAndGet(5_000_000_000L); // all old buckets refill to capacity
        assertThat(limiter.tryAcquire("new-client")).isTrue();
        assertThat(limiter.localKeyCount()).isEqualTo(1);
    }

    @Test
    void existingClientsKeepTheirStateWhenTheTableIsFull() {
        AtomicLong nanos = new AtomicLong();
        var limiter = new TokenBucketRateLimiter(1, 0.001, nanos::get).withMaxLocalKeys(2);
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse(); // a's limit survives pressure from new keys
        limiter.tryAcquire("b");
        limiter.tryAcquire("c");
        assertThat(limiter.tryAcquire("a")).isFalse();
    }
}
