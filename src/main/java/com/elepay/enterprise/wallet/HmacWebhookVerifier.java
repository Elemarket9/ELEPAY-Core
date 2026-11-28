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
    /** Must cover the full [-DEFAULT_TOLERANCE_SECONDS, +DEFAULT_TOLERANCE_SECONDS] window plus 1s margin. */
    public static final long NONCE_TTL_SECONDS = DEFAULT_TOLERANCE_SECONDS * 2 + 1;

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
        if (request == null) {
            return WebhookSignatureVerificationResult.invalid(null, "Missing verification request");
        }
        if (credentials == null || !credentials.active()) {
            return WebhookSignatureVerificationResult.invalid(
                    request.partnerId(), "Partner credentials not found or inactive"
            );
        }

        // 1. Validate timestamp (null-safe)
        String rawTimestamp = request.timestampHeader();
        if (rawTimestamp == null || rawTimestamp.isBlank()) {
            return WebhookSignatureVerificationResult.invalid(request.partnerId(), "Missing timestamp header");
        }
        long timestampSeconds;
        try {
            timestampSeconds = Long.parseLong(rawTimestamp.trim());
        } catch (NumberFormatException e) {
            return WebhookSignatureVerificationResult.invalid(request.partnerId(), "Invalid timestamp header format");
        }

        long nowSeconds = Instant.now().getEpochSecond();
        if (Math.abs(nowSeconds - timestampSeconds) > DEFAULT_TOLERANCE_SECONDS) {
            return WebhookSignatureVerificationResult.invalid(
                    request.partnerId(), "Timestamp expired or clock skew exceeded tolerance window"
            );
        }

        // 2. Validate nonce presence and format (null-safe; reject '.' to prevent canonicalization ambiguity)
        String rawNonce = request.nonceHeader();
        if (rawNonce == null || rawNonce.isBlank()) {
            return WebhookSignatureVerificationResult.invalid(request.partnerId(), "Missing nonce header");
        }
        String nonce = rawNonce.trim();
        if (nonce.length() > 128 || nonce.indexOf('.') >= 0) {
            return WebhookSignatureVerificationResult.invalid(request.partnerId(), "Invalid nonce header format");
        }

        // 3. Validate signature presence (null-safe) & canonicalize payload
        String rawSignature = request.signatureHeader();
        if (rawSignature == null || rawSignature.isBlank()) {
            return WebhookSignatureVerificationResult.invalid(request.partnerId(), "Missing signature header");
        }
        String canonical = canonicalize(timestampSeconds, nonce, request.rawBody());
        String providedSignature = rawSignature.trim().toLowerCase();

        // 4. Verify against active signing key, then (during rotation) the previous key.
        boolean signatureValid = (credentials.activeSigningKey() != null && !credentials.activeSigningKey().isBlank()
                        && constantTimeVerify(credentials.activeSigningKey(), canonical, providedSignature))
                || (credentials.previousSigningKey() != null && !credentials.previousSigningKey().isBlank()
                        && constantTimeVerify(credentials.previousSigningKey(), canonical, providedSignature));
        if (!signatureValid) {
            return WebhookSignatureVerificationResult.invalid(request.partnerId(), "HMAC signature mismatch");
        }

        // 5. Replay protection via the shared, cross-instance nonce store. Done only after the signature
        //    is confirmed valid so an unauthenticated caller cannot poison the fleet-wide store. Keyed by
        //    partner + nonce with TTL = full +/- tolerance window + 1s (601s).
        String nonceKey = request.partnerId() + ":" + nonce;
        if (!nonceStore.recordNonceIfAbsent(nonceKey, Duration.ofSeconds(NONCE_TTL_SECONDS))) {
            return WebhookSignatureVerificationResult.invalid(request.partnerId(), "Replay attack detected: duplicate nonce");
        }

        return WebhookSignatureVerificationResult.valid(request.partnerId());
    }

    private boolean constantTimeVerify(String secret, String canonical, String providedSignatureHex) {
        if (secret == null || secret.isBlank() || canonical == null || providedSignatureHex == null) return false;
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
