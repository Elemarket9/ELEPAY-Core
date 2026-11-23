package com.elepay.enterprise.wallet;

import java.time.Instant;

/**
 * Result of payment authorization across any operating mode.
 */
public record ExternalPaymentAuthResult(
        String authorizationId,
        String transactionReference,
        PaymentExecutionState state,
        long authorizedAmountMinorUnits,
        String currency,
        Instant authorizedAt,
        String rawPartnerResponse,
        boolean success,
        String errorMessage
) {
    public boolean isSuccessful() {
        return success;
    }

    public static ExternalPaymentAuthResult success(
            String authorizationId,
            String transactionReference,
            long amountMinorUnits,
            String currency,
            String rawPartnerResponse
    ) {
        return new ExternalPaymentAuthResult(
                authorizationId,
                transactionReference,
                PaymentExecutionState.AUTHORIZED,
                amountMinorUnits,
                currency,
                Instant.now(),
                rawPartnerResponse,
                true,
                null
        );
    }

    public static ExternalPaymentAuthResult failure(
            String transactionReference,
            String errorMessage
    ) {
        return new ExternalPaymentAuthResult(
                null,
                transactionReference,
                PaymentExecutionState.FAILED,
                0L,
                null,
                Instant.now(),
                null,
                false,
                errorMessage
        );
    }
}
