package com.elepay.enterprise.refund;

import java.util.UUID;

/**
 * Specification of an item or line item to be refunded within an enterprise order.
 * All monetary amounts are in integer minor units (Ghana Pesewas).
 */
public record EnterpriseRefundItemSpecification(
        String itemReference,
        UUID merchantId,
        long itemGrossPesewas,
        Long customMerchantReversalPesewas,
        Long customCommissionReversalPesewas,
        Long customPlatformFeeReversalPesewas,
        Long customReserveReversalPesewas,
        Long taxReversalPesewas,
        Integer commissionBps,
        Integer platformFeeBps,
        Integer reserveBps,
        String reason
) {
    public EnterpriseRefundItemSpecification {
        if (itemReference == null || itemReference.isBlank()) {
            throw new IllegalArgumentException("itemReference cannot be blank");
        }
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId cannot be null");
        }
        if (itemGrossPesewas <= 0) {
            throw new IllegalArgumentException("itemGrossPesewas must be strictly positive: " + itemGrossPesewas);
        }
        if (taxReversalPesewas == null) {
            taxReversalPesewas = 0L;
        }
    }

    public static EnterpriseRefundItemSpecification of(
            String itemReference,
            UUID merchantId,
            long itemGrossPesewas,
            int commissionBps,
            String reason
    ) {
        return new EnterpriseRefundItemSpecification(
                itemReference,
                merchantId,
                itemGrossPesewas,
                null,
                null,
                null,
                null,
                0L,
                commissionBps,
                0,
                0,
                reason
        );
    }

    public static EnterpriseRefundItemSpecification withCustomReversal(
            String itemReference,
            UUID merchantId,
            long itemGrossPesewas,
            long merchantReversalPesewas,
            long commissionReversalPesewas,
            String reason
    ) {
        return new EnterpriseRefundItemSpecification(
                itemReference,
                merchantId,
                itemGrossPesewas,
                merchantReversalPesewas,
                commissionReversalPesewas,
                0L,
                0L,
                0L,
                null,
                null,
                null,
                reason
        );
    }
}
