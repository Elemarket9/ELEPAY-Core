package com.elepay.enterprise.treasury;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Designated enterprise treasury account representation.
 */
public record EnterpriseTreasuryAccount(
        UUID id,
        UUID enterpriseId,
        TreasuryAccountType accountType,
        String accountCode,
        String currency,
        long balancePesewas,
        long reservedPesewas,
        String status,
        boolean strictSegregation,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    /** Returns available unreserved balance in minor units (pesewas). */
    public long availablePesewas() {
        return Math.max(0L, balancePesewas - reservedPesewas);
    }
}
