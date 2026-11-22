package com.elepay.enterprise.wallet;

/**
 * Result of webhook HMAC verification.
 */
public record WebhookSignatureVerificationResult(
        boolean valid,
        String partnerId,
        String failureReason
) {
    public static WebhookSignatureVerificationResult valid(String partnerId) {
        return new WebhookSignatureVerificationResult(true, partnerId, null);
    }

    public static WebhookSignatureVerificationResult invalid(String partnerId, String failureReason) {
        return new WebhookSignatureVerificationResult(false, partnerId, failureReason);
    }
}
