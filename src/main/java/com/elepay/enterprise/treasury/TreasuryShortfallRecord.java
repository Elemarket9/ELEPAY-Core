package com.elepay.enterprise.treasury;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Audit record of detected funding shortfall.
 */
public record TreasuryShortfallRecord(
        UUID id,
        UUID enterpriseId,
        String obligationType,
        long requiredFundingPesewas,
        long availableFundingPesewas,
        long shortfallPesewas,
        String status,
        ShortfallAction actionTaken,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt
) {}
