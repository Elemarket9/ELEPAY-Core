package com.elepay.enterprise.wallet;

/**
 * Item-level refund specification within an enterprise payment refund.
 */
public record RefundAllocationItem(
        String merchantId,
        String itemId,
        long refundAmountMinorUnits,
        long commissionReversalMinorUnits,
        String reason
) {
    public RefundAllocationItem {
        if (refundAmountMinorUnits <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive: " + refundAmountMinorUnits);
        }
        if (commissionReversalMinorUnits < 0) {
            throw new IllegalArgumentException("Commission reversal must be non-negative: " + commissionReversalMinorUnits);
        }
        if (commissionReversalMinorUnits > refundAmountMinorUnits) {
            throw new IllegalArgumentException("Commission reversal cannot exceed refund amount: " + refundAmountMinorUnits);
        }
    }

    public RefundAllocationItem(String itemId, java.util.UUID merchantId, long refundAmountMinorUnits, String reason) {
        this(merchantId.toString(), itemId, refundAmountMinorUnits, 0L, reason);
    }
}
