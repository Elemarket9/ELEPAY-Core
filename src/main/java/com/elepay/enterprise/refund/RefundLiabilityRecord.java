package com.elepay.enterprise.refund;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Record of an outstanding sub-merchant refund liability that offsets pending settlement balances.
 */
public record RefundLiabilityRecord(
        UUID liabilityId,
        UUID enterpriseId,
        UUID merchantId,
        UUID refundId,
        long amountPesewas,
        boolean settled,
        OffsetDateTime createdAt,
        OffsetDateTime settledAt
) {}
