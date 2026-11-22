package com.elepay.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptographic HMAC-SHA256 verification with constant-time equality and timestamp replay protection (Algorithm 073).
 */
public final class HmacVerification {
    private static final String HMAC_SHA256 = "HmacSHA256";
    public static final long DEFAULT_TOLERANCE_SECONDS = 300L; // 5 minutes

    private HmacVerification() {}

    /**
     * Computes raw HMAC-SHA256 bytes over payload using the secret key.
     */
    public static byte[] hmacSha256(byte[] secret, byte[] payload) {
        Objects.requireNonNull(secret, "secret cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA256));
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256", e);
        }
    }

    /**
     * Computes lowercase hex-encoded HMAC-SHA256 over string payload with string secret.
     */
    public static String computeHex(String secret, String payload) {
        Objects.requireNonNull(secret, "secret cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");
        byte[] raw = hmacSha256(secret.getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(raw);
    }

    /**
     * Constant-time comparison of expected HMAC against provided signature.
     */
    public static boolean verify(String secret, String payload, String signatureHex) {
        if (secret == null || payload == null || signatureHex == null) return false;
        String computed = computeHex(secret, payload);
        return MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                signatureHex.trim().toLowerCase().getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Verifies timestamped payload with replay window tolerance.
     * Canonical payload format: {@code timestampSeconds + "." + rawPayload}.
     *
     * @param secret secret key
     * @param timestampSeconds epoch seconds of request timestamp
     * @param rawPayload request payload
     * @param signatureHex hex signature
     * @param currentInstant current instant (e.g. clock)
     * @param toleranceSeconds maximum allowable clock drift / age window
     */
    public static boolean verifyWithTimestamp(String secret, long timestampSeconds, String rawPayload,
                                              String signatureHex, Instant currentInstant, long toleranceSeconds) {
        if (secret == null || rawPayload == null || signatureHex == null || currentInstant == null) {
            return false;
        }
        if (toleranceSeconds < 0) {
            throw new IllegalArgumentException("Tolerance seconds must be non-negative: " + toleranceSeconds);
        }

        long nowSeconds = currentInstant.getEpochSecond();
        if (Math.abs(nowSeconds - timestampSeconds) > toleranceSeconds) {
            return false;
        }

        String canonical = timestampSeconds + "." + rawPayload;
        return verify(secret, canonical, signatureHex);
    }

    public static boolean verifyWithTimestamp(String secret, long timestampSeconds, String rawPayload, String signatureHex) {
        return verifyWithTimestamp(secret, timestampSeconds, rawPayload, signatureHex, Instant.now(), DEFAULT_TOLERANCE_SECONDS);
    }
}
