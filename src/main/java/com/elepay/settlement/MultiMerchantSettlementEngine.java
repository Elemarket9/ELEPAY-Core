package com.elepay.settlement;

import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain 11 & 14: Multi-Merchant Settlement Engine.
 * Orchestrates multi-merchant split payments, platform service charges,
 * merchant reserve retentions, conditional fund releases, and fee recognition.
 * Enforces strictly balanced double-entry transactions in the authoritative ledger.
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
     * Debit: Customer Source (totalAmount)
     * Credit: Each Merchant Pending (netPayable)
     * Credit: Each Merchant Reserved (reserveAmount, if > 0)
     * Credit: Marketplace Collector Pending (totalPlatformFee, if > 0)
     * Credit: Platform DEFERRED_REVENUE (totalMerchantFee, if > 0)
     * Credit: Platform REVENUE (customerFee, if > 0)
     */
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
        SettlementSplit split = calculateSplit(customerFee, allocations);

        List<Leg> legs = new ArrayList<>();
        legs.add(Leg.debit(sourceAccount, split.totalAmount()));

        for (SettlementAllocation a : split.allocations()) {
            if (a.netPayable() > 0) {
                legs.add(Leg.credit(Ledger.code("merchant", a.merchantId(), "pending", Ledger.GHS), a.netPayable()));
            }
            if (a.reserveAmount() > 0) {
                legs.add(Leg.credit(Ledger.code("merchant", a.merchantId(), "reserved", Ledger.GHS), a.reserveAmount()));
            }
        }

        if (split.totalPlatformFee() > 0) {
            legs.add(Leg.credit(Ledger.code("merchant", collectorId, "pending", Ledger.GHS), split.totalPlatformFee()));
        }
        if (split.totalMerchantFee() > 0) {
            legs.add(Leg.credit(Ledger.DEFERRED_REVENUE, split.totalMerchantFee()));
        }
        if (split.customerFee() > 0) {
            legs.add(Leg.credit(Ledger.REVENUE, split.customerFee()));
        }

        var posted = ledger.post(Ledger.Posting.of("checkout-payment", checkoutId.toString(), "checkout_payment", legs)
                .describedAs(reference)
                .withMetadata(Map.of("checkout", reference, "method", method, "splits", String.valueOf(allocations.size()))));

        return new SettlementPostingResult(posted.transactionId(), reference, split.totalAmount(), legs);
    }

    /**
     * Conditionally releases retained merchant reserves to available or pending balance.
     * Posts balanced double-entry transaction:
     * Debit: Merchant Reserved
     * Credit: Merchant Available (if line settled) or Merchant Pending (if not yet settled).
     */
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
        if (amount <= 0) {
            throw ApiException.badRequest("invalid_amount", "Release amount must be positive");
        }

        String reservedAccount = Ledger.code("merchant", merchantId, "reserved", Ledger.GHS);
        String targetAccount = Ledger.code("merchant", merchantId, lineSettled ? "available" : "pending", Ledger.GHS);

        UUID releaseId = UUID.randomUUID();
        List<Leg> legs = List.of(
                Leg.debit(reservedAccount, amount),
                Leg.credit(targetAccount, amount)
        );

        var posted = ledger.post(Ledger.Posting.of("reserve-release", releaseId.toString(), "reserve_release", legs)
                .describedAs("Reserve release line " + line + ": " + (reason != null ? reason : "Conditional release")));
        return new ReserveReleaseResult(releaseId, checkoutId, line, merchantId, amount, posted.transactionId(), "RELEASED");
    }

    /**
     * Recognizes platform merchant fees from deferred revenue to earned revenue upon line settlement.
     * Debit: DEFERRED_REVENUE
     * Credit: REVENUE
     */
    @Transactional
    public FeeRecognitionResult recognizeEarnedFees(UUID batchId, long amount, String reference) {
        if (amount <= 0) {
            return new FeeRecognitionResult(null, 0L);
        }

        var posted = ledger.post(Ledger.Posting.of("fee-recognition", batchId.toString(), "fee_recognition", List.of(
                Leg.debit(Ledger.DEFERRED_REVENUE, amount),
                Leg.credit(Ledger.REVENUE, amount)
        )).describedAs("Fees earned on settled lines for batch " + reference));

        return new FeeRecognitionResult(posted.transactionId(), amount);
    }

    /**
     * Settles merchant funds from pending bucket to available bucket in a balanced journal entry.
     * Debit: Merchant Pending
     * Credit: Merchant Available
     */
    @Transactional
    public BatchSettlementResult settleMerchantFunds(UUID batchId, UUID merchantId, long amount, String reference) {
        if (amount <= 0) {
            return new BatchSettlementResult(null, merchantId, 0L);
        }

        var posted = ledger.post(Ledger.Posting.of("settlement", batchId.toString(), "settlement", List.of(
                Leg.debit(Ledger.code("merchant", merchantId, "pending", Ledger.GHS), amount),
                Leg.credit(Ledger.code("merchant", merchantId, "available", Ledger.GHS), amount)
        )).describedAs(reference));

        return new BatchSettlementResult(posted.transactionId(), merchantId, amount);
    }
}
