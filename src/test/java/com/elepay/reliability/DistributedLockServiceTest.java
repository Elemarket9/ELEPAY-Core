package com.elepay.reliability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DistributedLockServiceTest {

    private DistributedLockService lockService;

    @BeforeEach
    void setUp() {
        lockService = new DistributedLockService(null, null);
    }

    @Test
    @DisplayName("Acquiring lock succeeds and blocks concurrent acquisition for same key")
    void mutualExclusion() {
        String key = "test-lock-1";
        boolean acquired = lockService.tryLock(key, Duration.ofSeconds(5));
        assertThat(acquired).isTrue();

        // Same thread or other thread trying to lock without releasing
        // In local lock fallback, same key is already held
        // Let's test with another thread
        AtomicBoolean secondAcquired = new AtomicBoolean(false);
        Thread otherThread = new Thread(() -> {
            boolean result = lockService.tryLock(key, Duration.ofSeconds(5), Duration.ofMillis(50));
            secondAcquired.set(result);
        });
        otherThread.start();
        try {
            otherThread.join();
        } catch (InterruptedException ignored) {}

        assertThat(secondAcquired.get()).isFalse();

        // After unlock, other thread can acquire
        lockService.unlock(key);

        AtomicBoolean thirdAcquired = new AtomicBoolean(false);
        Thread thirdThread = new Thread(() -> {
            boolean result = lockService.tryLock(key, Duration.ofSeconds(5), Duration.ofMillis(50));
            thirdAcquired.set(result);
            if (result) lockService.unlock(key);
        });
        thirdThread.start();
        try {
            thirdThread.join();
        } catch (InterruptedException ignored) {}

        assertThat(thirdAcquired.get()).isTrue();
    }

    @Test
    @DisplayName("withLock executes action and releases automatically")
    void withLockExecutesAndReleases() {
        String key = "action-lock";
        AtomicInteger counter = new AtomicInteger(0);

        lockService.withLock(key, Duration.ofSeconds(5), Duration.ofSeconds(1), counter::incrementAndGet);

        assertThat(counter.get()).isEqualTo(1);

        // Immediate subsequent lock succeeds since previous was released
        int result = lockService.withLock(key, Duration.ofSeconds(5), Duration.ofSeconds(1), () -> 42);
        assertThat(result).isEqualTo(42);
    }

    @Test
    @DisplayName("Invalid parameters throw IllegalArgumentException")
    void invalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> lockService.tryLock(null, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> lockService.tryLock("", Duration.ofSeconds(1)));
    }
}
