package com.elepay.enterprise.wallet;

import java.time.Instant;
import java.util.List;

/**
 * Result of refunding funds to customer wallet.
 */
public record ExternalWalletRefundResult(
        String refundId,
        String originalCaptureId,
        PaymentExecutionState state,
        long refundedAmountMinorUnits,
        String currency,
        List<RefundAllocationItem> items,
        Instant refundedAt,
        boolean success,
        String errorMessage
) {
    public static ExternalWalletRefundResult success(
            String refundId,
            String originalCaptureId,
            PaymentExecutionState state,
            long refundedAmountMinorUnits,
            String currency,
            List<RefundAllocationItem> items
    ) {
        return new ExternalWalletRefundResult(
                refundId,
                originalCaptureId,
                state,
                refundedAmountMinorUnits,
                currency,
                items == null ? List.of() : items,
                Instant.now(),
                true,
                null
        );
    }

    public static ExternalWalletRefundResult failure(
            String originalCaptureId,
            String errorMessage
    ) {
        return new ExternalWalletRefundResult(
                null,
                originalCaptureId,
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
