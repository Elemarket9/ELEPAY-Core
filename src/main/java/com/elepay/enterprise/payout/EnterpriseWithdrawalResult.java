package com.elepay.enterprise.payout;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Result of processing an enterprise external wallet customer withdrawal.
 */
public record EnterpriseWithdrawalResult(
        UUID withdrawalId,
        String withdrawalReference,
        String idempotencyKey,
        UUID enterpriseId,
        String externalCustomerId,
        String customerName,
        long amountPesewas,
        String currency,
        WithdrawalDestinationType destinationType,
        String destinationNetwork,
        String destinationAccount,
        WithdrawalStatus status,
        UUID payoutBatchId,
        UUID payoutId,
        boolean compliancePassed,
        String failureReason,
        UUID retryOf,
        boolean replayed,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
) {}
