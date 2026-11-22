package com.elepay.enterprise.checkout;

import java.util.UUID;

/**
 * Resulting financial allocation for a delivery partner fulfillment leg.
 *
 * <p>Financial Invariant:
 * deliveryGrossPesewas == deliveryNetPesewas + marketplaceCommissionPesewas + platformFeePesewas
 */
public record CalculatedDeliveryAllocation(
        int lineIndex,
        UUID deliveryPartnerId,
        String deliveryPartnerName,
        String description,
        long deliveryGrossPesewas,
        long marketplaceCommissionPesewas,
        long platformFeePesewas,
        long deliveryNetPesewas
) {
    public CalculatedDeliveryAllocation {
        long deductions = Math.addExact(marketplaceCommissionPesewas, platformFeePesewas);
        long totalAllocated = Math.addExact(deductions, deliveryNetPesewas);
        if (totalAllocated != deliveryGrossPesewas) {
            throw new IllegalStateException("Delivery allocation conservation violated for line " + lineIndex
                    + ": gross=" + deliveryGrossPesewas + " != sum(" + totalAllocated + ")");
        }
    }
}
