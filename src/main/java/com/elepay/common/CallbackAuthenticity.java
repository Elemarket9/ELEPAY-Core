package com.elepay.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Rail and gateway callbacks must sign {@code timestamp.nonce.body} and are accepted only inside
 * a five-minute window. A nonce is remembered so the same signed request cannot be replayed.
 */
public final class CallbackAuthenticity {
    public static final long WINDOW_SECONDS = 300L;
    private static final int MAX_LOCAL_NONCES = 100_000;
    private static final String REDIS_NONCE_PREFIX = "elepay:callback:nonce:";
    private static final ConcurrentHashMap<String, Long> SEEN = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> ALREADY_VERIFIED_ASYNC = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static volatile StringRedisTemplate redis;
    private static volatile boolean failClosedOnRedisError;

    private CallbackAuthenticity() {}

    @Component
    static class RedisBridge {
        RedisBridge(
                @Autowired(required = false) StringRedisTemplate redisTemplate,
                @Autowired(required = false) Environment env,
                @Value("${elepay.rate-limits.shared-required:false}") boolean sharedRequired) {
            CallbackAuthenticity.redis = redisTemplate;
            boolean isProd = env != null && env.acceptsProfiles(Profiles.of("prod", "production"))
                    && !env.acceptsProfiles(Profiles.of("test", "dev", "sandbox"));
            CallbackAuthenticity.failClosedOnRedisError = sharedRequired || isProd;
        }
    }

    /** Visible to tests to wire a Redis template without a Spring context. */
    public static void configureRedis(StringRedisTemplate redisTemplate) {
        CallbackAuthenticity.redis = redisTemplate;
    }

    /** Visible to tests to toggle fail-closed Redis replay protection. */
    public static void configureFailClosed(boolean failClosed) {
        CallbackAuthenticity.failClosedOnRedisError = failClosed;
    }

    /**
     * Runs an async ingestion handler for a callback that already passed HTTP-ingress
     * {@link #verify} (signature + timestamp + nonce consumption). Still re-verifies
     * the cryptographic HMAC signature, but skips duplicate nonce consumption and
     * queue-delay timestamp expiry.
     */
    public static <T> T runForAlreadyVerifiedAsyncCallback(Supplier<T> action) {
        Boolean previous = ALREADY_VERIFIED_ASYNC.get();
        ALREADY_VERIFIED_ASYNC.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            if (Boolean.TRUE.equals(previous)) {
                ALREADY_VERIFIED_ASYNC.set(Boolean.TRUE);
            } else {
                ALREADY_VERIFIED_ASYNC.remove();
            }
        }
    }

    public static void verify(String secret, Map<String, String> headers, String body, String... signatureHeaderNames) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Callback secret is not configured");
        }
        String timestamp = header(headers, "x-callback-timestamp");
        String nonce = header(headers, "x-callback-nonce");
        if (timestamp == null || timestamp.isBlank() || nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("Callback timestamp and nonce are required");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Callback timestamp is not a unix time");
        }
        boolean asyncAlreadyVerified = Boolean.TRUE.equals(ALREADY_VERIFIED_ASYNC.get());
        long now = Instant.now().getEpochSecond();
        if (!asyncAlreadyVerified && Math.abs(now - ts) > WINDOW_SECONDS) {
            throw new IllegalArgumentException("Callback timestamp is outside the allowed window");
        }
        if (nonce.length() > 128 || nonce.indexOf('.') >= 0) {
            throw new IllegalArgumentException("Callback nonce is invalid");
        }
        String signature = null;
        for (String name : signatureHeaderNames) {
            signature = header(headers, name);
            if (signature != null && !signature.isBlank()) break;
        }
        if (signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("Callback signature is missing");
        }
        String payload = timestamp.trim() + "." + nonce + "." + body;
        String expected = HmacVerification.computeHex(secret, payload);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Callback signature is invalid");
        }
        if (asyncAlreadyVerified) {
            return;
        }
        String key = nonce + ":" + timestamp.trim();
        StringRedisTemplate redisClient = redis;
        if (redisClient != null) {
            try {
                Boolean fresh = redisClient.opsForValue()
                        .setIfAbsent(REDIS_NONCE_PREFIX + key, "1", Duration.ofSeconds(WINDOW_SECONDS * 2 + 1));
                if (Boolean.FALSE.equals(fresh)) {
                    throw new IllegalArgumentException("Callback nonce was already used");
                }
                if (Boolean.TRUE.equals(fresh)) {
                    return;
                }
                if (failClosedOnRedisError) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "replay_store_unavailable",
                            "Callback replay protection store is unavailable");
                }
            } catch (IllegalArgumentException | ApiException explicit) {
                throw explicit;
            } catch (RuntimeException e) {
                if (failClosedOnRedisError) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "replay_store_unavailable",
                            "Callback replay protection store is unavailable");
                }
                // Non-prod fallback to bounded in-JVM replay cache
            }
        } else if (failClosedOnRedisError) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "replay_store_unavailable",
                    "Callback replay protection store is unavailable");
        }
        SEEN.entrySet().removeIf(e -> now - e.getValue() > WINDOW_SECONDS * 2 + 1);
        if (SEEN.size() >= MAX_LOCAL_NONCES) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "replay_store_full",
                    "Callback replay cache capacity exceeded");
        }
        Long previous = SEEN.putIfAbsent(key, now);
        if (previous != null) {
            throw new IllegalArgumentException("Callback nonce was already used");
        }
    }

    /** Visible to tests that need a fresh nonce space. */
    public static void resetForTesting() {
        SEEN.clear();
        failClosedOnRedisError = false;
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (var entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }
}
