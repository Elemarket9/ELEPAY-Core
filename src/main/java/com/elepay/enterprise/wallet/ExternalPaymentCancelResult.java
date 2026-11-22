package com.elepay.enterprise.wallet;

import java.time.Instant;

/**
 * Result of cancelling an authorized payment.
 */
public record ExternalPaymentCancelResult(
        String authorizationId,
        PaymentExecutionState state,
        String reason,
        Instant cancelledAt,
        boolean success,
        String errorMessage
) {
    public static ExternalPaymentCancelResult success(String authorizationId, String reason) {
        return new ExternalPaymentCancelResult(
                authorizationId,
                PaymentExecutionState.CANCELLED,
                reason,
                Instant.now(),
                true,
                null
        );
    }

    public static ExternalPaymentCancelResult failure(String authorizationId, String errorMessage) {
        return new ExternalPaymentCancelResult(
                authorizationId,
                PaymentExecutionState.FAILED,
                null,
                Instant.now(),
                false,
                errorMessage
        );
    }
}
