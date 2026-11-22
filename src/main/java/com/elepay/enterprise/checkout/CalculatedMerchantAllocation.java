package com.elepay.enterprise.checkout;

import java.util.UUID;

/**
 * Resulting financial allocation for an individual sub-merchant item line after multi-tier splitting.
 *
 * <p>Financial Invariant:
 * grossAmountPesewas == netPayablePesewas + marketplaceCommissionPesewas + platformFeePesewas + reservePesewas
 */
public record CalculatedMerchantAllocation(
        int lineIndex,
        UUID merchantId,
        String merchantName,
        String category,
        String description,
        long grossAmountPesewas,
        long marketplaceCommissionPesewas,
        long platformFeePesewas,
        long reservePesewas,
        long netPayablePesewas,
        boolean holdUntilDelivered
) {
    public CalculatedMerchantAllocation {
        long deductions = Math.addExact(
                Math.addExact(marketplaceCommissionPesewas, platformFeePesewas),
                reservePesewas
        );
        long totalAllocated = Math.addExact(deductions, netPayablePesewas);
        if (totalAllocated != grossAmountPesewas) {
            throw new IllegalStateException("Allocation conservation violated for line " + lineIndex
                    + ": gross=" + grossAmountPesewas + " != sum(" + totalAllocated + ")");
        }
    }
}
