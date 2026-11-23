package com.elepay.enterprise.wallet;

import java.util.List;

/**
 * Request to process a full or item-level refund back to an external or white-label wallet.
 */
public record ExternalWalletRefundRequest(
        String idempotencyKey,
        String partnerId,
        String originalCaptureId,
        String transactionReference,
        String externalCustomerId,
        long refundAmountMinorUnits,
        String currency,
        String refundReason,
        List<RefundAllocationItem> items
) {
    public ExternalWalletRefundRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
        if (originalCaptureId == null || originalCaptureId.isBlank()) {
            throw new IllegalArgumentException("Original capture ID is required");
        }
        if (refundAmountMinorUnits <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive: " + refundAmountMinorUnits);
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency is required");
        }
        if (partnerId == null || partnerId.isBlank()) {
            throw new IllegalArgumentException("Partner id is required");
        }
        if (transactionReference == null || transactionReference.isBlank()) {
            transactionReference = "TX-REF-" + idempotencyKey;
        }
        if (items != null && !items.isEmpty()) {
            long itemsTotal = 0;
            for (RefundAllocationItem item : items) {
                itemsTotal += item.refundAmountMinorUnits();
            }
            if (itemsTotal != refundAmountMinorUnits) {
                throw new IllegalArgumentException(
                        "Sum of refund items (" + itemsTotal + ") does not equal refund amount (" + refundAmountMinorUnits + ")"
                );
            }
        }
    }

}
