package com.elepay.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Rail and gateway callbacks must sign {@code timestamp.nonce.body} and are accepted only inside
 * a five-minute window. A nonce is remembered so the same signed request cannot be replayed.
 */
public final class CallbackAuthenticity {
    public static final long WINDOW_SECONDS = 300L;
    private static final String REDIS_NONCE_PREFIX = "elepay:callback:nonce:";
    private static final ConcurrentHashMap<String, Long> SEEN = new ConcurrentHashMap<>();
    private static volatile StringRedisTemplate redis;

    private CallbackAuthenticity() {}

    @Component
    static class RedisBridge {
        RedisBridge(@Autowired(required = false) StringRedisTemplate redisTemplate) {
            CallbackAuthenticity.redis = redisTemplate;
        }
    }

    /** Visible to tests to wire a Redis template without a Spring context. */
    public static void configureRedis(StringRedisTemplate redisTemplate) {
        CallbackAuthenticity.redis = redisTemplate;
    }

    public static void verify(String secret, Map<String, String> headers, String body, String... signatureHeaderNames) {
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
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > WINDOW_SECONDS) {
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
        String key = nonce + ":" + timestamp.trim();
        StringRedisTemplate redisClient = redis;
        if (redisClient != null) {
            try {
                Boolean fresh = redisClient.opsForValue()
                        .setIfAbsent(REDIS_NONCE_PREFIX + key, "1", Duration.ofSeconds(WINDOW_SECONDS * 2));
                if (Boolean.FALSE.equals(fresh)) {
                    throw new IllegalArgumentException("Callback nonce was already used");
                }
                if (Boolean.TRUE.equals(fresh)) {
                    return;
                }
            } catch (IllegalArgumentException replay) {
                throw replay;
            } catch (RuntimeException ignored) {
                // Redis unreachable; fall back to in-JVM replay cache
            }
        }
        Long previous = SEEN.putIfAbsent(key, now);
        if (previous != null) {
            throw new IllegalArgumentException("Callback nonce was already used");
        }
        SEEN.entrySet().removeIf(e -> now - e.getValue() > WINDOW_SECONDS * 2);
    }

    /** Visible to tests that need a fresh nonce space. */
    public static void resetForTesting() {
        SEEN.clear();
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (var entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }
}
