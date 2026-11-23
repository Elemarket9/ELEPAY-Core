package com.elepay.enterprise.wallet;

import com.elepay.common.HmacVerification;
import com.elepay.identity.DistributedNonceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;

/**
 * Enterprise HMAC webhook signature verifier with timestamp replay protection,
 * constant-time comparison, nonce deduplication, and zero-downtime key rotation support.
 */
@Component
public class HmacWebhookVerifier {

    public static final long DEFAULT_TOLERANCE_SECONDS = 300L; // 5 minutes

    // Fleet-wide replay dedupe. A per-JVM map would let a captured (timestamp,nonce,signature) replay
    // against any instance that had not individually seen the nonce; the shared store makes dedupe global.
    private final DistributedNonceStore nonceStore;

    @Autowired
    public HmacWebhookVerifier(DistributedNonceStore nonceStore) {
        this.nonceStore = nonceStore;
    }

    /** Test-only: unit tests construct the verifier directly; falls back to the store's in-memory mode. */
    public HmacWebhookVerifier() {
        this(new DistributedNonceStore());
    }

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

        // 2. Validate nonce presence
        String nonce = request.nonceHeader().trim();
        if (nonce.isEmpty()) {
            return WebhookSignatureVerificationResult.invalid(request.partnerId(), "Missing nonce header");
        }

        // 3. Canonicalize payload
        String canonical = canonicalize(timestampSeconds, nonce, request.rawBody());
        String providedSignature = request.signatureHeader().trim().toLowerCase();

        // 4. Verify against active signing key, then (during rotation) the previous key.
        boolean signatureValid = constantTimeVerify(credentials.activeSigningKey(), canonical, providedSignature)
                || (credentials.previousSigningKey() != null && !credentials.previousSigningKey().isBlank()
                        && constantTimeVerify(credentials.previousSigningKey(), canonical, providedSignature));
        if (!signatureValid) {
            return WebhookSignatureVerificationResult.invalid(request.partnerId(), "HMAC signature mismatch");
        }

        // 5. Replay protection via the shared, cross-instance nonce store. Done only after the signature
        //    is confirmed valid so an unauthenticated caller cannot poison the fleet-wide store. Keyed by
        //    partner + nonce (nonces are only unique per partner) with TTL = the replay tolerance window.
        String nonceKey = request.partnerId() + ":" + nonce;
        if (!nonceStore.recordNonceIfAbsent(nonceKey, Duration.ofSeconds(DEFAULT_TOLERANCE_SECONDS))) {
            return WebhookSignatureVerificationResult.invalid(request.partnerId(), "Replay attack detected: duplicate nonce");
        }

        return WebhookSignatureVerificationResult.valid(request.partnerId());
    }

    private boolean constantTimeVerify(String secret, String canonical, String providedSignatureHex) {
        if (secret == null || canonical == null || providedSignatureHex == null) return false;
        String expected = HmacVerification.computeHex(secret, canonical);
        // Comparing hex strings may return early on a length mismatch (leaks length only, not content);
        // negligible for fixed-width SHA-256 hex, so left as-is.
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedSignatureHex.getBytes(StandardCharsets.UTF_8)
        );
    }

    public void clearNonceCacheForTesting() {
        nonceStore.clear();
    }
}
