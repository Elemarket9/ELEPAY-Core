package com.elepay.enterprise.refund;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Result of executing an enterprise item-level refund.
 */
public record EnterpriseRefundResult(
        UUID refundId,
        String refundReference,
        String idempotencyKey,
        UUID enterpriseId,
        String orderReference,
        String externalCaptureId,
        String externalCustomerId,
        long totalRefundPesewas,
        String currency,
        RefundDestination destination,
        RefundStatus status,
        UUID ledgerTransactionId,
        String connectorRefundId,
        List<CalculatedItemRefund> calculatedItems,
        DeliveryRefundSpecification deliveryAdjustment,
        long totalMerchantDebitPesewas,
        long totalCommissionClawbackPesewas,
        long totalPlatformFeeClawbackPesewas,
        long totalReserveReleasePesewas,
        long totalDeliveryReversalPesewas,
        boolean replayed,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
) {}
