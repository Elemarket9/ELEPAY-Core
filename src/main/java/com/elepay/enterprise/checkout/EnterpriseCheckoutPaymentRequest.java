package com.elepay.enterprise.checkout;

import com.elepay.ledger.Ledger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Command payload representing an externally collected checkout payment to be ingested and split.
 */
public record EnterpriseCheckoutPaymentRequest(
        String idempotencyKey,
        UUID enterpriseId,
        String orderReference,
        String externalPaymentReference,
        String paymentChannel,
        String currency,
        long totalPesewas,
        List<SubMerchantItemAllocation> items,
        List<DeliveryAllocation> deliveryFees,
        Map<String, String> metadata
) {
    public EnterpriseCheckoutPaymentRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey cannot be blank");
        }
        if (enterpriseId == null) {
            throw new IllegalArgumentException("enterpriseId cannot be null");
        }
        if (orderReference == null || orderReference.isBlank()) {
            throw new IllegalArgumentException("orderReference cannot be blank");
        }
        if (externalPaymentReference == null || externalPaymentReference.isBlank()) {
            throw new IllegalArgumentException("externalPaymentReference cannot be blank");
        }
        if (totalPesewas <= 0) {
            throw new IllegalArgumentException("totalPesewas must be strictly positive: " + totalPesewas);
        }
        if (currency == null || currency.isBlank()) {
            currency = Ledger.GHS;
        }
        if (paymentChannel == null || paymentChannel.isBlank()) {
            paymentChannel = "EXTERNAL_CHECKOUT";
        }
        items = items == null ? List.of() : List.copyOf(items);
        deliveryFees = deliveryFees == null ? List.of() : List.copyOf(deliveryFees);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static EnterpriseCheckoutPaymentRequest of(
            String idempotencyKey, UUID enterpriseId, String orderReference,
            String externalPaymentReference, String paymentChannel, long totalPesewas,
            List<SubMerchantItemAllocation> items, List<DeliveryAllocation> deliveryFees
    ) {
        return new EnterpriseCheckoutPaymentRequest(
                idempotencyKey, enterpriseId, orderReference, externalPaymentReference,
                paymentChannel, Ledger.GHS, totalPesewas, items, deliveryFees, Map.of()
        );
    }
}
