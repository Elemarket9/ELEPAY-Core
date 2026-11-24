package com.elepay.reliability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.elepay.common.CallbackAuthenticity;
import com.elepay.common.HmacVerification;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.simple.JdbcClient;

class DistributedLockServiceTest {

    private DistributedLockService lockService;

    @BeforeEach
    void setUp() {
        lockService = new DistributedLockService(null, null);
        CallbackAuthenticity.configureRedis(null);
        CallbackAuthenticity.resetForTesting();
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
    void rejectsReentrantAcquisitionAndAllowsOwnerRenewal() {
        assertThat(lockService.tryLock("owner", Duration.ofSeconds(2))).isTrue();
        assertThat(lockService.tryLock("owner", Duration.ofSeconds(2))).isFalse();
        assertThat(lockService.renew("owner", Duration.ofSeconds(2))).isTrue();
        lockService.unlock("owner");
        assertThat(lockService.renew("owner", Duration.ofSeconds(2))).isFalse();
    }

    @Test
    @DisplayName("Invalid parameters throw IllegalArgumentException")
    void invalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> lockService.tryLock(null, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> lockService.tryLock("", Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("Redis failure fails closed and does NOT fall back to PostgreSQL (prevents split-brain)")
    @SuppressWarnings("unchecked")
    void redisFailureDoesNotFallbackToDb() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis connection timeout"));

        JdbcClient db = mock(JdbcClient.class);
        DistributedLockService service = new DistributedLockService(redis, db);

        boolean acquired = service.tryLock("payout:123", Duration.ofSeconds(5));
        assertThat(acquired).isFalse();
        verifyNoInteractions(db);
    }

    @Test
    @DisplayName("CallbackAuthenticity uses Redis to reject cross-instance nonce replay")
    @SuppressWarnings("unchecked")
    void callbackAuthenticityRejectsReplayViaRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        // First instance sees fresh=true, second instance sees fresh=false
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true, false);

        CallbackAuthenticity.configureRedis(redis);
        try {
            String secret = "cb-secret";
            String body = "{\"ok\":true}";
            String ts = String.valueOf(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String sig = HmacVerification.computeHex(secret, ts + "." + nonce + "." + body);
            Map<String, String> headers = Map.of(
                    "x-callback-timestamp", ts,
                    "x-callback-nonce", nonce,
                    "x-sig", sig
            );

            CallbackAuthenticity.verify(secret, headers, body, "x-sig");
            // Even with in-JVM SEEN cleared, Redis rejects the replay
            CallbackAuthenticity.resetForTesting();
            assertThrows(IllegalArgumentException.class,
                    () -> CallbackAuthenticity.verify(secret, headers, body, "x-sig"));
        } finally {
            CallbackAuthenticity.configureRedis(null);
        }
    }
}
