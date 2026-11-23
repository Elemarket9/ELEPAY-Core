package com.elepay.enterprise.checkout;

import java.util.List;

/**
 * Result of the deterministic multi-merchant split calculation.
 * Encapsulates multi-tier deductions (marketplace commission, platform fee, reserve retention)
 * and sub-merchant/delivery partner net payables.
 */
public record EnterpriseSplitResult(
        long totalPesewas,
        String currency,
        long totalMerchantGross,
        long totalMerchantNet,
        long totalDeliveryGross,
        long totalDeliveryNet,
        long totalMarketplaceCommission,
        long totalPlatformRevenue,
        long totalReserveRetained,
        List<CalculatedMerchantAllocation> merchantAllocations,
        List<CalculatedDeliveryAllocation> deliveryAllocations
) {
    public EnterpriseSplitResult {
        merchantAllocations = List.copyOf(merchantAllocations);
        deliveryAllocations = List.copyOf(deliveryAllocations);
    }

    public long totalOrderPesewas() {
        return totalPesewas;
    }

    /**
     * Validates that total money is strictly conserved across all allocations.
     * sum(netPayables) + sum(commissions) + sum(platformFees) + sum(reserves) == totalPesewas
     */
    public void validateConservation() {
        long sumCredits = Math.addExact(
                Math.addExact(totalMerchantNet, totalDeliveryNet),
                Math.addExact(
                        Math.addExact(totalMarketplaceCommission, totalPlatformRevenue),
                        totalReserveRetained
                )
        );
        if (sumCredits != totalPesewas) {
            throw new IllegalStateException("Strict financial conservation violated: totalPesewas="
                    + totalPesewas + " != sumCredits=" + sumCredits);
        }
    }
}
