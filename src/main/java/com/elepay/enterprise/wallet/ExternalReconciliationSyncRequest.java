package com.elepay.enterprise.wallet;

import java.time.LocalDate;
import java.util.List;

/**
 * Request to perform reconciliation sync between enterprise partner ledger and ELEPAY.
 */
public record ExternalReconciliationSyncRequest(
        String partnerId,
        LocalDate reconciliationDate,
        String currency,
        List<ExternalLedgerRecord> records
) {
    public ExternalReconciliationSyncRequest {
        if (partnerId == null || partnerId.isBlank()) {
            throw new IllegalArgumentException("Partner ID is required");
        }
        if (reconciliationDate == null) {
            throw new IllegalArgumentException("Reconciliation date is required");
        }
        if (records == null) {
            records = List.of();
        }
    }
}
