package com.elepay.enterprise.checkout;

import java.util.UUID;

/**
 * Specification of an item or service line belonging to an enterprise sub-merchant.
 * All monetary amounts are represented in integer minor units (e.g. pesewas).
 */
public record SubMerchantItemAllocation(
        UUID merchantId,
        String merchantName,
        long grossAmountPesewas,
        String category,
        String description,
        Integer commissionBps,
        Long fixedCommissionPesewas,
        Long minCommissionPesewas,
        Integer platformFeeBps,
        Long fixedPlatformFeePesewas,
        Integer reserveBps,
        Long fixedReservePesewas,
        boolean holdUntilDelivered
) {
    public SubMerchantItemAllocation {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId cannot be null");
        }
        if (grossAmountPesewas <= 0) {
            throw new IllegalArgumentException("grossAmountPesewas must be strictly positive: " + grossAmountPesewas);
        }
        if (commissionBps != null && (commissionBps < 0 || commissionBps > 10000)) {
            throw new IllegalArgumentException("commissionBps must be between 0 and 10000: " + commissionBps);
        }
        if (fixedCommissionPesewas != null && fixedCommissionPesewas < 0) {
            throw new IllegalArgumentException("fixedCommissionPesewas cannot be negative: " + fixedCommissionPesewas);
        }
        if (minCommissionPesewas != null && minCommissionPesewas < 0) {
            throw new IllegalArgumentException("minCommissionPesewas cannot be negative: " + minCommissionPesewas);
        }
        if (platformFeeBps != null && (platformFeeBps < 0 || platformFeeBps > 10000)) {
            throw new IllegalArgumentException("platformFeeBps must be between 0 and 10000: " + platformFeeBps);
        }
        if (fixedPlatformFeePesewas != null && fixedPlatformFeePesewas < 0) {
            throw new IllegalArgumentException("fixedPlatformFeePesewas cannot be negative: " + fixedPlatformFeePesewas);
        }
        if (reserveBps != null && (reserveBps < 0 || reserveBps > 10000)) {
            throw new IllegalArgumentException("reserveBps must be between 0 and 10000: " + reserveBps);
        }
        if (fixedReservePesewas != null && fixedReservePesewas < 0) {
            throw new IllegalArgumentException("fixedReservePesewas cannot be negative: " + fixedReservePesewas);
        }
    }

    public static SubMerchantItemAllocation of(UUID merchantId, String merchantName, long grossAmountPesewas,
                                              int commissionBps, long fixedCommissionPesewas) {
        return new SubMerchantItemAllocation(merchantId, merchantName, grossAmountPesewas, "GENERAL",
                "Item sale", commissionBps, fixedCommissionPesewas, 0L, 0, 0L, 0, 0L, true);
    }

    public static SubMerchantItemAllocation simple(UUID merchantId, String merchantName, long grossAmountPesewas) {
        return new SubMerchantItemAllocation(merchantId, merchantName, grossAmountPesewas, "GENERAL",
                "Item sale", 0, 0L, 0L, 0, 0L, 0, 0L, false);
    }
}
