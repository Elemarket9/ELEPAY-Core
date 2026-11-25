package com.elepay.enterprise.refund;

import java.util.UUID;

/**
 * Calculated financial reversal breakdown for a refunded item.
 *
 * <p>Enforces strict financial conservation:
 * {@code grossRefundPesewas == merchantReversalPesewas + commissionReversalPesewas + platformFeeReversalPesewas + reserveReversalPesewas + taxReversalPesewas}
 */
public record CalculatedItemRefund(
        String itemReference,
        UUID merchantId,
        long grossRefundPesewas,
        long merchantReversalPesewas,
        long commissionReversalPesewas,
        long platformFeeReversalPesewas,
        long reserveReversalPesewas,
        long taxReversalPesewas,
        String reason
) {
    public CalculatedItemRefund {
        if (itemReference == null || itemReference.isBlank()) {
            throw new IllegalArgumentException("itemReference cannot be blank");
        }
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId cannot be null");
        }
        if (grossRefundPesewas <= 0) {
            throw new IllegalArgumentException("grossRefundPesewas must be strictly positive: " + grossRefundPesewas);
        }
        if (merchantReversalPesewas < 0 || commissionReversalPesewas < 0 || platformFeeReversalPesewas < 0
                || reserveReversalPesewas < 0 || taxReversalPesewas < 0)
            throw new IllegalArgumentException("Refund components must be non-negative");
        long sum = Math.addExact(
                Math.addExact(merchantReversalPesewas, commissionReversalPesewas),
                Math.addExact(
                        Math.addExact(platformFeeReversalPesewas, reserveReversalPesewas),
                        taxReversalPesewas
                )
        );
        if (sum != grossRefundPesewas) {
            throw new IllegalStateException("Calculated refund breakdown violated conservation for item " + itemReference
                    + ": gross=" + grossRefundPesewas + " != sum(" + sum + ")");
        }
    }
}
