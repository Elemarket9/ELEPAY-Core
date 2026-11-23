package com.elepay.enterprise.refund;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Top-level enterprise refund request, supporting full and item-level refunds,
 * multi-merchant simultaneous refunds, and destination routing (external wallet, MoMo, bank, internal).
 */
public record EnterpriseItemRefundRequest(
        String idempotencyKey,
        UUID enterpriseId,
        String orderReference,
        String externalCaptureId,
        String externalCustomerId,
        RefundDestination destination,
        Map<String, String> destinationDetails,
        String currency,
        String reason,
        List<EnterpriseRefundItemSpecification> items,
        DeliveryRefundSpecification deliveryAdjustment,
        Map<String, String> metadata
) {
    public EnterpriseItemRefundRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (enterpriseId == null) {
            throw new IllegalArgumentException("enterpriseId is required");
        }
        if (orderReference == null || orderReference.isBlank()) {
            throw new IllegalArgumentException("orderReference is required");
        }
        if (externalCustomerId == null || externalCustomerId.isBlank()) {
            throw new IllegalArgumentException("externalCustomerId is required");
        }
        if (destination == null) {
            throw new IllegalArgumentException("destination is required");
        }
        if (currency == null || currency.isBlank()) {
            currency = "GHS";
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("At least one item specification is required for item refund");
        }
        items = List.copyOf(items);
        destinationDetails = destinationDetails == null ? Map.of() : Map.copyOf(destinationDetails);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Total gross refund amount in minor units requested across all items plus delivery adjustment.
     */
    public long totalRefundPesewas() {
        long total = items.stream().mapToLong(EnterpriseRefundItemSpecification::itemGrossPesewas).sum();
        if (deliveryAdjustment != null) {
            total = Math.addExact(total, deliveryAdjustment.deliveryGrossPesewas());
        }
        return total;
    }
}
