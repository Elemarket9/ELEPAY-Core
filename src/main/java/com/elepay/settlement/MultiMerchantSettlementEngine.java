package com.elepay.settlement;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain 11 & 14: Multi-Merchant Settlement Engine.
 *
 * <p><strong>Authoritative Settlement Path Notice (P1 #7 Consolidation):</strong>
 * {@code com.elepay.checkout.CheckoutService} is the single authoritative runtime path for
 * multi-merchant checkout payments, delivery-gated settlement batches, deferred fee recognition,
 * and reserve releases (owning {@code checkout}, {@code checkout_allocation},
 * {@code settlement_batch}, {@code settlement_reserve_release}, outbox events, audit records,
 * and API idempotency).
 *
 * <p>This component provides:
 * <ul>
 *   <li>{@link #calculateSplit(long, List)} — a pure, side-effect-free split calculator and
 *       minor-unit conservation verifier.</li>
 *   <li>Deprecated low-level direct-ledger posting primitives retained only for isolated ledger
 *       tests and backwards compatibility. Production endpoints MUST route through
 *       {@code CheckoutService} rather than invoking these direct-ledger methods.</li>
 * </ul>
 */
@Service
public class MultiMerchantSettlementEngine {

    private final Ledger ledger;

    public MultiMerchantSettlementEngine(Ledger ledger) {
        this.ledger = ledger;
    }

    public record SettlementAllocation(
            UUID merchantId,
            String merchantName,
            String kind,
            long amount,
            long merchantFee,
            long platformCommission,
            long processingFee,
            long reserveAmount,
            String releaseCondition
    ) {
        public SettlementAllocation {
            if (merchantId == null) throw new IllegalArgumentException("Merchant ID is required");
            if (amount <= 0) throw new IllegalArgumentException("Allocation amount must be positive");
            if (merchantFee < 0 || platformCommission < 0 || processingFee < 0 || reserveAmount < 0) {
                throw new IllegalArgumentException("Fees and reserves cannot be negative");
            }
            long totalDeductions = Math.addExact(Math.addExact(merchantFee, platformCommission),
                    Math.addExact(processingFee, reserveAmount));
            if (totalDeductions > amount) {
                throw new IllegalArgumentException("Total deductions exceed allocation amount: "
                        + totalDeductions + " > " + amount);
            }
        }

        public long platformFee() {
            return Math.addExact(platformCommission, processingFee);
        }

        public long netPayable() {
            return amount - merchantFee - platformFee() - reserveAmount;
        }
    }

    public record SettlementSplit(
            long subtotal,
            long customerFee,
            long totalAmount,
            List<SettlementAllocation> allocations,
            long totalNetPayable,
            long totalPlatformFee,
            long totalMerchantFee,
            long totalReserve
    ) {}

    public record SettlementPostingResult(
            UUID journalTxId,
            String reference,
            long totalAmount,
            List<Leg> legs
    ) {}

    public record ReserveReleaseResult(
            UUID releaseId,
            UUID checkoutId,
            int line,
            UUID merchantId,
            long amountReleased,
            UUID journalTxId,
            String status
    ) {}

    public record FeeRecognitionResult(
            UUID journalTxId,
            long recognizedAmount
    ) {}

    public record BatchSettlementResult(
            UUID journalTxId,
            UUID merchantId,
            long amount
    ) {}

    /**
     * Calculates the deterministic split distribution across all merchant recipients.
     * Enforces integer minor unit arithmetic and exact sum preservation.
     */
    public SettlementSplit calculateSplit(long customerFee, List<SettlementAllocation> allocations) {
        if (allocations == null || allocations.isEmpty()) {
            throw new IllegalArgumentException("At least one allocation is required");
        }
        if (customerFee < 0) {
            throw new IllegalArgumentException("Customer fee cannot be negative");
        }

        long subtotal = 0;
        long totalNet = 0;
        long totalPlatform = 0;
        long totalMerchant = 0;
        long totalReserve = 0;

        for (SettlementAllocation a : allocations) {
            if (a == null) {
                throw new IllegalArgumentException("Allocation entry cannot be null");
            }
            subtotal = Math.addExact(subtotal, a.amount());
            totalNet = Math.addExact(totalNet, a.netPayable());
            totalPlatform = Math.addExact(totalPlatform, a.platformFee());
            totalMerchant = Math.addExact(totalMerchant, a.merchantFee());
            totalReserve = Math.addExact(totalReserve, a.reserveAmount());
        }

        long total = Math.addExact(subtotal, customerFee);
        long computedTotal = Math.addExact(Math.addExact(totalNet, totalPlatform),
                Math.addExact(Math.addExact(totalMerchant, totalReserve), customerFee));
        if (computedTotal != total) {
            throw new IllegalStateException("Split invariant failed: computed " + computedTotal + " != total " + total);
        }

        return new SettlementSplit(subtotal, customerFee, total, List.copyOf(allocations),
                totalNet, totalPlatform, totalMerchant, totalReserve);
    }

    /**
     * Executes atomic, balanced double-entry ledger posting for a multi-merchant checkout payment.
     *
     * <p>Debit: Customer Source (totalAmount)<br>
     * Credit: Each Merchant Pending (netPayable)<br>
     * Credit: Each Merchant Reserved (reserveAmount, if &gt; 0)<br>
     * Credit: Marketplace Collector Pending (totalPlatformFee, if &gt; 0)<br>
     * Credit: Platform DEFERRED_REVENUE (totalMerchantFee, if &gt; 0)<br>
     * Credit: Platform REVENUE (customerFee, if &gt; 0)
     *
     * @deprecated Production checkouts MUST use {@code com.elepay.checkout.CheckoutService}, which
     *     coordinates ledger posting with {@code checkout_allocation} state, outbox events, and audit logs.
     */
    @Deprecated(forRemoval = false)
    @Transactional
    public SettlementPostingResult postMultiMerchantPayment(
            UUID checkoutId,
            String reference,
            String method,
            String sourceAccount,
            long customerFee,
            List<SettlementAllocation> allocations,
            UUID collectorId
    ) {
        if (checkoutId == null) {
            throw new IllegalArgumentException("Checkout ID is required");
        }
        if (sourceAccount == null || sourceAccount.isBlank()) {
            throw new IllegalArgumentException("Source account is required");
        }
        SettlementSplit split = calculateSplit(customerFee, allocations);
        if (split.totalPlatformFee() > 0 && collectorId == null) {
            throw new IllegalArgumentException("Collector ID is required when platform fee is positive");
        }

        List<Leg> legs = new ArrayList<>();
        legs.add(Leg.debit(sourceAccount, split.totalAmount()));

        for (SettlementAllocation a : split.allocations()) {
            if (a.netPayable() > 0) {
                legs.add(Leg.credit(ledger.open("merchant", a.merchantId(), "pending", Ledger.GHS), a.netPayable()));
            }
            if (a.reserveAmount() > 0) {
                legs.add(Leg.credit(ledger.open("merchant", a.merchantId(), "reserved", Ledger.GHS), a.reserveAmount()));
            }
        }

        if (split.totalPlatformFee() > 0) {
            legs.add(Leg.credit(ledger.open("merchant", collectorId, "pending", Ledger.GHS), split.totalPlatformFee()));
        }
        if (split.totalMerchantFee() > 0) {
            legs.add(Leg.credit(ledger.openSystem(Ledger.DEFERRED_REVENUE, "deferred_revenue", Ledger.GHS, true),
                    split.totalMerchantFee()));
        }
        if (split.customerFee() > 0) {
            legs.add(Leg.credit(ledger.openSystem(Ledger.REVENUE, "revenue", Ledger.GHS, false), split.customerFee()));
        }

        var posted = ledger.post(Ledger.Posting.of("checkout-payment", checkoutId.toString(), "checkout_payment", legs)
                .describedAs(reference)
                .withMetadata(Map.of("checkout", String.valueOf(reference), "method", String.valueOf(method),
                        "splits", String.valueOf(allocations.size()))));

        return new SettlementPostingResult(posted.transactionId(), reference, split.totalAmount(), legs);
    }

    /**
     * Conditionally releases retained merchant reserves to available or pending balance.
     * Posts balanced double-entry transaction:
     * Debit: Merchant Reserved
     * Credit: Merchant Available (if line settled) or Merchant Pending (if not yet settled).
     *
     * @deprecated Production reserve releases MUST use {@code com.elepay.checkout.CheckoutService#releaseReserve},
     *     which enforces Idempotency-Key, updates {@code checkout_allocation}, and records
     *     {@code settlement_reserve_release}.
     */
    @Deprecated(forRemoval = false)
    @Transactional
    public ReserveReleaseResult releaseMerchantReserve(
            UUID checkoutId,
            int line,
            UUID merchantId,
            long amount,
            boolean lineSettled,
            String actor,
            String reason
    ) {
        return releaseMerchantReserve(checkoutId, line, merchantId, amount, lineSettled, actor, reason, null);
    }

    /**
     * Idempotent overload of {@link #releaseMerchantReserve(UUID, int, UUID, long, boolean, String, String)}.
     *
     * @deprecated Production reserve releases MUST use {@code com.elepay.checkout.CheckoutService#releaseReserve}.
     */
    @Deprecated(forRemoval = false)
    @Transactional
    public ReserveReleaseResult releaseMerchantReserve(
            UUID checkoutId,
            int line,
            UUID merchantId,
            long amount,
            boolean lineSettled,
            String actor,
            String reason,
            String idempotencyKey
    ) {
        if (checkoutId == null || merchantId == null) {
            throw ApiException.badRequest("invalid_request", "Checkout ID and merchant ID are required");
        }
        if (amount <= 0) {
            throw ApiException.badRequest("invalid_amount", "Release amount must be positive");
        }

        String reservedAccount = ledger.open("merchant", merchantId, "reserved", Ledger.GHS);
        String targetAccount = ledger.open("merchant", merchantId, lineSettled ? "available" : "pending", Ledger.GHS);

        UUID releaseId = UUID.randomUUID();
        String ledgerKey = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? Idempotency.sha256(checkoutId + ":" + line + ":" + merchantId + ":" + idempotencyKey)
                : releaseId.toString();
        List<Leg> legs = List.of(
                Leg.debit(reservedAccount, amount),
                Leg.credit(targetAccount, amount)
        );

        var posted = ledger.post(Ledger.Posting.of("reserve-release", ledgerKey, "reserve_release", legs)
                .describedAs("Reserve release line " + line + ": " + (reason != null ? reason : "Conditional release")));
        return new ReserveReleaseResult(releaseId, checkoutId, line, merchantId, amount, posted.transactionId(), "RELEASED");
    }

    /**
     * Recognizes platform merchant fees from deferred revenue to earned revenue upon line settlement.
     * Debit: DEFERRED_REVENUE
     * Credit: REVENUE
     *
     * @deprecated Production fee recognition MUST run via {@code com.elepay.checkout.CheckoutService#settleDue}.
     */
    @Deprecated(forRemoval = false)
    @Transactional
    public FeeRecognitionResult recognizeEarnedFees(UUID batchId, long amount, String reference) {
        if (amount <= 0) {
            return new FeeRecognitionResult(null, 0L);
        }
        if (batchId == null) {
            throw new IllegalArgumentException("Batch ID is required");
        }

        String deferredAcc = ledger.openSystem(Ledger.DEFERRED_REVENUE, "deferred_revenue", Ledger.GHS, true);
        String revenueAcc = ledger.openSystem(Ledger.REVENUE, "revenue", Ledger.GHS, false);
        var posted = ledger.post(Ledger.Posting.of("fee-recognition", batchId.toString(), "fee_recognition", List.of(
                Leg.debit(deferredAcc, amount),
                Leg.credit(revenueAcc, amount)
        )).describedAs("Fees earned on settled lines for batch " + reference));

        return new FeeRecognitionResult(posted.transactionId(), amount);
    }

    /**
     * Settles merchant funds from pending bucket to available bucket in a balanced journal entry.
     * Debit: Merchant Pending
     * Credit: Merchant Available
     *
     * @deprecated Production settlement batches MUST run via {@code com.elepay.checkout.CheckoutService#settleDue}.
     */
    @Deprecated(forRemoval = false)
    @Transactional
    public BatchSettlementResult settleMerchantFunds(UUID batchId, UUID merchantId, long amount, String reference) {
        if (amount <= 0) {
            return new BatchSettlementResult(null, merchantId, 0L);
        }
        if (batchId == null || merchantId == null) {
            throw new IllegalArgumentException("Batch ID and merchant ID are required");
        }

        String pendingAcc = ledger.open("merchant", merchantId, "pending", Ledger.GHS);
        String availableAcc = ledger.open("merchant", merchantId, "available", Ledger.GHS);
        var posted = ledger.post(Ledger.Posting.of("settlement", batchId.toString(), "settlement", List.of(
                Leg.debit(pendingAcc, amount),
                Leg.credit(availableAcc, amount)
        )).describedAs(reference));

        return new BatchSettlementResult(posted.transactionId(), merchantId, amount);
    }
}
