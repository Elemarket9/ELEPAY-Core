package com.elepay.enterprise.settlement;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Record of an item held in conditional delivery escrow.
 */
public record DeliveryEscrowRecord(
        UUID id,
        UUID enterpriseId,
        String orderReference,
        String itemReference,
        UUID merchantId,
        long grossAmountPesewas,
        long netPayablePesewas,
        long commissionPesewas,
        String currency,
        DeliveryStatus deliveryStatus,
        EscrowStatus escrowStatus,
        OffsetDateTime deliveredAt,
        OffsetDateTime releasedAt,
        UUID ledgerHoldTxId,
        UUID ledgerReleaseTxId,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public boolean isEligibleForRelease() {
        return deliveryStatus == DeliveryStatus.DELIVERED && escrowStatus == EscrowStatus.HELD;
    }
}
