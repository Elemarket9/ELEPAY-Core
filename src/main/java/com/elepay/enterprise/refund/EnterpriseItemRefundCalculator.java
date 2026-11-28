package com.elepay.enterprise.refund;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic integer minor-unit calculator for enterprise item-level refunds.
 *
 * <p>Enforces strict financial conservation where the gross refund amount exactly equals
 * the sum of merchant debit, marketplace commission clawback, platform fee clawback,
 * reserve release, and tax adjustments.
 */
@Component
public class EnterpriseItemRefundCalculator {

    public List<CalculatedItemRefund> calculate(List<EnterpriseRefundItemSpecification> items) {
        Objects.requireNonNull(items, "items cannot be null");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items list cannot be empty");
        }

        List<CalculatedItemRefund> result = new ArrayList<>(items.size());
        for (EnterpriseRefundItemSpecification item : items) {
            result.add(calculateSingleItem(item));
        }
        return List.copyOf(result);
    }

    public CalculatedItemRefund calculateSingleItem(EnterpriseRefundItemSpecification item) {
        Objects.requireNonNull(item, "item cannot be null");
        long gross = item.itemGrossPesewas();
        if (gross <= 0) {
            throw new IllegalArgumentException("Item gross refund must be positive: " + gross);
        }

        long taxReversal = item.taxReversalPesewas() != null ? item.taxReversalPesewas() : 0L;
        if (taxReversal < 0 || taxReversal > gross) {
            throw new IllegalArgumentException("Invalid tax reversal amount: " + taxReversal);
        }

        long commissionReversal;
        long platformFeeReversal;
        long reserveReversal;
        long merchantReversal;
        final int maxPlatformFeeBps = 1000; // Hard cap: platform fee clawback cannot exceed 10% of item gross

        if (item.customMerchantReversalPesewas() != null && item.customCommissionReversalPesewas() != null) {
            commissionReversal = item.customCommissionReversalPesewas();
            platformFeeReversal = item.customPlatformFeeReversalPesewas() != null ? item.customPlatformFeeReversalPesewas() : 0L;
            reserveReversal = item.customReserveReversalPesewas() != null ? item.customReserveReversalPesewas() : 0L;
            merchantReversal = item.customMerchantReversalPesewas();

            if (merchantReversal < 0 || commissionReversal < 0 || platformFeeReversal < 0 || reserveReversal < 0) {
                throw new IllegalArgumentException("Custom refund reversal components cannot be negative");
            }

            int allowedBps = item.platformFeeBps() != null
                    ? Math.min(item.platformFeeBps(), maxPlatformFeeBps)
                    : maxPlatformFeeBps;
            if (allowedBps < 0) {
                throw new IllegalArgumentException("Platform fee basis points cannot be negative");
            }
            long maxAllowedPlatformFee = java.math.BigInteger.valueOf(gross)
                    .multiply(java.math.BigInteger.valueOf(allowedBps))
                    .divide(java.math.BigInteger.valueOf(10_000L))
                    .longValueExact();
            if (platformFeeReversal > maxAllowedPlatformFee) {
                throw new IllegalArgumentException("Custom platform fee reversal (" + platformFeeReversal
                        + ") exceeds maximum allowed (" + maxAllowedPlatformFee + ")");
            }

            long sum = Math.addExact(
                    Math.addExact(merchantReversal, commissionReversal),
                    Math.addExact(Math.addExact(platformFeeReversal, reserveReversal), taxReversal)
            );
            if (sum != gross) {
                throw new IllegalArgumentException("Custom refund breakdown (" + sum + ") does not equal gross (" + gross + ")");
            }
        } else {
            // Proportional integer math calculation (BigInteger prevents 64-bit overflow on large gross)
            int commBps = item.commissionBps() != null ? item.commissionBps() : 0;
            int platBps = item.platformFeeBps() != null ? item.platformFeeBps() : 0;
            int resBps = item.reserveBps() != null ? item.reserveBps() : 0;

            if (commBps < 0 || commBps > 10000 || platBps < 0 || platBps > maxPlatformFeeBps || resBps < 0 || resBps > 10000) {
                throw new IllegalArgumentException("Basis points out of allowed bounds (platformFeeBps max " + maxPlatformFeeBps + ")");
            }

            java.math.BigInteger grossBig = java.math.BigInteger.valueOf(gross);
            java.math.BigInteger bpsDenom = java.math.BigInteger.valueOf(10_000L);
            commissionReversal = grossBig.multiply(java.math.BigInteger.valueOf(commBps)).divide(bpsDenom).longValueExact();
            platformFeeReversal = grossBig.multiply(java.math.BigInteger.valueOf(platBps)).divide(bpsDenom).longValueExact();
            reserveReversal = grossBig.multiply(java.math.BigInteger.valueOf(resBps)).divide(bpsDenom).longValueExact();

            long totalDeductions = Math.addExact(
                    Math.addExact(commissionReversal, platformFeeReversal),
                    Math.addExact(reserveReversal, taxReversal)
            );

            if (totalDeductions > gross) {
                throw new IllegalArgumentException("Total calculated reversals (" + totalDeductions
                        + ") exceed item gross (" + gross + ")");
            }

            merchantReversal = Math.subtractExact(gross, totalDeductions);
        }

        return new CalculatedItemRefund(
                item.itemReference(),
                item.merchantId(),
                gross,
                merchantReversal,
                commissionReversal,
                platformFeeReversal,
                reserveReversal,
                taxReversal,
                item.reason()
        );
    }
}
