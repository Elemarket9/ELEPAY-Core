package com.elepay.enterprise.wallet;

import java.util.List;

/**
 * Request to capture an authorized payment and allocate amounts across merchants.
 */
public record ExternalPaymentCaptureRequest(
        String idempotencyKey,
        String authorizationId,
        String transactionReference,
        long captureAmountMinorUnits,
        String currency,
        List<PaymentAllocationSplit> allocations
) {
    public ExternalPaymentCaptureRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
        if (authorizationId == null || authorizationId.isBlank()) {
            throw new IllegalArgumentException("Authorization ID is required");
        }
        if (captureAmountMinorUnits <= 0) {
            throw new IllegalArgumentException("Capture amount must be positive: " + captureAmountMinorUnits);
        }
        if (allocations == null || allocations.isEmpty()) {
            allocations = List.of(new PaymentAllocationSplit("default", captureAmountMinorUnits, 0L, 0L));
        }

        // Financial conservation check: Sum of allocation amounts must equal capture amount
        long sumAllocations = 0;
        for (PaymentAllocationSplit split : allocations) {
            sumAllocations += split.amountMinorUnits();
        }
        if (sumAllocations != captureAmountMinorUnits) {
            throw new IllegalArgumentException(
                    "Financial conservation violated: sum of allocations (" + sumAllocations
                            + ") does not match capture amount (" + captureAmountMinorUnits + ")"
            );
        }
    }

    public ExternalPaymentCaptureRequest(String authorizationId, long captureAmountMinorUnits, List<PaymentAllocationSplit> allocations) {
        this("cap_" + authorizationId, authorizationId, "tx_" + authorizationId, captureAmountMinorUnits, "GHS",
                (allocations == null || allocations.isEmpty())
                        ? List.of(new PaymentAllocationSplit("default", captureAmountMinorUnits, 0L, 0L))
                        : allocations);
    }

    public ExternalPaymentCaptureRequest(String idempotencyKey, String authorizationId, long captureAmountMinorUnits, String currency, List<PaymentAllocationSplit> allocations) {
        this(idempotencyKey, authorizationId, "tx_" + authorizationId, captureAmountMinorUnits, currency,
                (allocations == null || allocations.isEmpty())
                        ? List.of(new PaymentAllocationSplit("default", captureAmountMinorUnits, 0L, 0L))
                        : allocations);
    }
}
