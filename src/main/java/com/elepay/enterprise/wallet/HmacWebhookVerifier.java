package com.elepay.enterprise.wallet;

import com.elepay.common.HmacVerification;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enterprise HMAC webhook signature verifier with timestamp replay protection,
 * constant-time comparison, nonce deduplication, and zero-downtime key rotation support.
 */
@Component
public class HmacWebhookVerifier {

    public static final long DEFAULT_TOLERANCE_SECONDS = 300L; // 5 minutes

    // In-memory cache for observed nonces bound to epoch seconds: nonce -> expirationEpochSecond
    private final Map<String, Long> observedNonces = new ConcurrentHashMap<>();

    /**
     * Computes the canonical signature string for an enterprise webhook payload.
     * Format: {@code timestamp + "." + nonce + "." + rawBody}
     */
    public static String canonicalize(long timestampSeconds, String nonce, String rawBody) {
        return timestampSeconds + "." + (nonce != null ? nonce.trim() : "") + "." + (rawBody != null ? rawBody : "");
    }

    /**
     * Computes hex-encoded HMAC-SHA256 over the canonical payload.
     */
    public static String computeSignature(String secret, long timestampSeconds, String nonce, String rawBody) {
        String canonical = canonicalize(timestampSeconds, nonce, rawBody);
        return HmacVerification.computeHex(secret, canonical);
    }

    /**
     * Verifies an incoming partner webhook request.
     *
     * @param request The verification request containing headers and raw body.
     * @param credentials The partner credentials containing active and previous signing keys.
     * @return Verification result indicating validity or failure reason.
     */
    public WebhookSignatureVerificationResult verify(
            WebhookSignatureVerificationRequest request,
            PartnerAuthCredentials credentials
    ) {
        if (credentials == null || !credentials.active()) {
            return WebhookSignatureVerificationResult.invalid(
                    request.partnerId(), "Partner credentials not found or inactive"
            );
        }

        // 1. Validate timestamp
        long timestampSeconds;
        try {
            timestampSeconds = Long.parseLong(request.timestampHeader().trim());
        } catch (NumberFormatException e) {
            return WebhookSignatureVerificationResult.invalid(request.partnerId(), "Invalid timestamp header format");
        }

        long nowSeconds = Instant.now().getEpochSecond();
        if (Math.abs(nowSeconds - timestampSeconds) > DEFAULT_TOLERANCE_SECONDS) {
            return WebhookSignatureVerificationResult.invalid(
                    request.partnerId(), "Timestamp expired or clock skew exceeded tolerance window"
            );
        }

        // 2. Validate nonce & replay protection
        String nonce = request.nonceHeader().trim();
        if (nonce.isEmpty()) {
            return WebhookSignatureVerificationResult.invalid(request.partnerId(), "Missing nonce header");
        }

        cleanExpiredNonces(nowSeconds);
        Long existingExpiry = observedNonces.putIfAbsent(nonce, timestampSeconds + DEFAULT_TOLERANCE_SECONDS);
        if (existingExpiry != null) {
            return WebhookSignatureVerificationResult.invalid(request.partnerId(), "Replay attack detected: duplicate nonce");
        }

        // 3. Canonicalize payload
        String canonical = canonicalize(timestampSeconds, nonce, request.rawBody());
        String providedSignature = request.signatureHeader().trim().toLowerCase();

        // 4. Verify against active signing key
        if (constantTimeVerify(credentials.activeSigningKey(), canonical, providedSignature)) {
            return WebhookSignatureVerificationResult.valid(request.partnerId());
        }

        // 5. If key rotation is in progress, check previous signing key
        if (credentials.previousSigningKey() != null && !credentials.previousSigningKey().isBlank()) {
            if (constantTimeVerify(credentials.previousSigningKey(), canonical, providedSignature)) {
                return WebhookSignatureVerificationResult.valid(request.partnerId());
            }
        }

        return WebhookSignatureVerificationResult.invalid(request.partnerId(), "HMAC signature mismatch");
    }

    private boolean constantTimeVerify(String secret, String canonical, String providedSignatureHex) {
        if (secret == null || canonical == null || providedSignatureHex == null) return false;
        String expected = HmacVerification.computeHex(secret, canonical);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedSignatureHex.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void cleanExpiredNonces(long nowSeconds) {
        if (observedNonces.size() > 1000) {
            observedNonces.entrySet().removeIf(entry -> entry.getValue() < nowSeconds);
        }
    }

    public void clearNonceCacheForTesting() {
        observedNonces.clear();
    }
}
