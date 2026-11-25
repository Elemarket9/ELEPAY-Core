package com.elepay.enterprise.refund;

import org.springframework.stereotype.Component;
import com.elepay.common.ProportionalAllocation;

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

        boolean custom = item.customMerchantReversalPesewas() != null || item.customCommissionReversalPesewas() != null
                || item.customPlatformFeeReversalPesewas() != null || item.customReserveReversalPesewas() != null;
        if (custom && (item.customMerchantReversalPesewas() == null || item.customCommissionReversalPesewas() == null))
            throw new IllegalArgumentException("Custom refund requires merchant and commission reversals");
        if (custom) {
            commissionReversal = item.customCommissionReversalPesewas();
            platformFeeReversal = item.customPlatformFeeReversalPesewas() != null ? item.customPlatformFeeReversalPesewas() : 0L;
            reserveReversal = item.customReserveReversalPesewas() != null ? item.customReserveReversalPesewas() : 0L;
            merchantReversal = item.customMerchantReversalPesewas();

            long sum = Math.addExact(
                    Math.addExact(merchantReversal, commissionReversal),
                    Math.addExact(Math.addExact(platformFeeReversal, reserveReversal), taxReversal)
            );
            if (sum != gross) {
                throw new IllegalArgumentException("Custom refund breakdown (" + sum + ") does not equal gross (" + gross + ")");
            }
        } else {
            // Proportional integer math calculation
            int commBps = item.commissionBps() != null ? item.commissionBps() : 0;
            int platBps = item.platformFeeBps() != null ? item.platformFeeBps() : 0;
            int resBps = item.reserveBps() != null ? item.reserveBps() : 0;

            if (commBps < 0 || commBps > 10000 || platBps < 0 || platBps > 10000 || resBps < 0 || resBps > 10000) {
                throw new IllegalArgumentException("Basis points must be between 0 and 10000");
            }

            commissionReversal = ProportionalAllocation.basisPoints(gross, commBps);
            platformFeeReversal = ProportionalAllocation.basisPoints(gross, platBps);
            reserveReversal = ProportionalAllocation.basisPoints(gross, resBps);

            long totalDeductions = Math.addExact(
                    Math.addExact(commissionReversal, platformFeeReversal),
                    Math.addExact(reserveReversal, taxReversal)
            );

            if (totalDeductions > gross) {
                throw new IllegalArgumentException("Total calculated reversals (" + totalDeductions
                        + ") exceed item gross (" + gross + ")");
            }

            merchantReversal = gross - totalDeductions;
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
