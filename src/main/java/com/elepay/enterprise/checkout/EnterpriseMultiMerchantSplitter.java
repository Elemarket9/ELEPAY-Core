package com.elepay.enterprise.checkout;

import com.elepay.common.ApiException;
import com.elepay.common.ProportionalAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Deterministic multi-tier split calculation engine for enterprise marketplaces.
 *
 * <p>Allocates externally collected checkout totals across:
 * <ol>
 *   <li>Sub-merchant net item proceeds (credited to merchant pending buckets)</li>
 *   <li>Delivery partner fulfillment fees (credited to delivery pending buckets)</li>
 *   <li>Marketplace category commissions and fixed processing fees</li>
 *   <li>Platform (ELEPAY) service charges and revenue recognition</li>
 *   <li>Risk reserve retentions (held in merchant reserved buckets)</li>
 * </ol>
 *
 * <p>Financial Guarantees:
 * <ul>
 *   <li>Strict conservation of money: {@code totalPesewas == sum(allocations)}.</li>
 *   <li>Pure integer minor-unit arithmetic throughout; no floating-point conversions.</li>
 *   <li>Deterministic largest-remainder (Hare-Niemeyer) distribution for fractional rounding.</li>
 * </ul>
 */
@Component
public class EnterpriseMultiMerchantSplitter {

    /**
     * Splits an enterprise checkout request deterministically across all participants.
     *
     * @param request the split specification containing order total, items, and delivery fees
     * @return the verified split result with exact zero-sum balance
     */
    public EnterpriseSplitResult split(EnterpriseSplitRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        if (request.items().isEmpty()) {
            throw ApiException.badRequest("empty_items", "Enterprise checkout must include at least one item allocation");
        }

        long sumItemGross = 0L;
        for (SubMerchantItemAllocation item : request.items()) {
            sumItemGross = Math.addExact(sumItemGross, item.grossAmountPesewas());
        }

        long sumDeliveryGross = 0L;
        for (DeliveryAllocation delivery : request.deliveryAllocations()) {
            sumDeliveryGross = Math.addExact(sumDeliveryGross, delivery.deliveryFeePesewas());
        }

        long totalOrderGross = Math.addExact(sumItemGross, sumDeliveryGross);
        if (totalOrderGross != request.totalPesewas()) {
            throw ApiException.badRequest("amount_mismatch",
                    "Total pesewas (" + request.totalPesewas() + ") does not equal sum of items ("
                            + sumItemGross + ") and delivery (" + sumDeliveryGross + ") = " + totalOrderGross);
        }

        List<CalculatedMerchantAllocation> merchantResults = new ArrayList<>(request.items().size());
        long totalMerchantNet = 0L;
        long totalMerchantCommission = 0L;
        long totalMerchantPlatformFee = 0L;
        long totalReserve = 0L;

        for (int i = 0; i < request.items().size(); i++) {
            SubMerchantItemAllocation item = request.items().get(i);
            long gross = item.grossAmountPesewas();

            // Marketplace commission: percentage bps + fixed fee with minimum floor
            long commission = 0L;
            if (item.commissionBps() != null && item.commissionBps() > 0) {
                commission = ProportionalAllocation.basisPoints(gross, item.commissionBps());
            }
            if (item.fixedCommissionPesewas() != null && item.fixedCommissionPesewas() > 0) {
                commission = Math.addExact(commission, item.fixedCommissionPesewas());
            }
            if (item.minCommissionPesewas() != null && commission < item.minCommissionPesewas()) {
                commission = item.minCommissionPesewas();
            }

            // Platform service fee (ELEPAY platform charge)
            long platformFee = 0L;
            if (item.platformFeeBps() != null && item.platformFeeBps() > 0) {
                platformFee = ProportionalAllocation.basisPoints(gross, item.platformFeeBps());
            }
            if (item.fixedPlatformFeePesewas() != null && item.fixedPlatformFeePesewas() > 0) {
                platformFee = Math.addExact(platformFee, item.fixedPlatformFeePesewas());
            }

            // Reserve retention
            long reserve = 0L;
            if (item.reserveBps() != null && item.reserveBps() > 0) {
                reserve = ProportionalAllocation.basisPoints(gross, item.reserveBps());
            }
            if (item.fixedReservePesewas() != null && item.fixedReservePesewas() > 0) {
                reserve = Math.addExact(reserve, item.fixedReservePesewas());
            }

            long totalDeductions = Math.addExact(Math.addExact(commission, platformFee), reserve);
            if (totalDeductions > gross) {
                throw ApiException.badRequest("deductions_exceed_gross",
                        "Line " + i + " deductions (" + totalDeductions + ") exceed gross item amount (" + gross + ")");
            }

            long netPayable = Math.subtractExact(gross, totalDeductions);

            CalculatedMerchantAllocation calc = new CalculatedMerchantAllocation(
                    i, item.merchantId(), item.merchantName(), item.category(), item.description(),
                    gross, commission, platformFee, reserve, netPayable, item.holdUntilDelivered()
            );

            merchantResults.add(calc);
            totalMerchantNet = Math.addExact(totalMerchantNet, netPayable);
            totalMerchantCommission = Math.addExact(totalMerchantCommission, commission);
            totalMerchantPlatformFee = Math.addExact(totalMerchantPlatformFee, platformFee);
            totalReserve = Math.addExact(totalReserve, reserve);
        }

        List<CalculatedDeliveryAllocation> deliveryResults = new ArrayList<>(request.deliveryAllocations().size());
        long totalDeliveryNet = 0L;
        long totalDeliveryCommission = 0L;
        long totalDeliveryPlatformFee = 0L;

        for (int i = 0; i < request.deliveryAllocations().size(); i++) {
            DeliveryAllocation delivery = request.deliveryAllocations().get(i);
            long gross = delivery.deliveryFeePesewas();

            long commission = 0L;
            if (delivery.marketplaceCommissionBps() != null && delivery.marketplaceCommissionBps() > 0) {
                commission = ProportionalAllocation.basisPoints(gross, delivery.marketplaceCommissionBps());
            }

            long platformFee = 0L;
            if (delivery.platformFeeBps() != null && delivery.platformFeeBps() > 0) {
                platformFee = ProportionalAllocation.basisPoints(gross, delivery.platformFeeBps());
            }

            long totalDeductions = Math.addExact(commission, platformFee);
            if (totalDeductions > gross) {
                throw ApiException.badRequest("delivery_deductions_exceed_fee",
                        "Delivery line " + i + " deductions (" + totalDeductions + ") exceed delivery fee (" + gross + ")");
            }

            long deliveryNet = Math.subtractExact(gross, totalDeductions);

            CalculatedDeliveryAllocation calc = new CalculatedDeliveryAllocation(
                    i, delivery.deliveryPartnerId(), delivery.deliveryPartnerName(),
                    delivery.description(), gross, commission, platformFee, deliveryNet
            );

            deliveryResults.add(calc);
            totalDeliveryNet = Math.addExact(totalDeliveryNet, deliveryNet);
            totalDeliveryCommission = Math.addExact(totalDeliveryCommission, commission);
            totalDeliveryPlatformFee = Math.addExact(totalDeliveryPlatformFee, platformFee);
        }

        long totalMarketplaceCommission = Math.addExact(totalMerchantCommission, totalDeliveryCommission);
        long totalPlatformRevenue = Math.addExact(totalMerchantPlatformFee, totalDeliveryPlatformFee);

        EnterpriseSplitResult result = new EnterpriseSplitResult(
                request.totalPesewas(),
                request.currency(),
                sumItemGross,
                totalMerchantNet,
                sumDeliveryGross,
                totalDeliveryNet,
                totalMarketplaceCommission,
                totalPlatformRevenue,
                totalReserve,
                merchantResults,
                deliveryResults
        );

        // Enforce non-negotiable financial conservation invariant
        result.validateConservation();

        return result;
    }

    /**
     * Distributes an integer monetary amount across proportional weights using the
     * deterministic Hare-Niemeyer (Largest Remainder) method.
     *
     * @param totalMinor total integer minor units (pesewas) to distribute
     * @param weights weights or item shares to distribute across
     * @return list of allocated integer units strictly summing to {@code totalMinor}
     */
    public List<Long> distributeProportionally(long totalMinor, List<Long> weights) {
        return ProportionalAllocation.allocate(totalMinor, weights);
    }

    /**
     * Proportionally distributes a target order-level commission across multiple items
     * using the Hare-Niemeyer (Largest Remainder) method to guarantee zero rounding drift.
     */
    public List<Long> distributeCommissionByLargestRemainder(long targetTotalCommission, List<Long> itemGrossAmounts) {
        return ProportionalAllocation.allocate(targetTotalCommission, itemGrossAmounts);
    }

    /**
     * Proportionally distributes an order-level discount across item gross amounts
     * without losing a single pesewa.
     */
    public List<Long> distributeDiscountByLargestRemainder(long totalDiscountPesewas, List<Long> itemGrossAmounts) {
        return ProportionalAllocation.allocate(totalDiscountPesewas, itemGrossAmounts);
    }
}
