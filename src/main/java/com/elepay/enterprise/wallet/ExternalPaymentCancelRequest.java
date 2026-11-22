package com.elepay.enterprise.wallet;

/**
 * Request to cancel or void an authorization before capture.
 */
public record ExternalPaymentCancelRequest(
        String idempotencyKey,
        String authorizationId,
        String reason
) {
    public ExternalPaymentCancelRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
        if (authorizationId == null || authorizationId.isBlank()) {
            throw new IllegalArgumentException("Authorization ID is required");
        }
    }
}
