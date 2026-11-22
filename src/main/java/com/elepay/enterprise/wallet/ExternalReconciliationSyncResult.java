package com.elepay.enterprise.wallet;

import java.time.Instant;
import java.util.List;

/**
 * Result of external ledger reconciliation.
 */
public record ExternalReconciliationSyncResult(
        String batchId,
        int totalRecords,
        int matchedCount,
        int discrepancyCount,
        long matchedAmountMinorUnits,
        long discrepancyAmountMinorUnits,
        List<String> discrepancies,
        Instant reconciledAt
) {}
