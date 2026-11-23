package com.elepay.enterprise.wallet;

/**
 * Split allocation specification for a multi-merchant payment.
 * All monetary amounts are in integer minor units (pesewas/cents).
 */
public record PaymentAllocationSplit(
        String merchantId,
        long amountMinorUnits,
        long commissionMinorUnits,
        long deliveryFeeMinorUnits,
        String itemReference,
        String description
) {
    public PaymentAllocationSplit {
        if (amountMinorUnits < 0) {
            throw new IllegalArgumentException("Amount must be non-negative: " + amountMinorUnits);
        }
        if (commissionMinorUnits < 0) {
            throw new IllegalArgumentException("Commission must be non-negative: " + commissionMinorUnits);
        }
        if (deliveryFeeMinorUnits < 0) {
            throw new IllegalArgumentException("Delivery fee must be non-negative: " + deliveryFeeMinorUnits);
        }
        if (commissionMinorUnits + deliveryFeeMinorUnits > amountMinorUnits) {
            throw new IllegalArgumentException("Deductions cannot exceed allocated amount: " + amountMinorUnits);
        }
    }

    public PaymentAllocationSplit(String merchantId, long amountMinorUnits, long commissionMinorUnits, long deliveryFeeMinorUnits) {
        this(merchantId, amountMinorUnits, commissionMinorUnits, deliveryFeeMinorUnits, null, null);
    }

    public long netPayableToMerchant() {
        return amountMinorUnits - commissionMinorUnits - deliveryFeeMinorUnits;
    }
}
