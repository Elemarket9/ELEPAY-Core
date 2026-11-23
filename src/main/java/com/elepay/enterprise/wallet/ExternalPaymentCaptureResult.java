package com.elepay.enterprise.wallet;

import java.time.Instant;
import java.util.List;

/**
 * Result of capturing an authorized payment.
 */
public record ExternalPaymentCaptureResult(
        String captureId,
        String authorizationId,
        PaymentExecutionState state,
        long capturedAmountMinorUnits,
        String currency,
        List<PaymentAllocationSplit> allocations,
        Instant capturedAt,
        boolean success,
        String errorMessage
) {
    public boolean isSuccessful() {
        return success;
    }

    public static ExternalPaymentCaptureResult success(
            String captureId,
            String authorizationId,
            long capturedAmountMinorUnits,
            String currency,
            List<PaymentAllocationSplit> allocations
    ) {
        return new ExternalPaymentCaptureResult(
                captureId,
                authorizationId,
                PaymentExecutionState.CAPTURED,
                capturedAmountMinorUnits,
                currency,
                allocations,
                Instant.now(),
                true,
                null
        );
    }

    public static ExternalPaymentCaptureResult failure(
            String authorizationId,
            String errorMessage
    ) {
        return new ExternalPaymentCaptureResult(
                null,
                authorizationId,
                PaymentExecutionState.FAILED,
                0L,
                null,
                List.of(),
                Instant.now(),
                false,
                errorMessage
        );
    }
}
