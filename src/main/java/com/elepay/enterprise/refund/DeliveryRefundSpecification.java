package com.elepay.enterprise.refund;

import java.util.UUID;

/**
 * Specification for reversing or adjusting delivery fulfillment fees during an enterprise refund.
 */
public record DeliveryRefundSpecification(
        UUID deliveryPartnerId,
        long deliveryGrossPesewas,
        long deliveryNetReversalPesewas,
        long marketplaceFeeReversalPesewas,
        long platformFeeReversalPesewas,
        String reason
) {
    public DeliveryRefundSpecification {
        if (deliveryPartnerId == null) {
            throw new IllegalArgumentException("deliveryPartnerId cannot be null");
        }
        if (deliveryGrossPesewas <= 0) {
            throw new IllegalArgumentException("deliveryGrossPesewas must be strictly positive: " + deliveryGrossPesewas);
        }
        long sum = Math.addExact(deliveryNetReversalPesewas, Math.addExact(marketplaceFeeReversalPesewas, platformFeeReversalPesewas));
        if (sum != deliveryGrossPesewas) {
            throw new IllegalArgumentException("Delivery refund breakdown sum (" + sum
                    + ") must equal deliveryGrossPesewas (" + deliveryGrossPesewas + ")");
        }
    }
}
