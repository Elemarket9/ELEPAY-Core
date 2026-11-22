package com.elepay.enterprise.wallet;

import java.time.Instant;

/**
 * Result of status query for an external transaction.
 */
public record ExternalPaymentStatusResult(
        String reference,
        PaymentExecutionState state,
        long amountMinorUnits,
        String currency,
        Instant timestamp,
        boolean found,
        String message
) {
    public static ExternalPaymentStatusResult notFound(String reference) {
        return new ExternalPaymentStatusResult(reference, null, 0L, null, Instant.now(), false, "Transaction not found");
    }

    public static ExternalPaymentStatusResult of(
            String reference,
            PaymentExecutionState state,
            long amountMinorUnits,
            String currency,
            Instant timestamp,
            String message
    ) {
        return new ExternalPaymentStatusResult(reference, state, amountMinorUnits, currency, timestamp, true, message);
    }
}
