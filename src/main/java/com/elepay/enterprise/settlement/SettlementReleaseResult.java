package com.elepay.enterprise.settlement;

import java.util.List;
import java.util.UUID;

/**
 * Result of executing conditional settlement release.
 */
public record SettlementReleaseResult(
        UUID batchId,
        String batchReference,
        SettlementScheduleType scheduleType,
        int totalMerchants,
        int totalItemsReleased,
        int totalItemsHeld,
        long totalNetReleasedPesewas,
        long totalCommissionPesewas,
        String currency,
        UUID ledgerTransactionId,
        UUID payoutBatchId,
        boolean shortfallDetected,
        String status,
        List<DeliveryEscrowRecord> releasedItems,
        List<DeliveryEscrowRecord> heldItems
) {}
