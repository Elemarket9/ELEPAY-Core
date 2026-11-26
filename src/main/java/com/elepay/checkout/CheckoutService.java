package com.elepay.checkout;

import com.elepay.common.ApiException;
import com.elepay.merchant.MerchantAccess;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Checkout lifecycle facade: one customer payment allocated across merchants,
 * delivery partners and agreed fees, with per-allocation refunds, disputes and
 * settlement. All money moves through the ledger; this service delegates each
 * lifecycle stage to focused internal services while preserving the checkout
 * module's domain model and public API surface.
 *
 * <p>Marketplace checkouts follow a commission-on-delivery model: the marketplace's commission
 * grid (per category, with a minimum) and per-item processing fee are deducted
 * from each seller line, and every seller and delivery line is held until the
 * marketplace confirms delivery. Commission is therefore earned only on
 * delivered items. ELEPAY's merchant fee waits in deferred revenue and is
 * recognised only when the line settles (BoG Notice BG/GOV/SEC/2026/43).
 */
@Service
class CheckoutService {
    static final int MAX_ALLOCATIONS = 50;
    static final long MAX_TOTAL = 5_000_000_00L;
    static final Pattern CATEGORY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private final CheckoutStore store;
    private final CheckoutCreationService creation;
    private final CheckoutCompletionService completion;
    private final CheckoutRefundService refunds;
    private final CheckoutReserveService reserves;
    private final CheckoutDisputeService disputes;

    CheckoutService(CheckoutStore store,
                    CheckoutCreationService creation,
                    CheckoutCompletionService completion,
                    CheckoutRefundService refunds,
                    CheckoutReserveService reserves,
                    CheckoutDisputeService disputes) {
        this.store = store;
        this.creation = creation;
        this.completion = completion;
        this.refunds = refunds;
        this.reserves = reserves;
        this.disputes = disputes;
    }

    // ---- model ------------------------------------------------------------------------------

    record AllocationRequest(UUID merchantId, String kind, long amount, String description, String category,
                             Integer reserveBps, String releaseCondition) {
        AllocationRequest(UUID merchantId, String kind, long amount, String description, String category) {
            this(merchantId, kind, amount, description, category, null, null);
        }
        AllocationRequest(UUID merchantId, String kind, long amount, String description) {
            this(merchantId, kind, amount, description, null, null, null);
        }
    }

    record Allocation(int line, UUID merchantId, String merchantName, String kind, String description, long amount,
                      long merchantFee, long refunded, long refundedFee, long disputed, long pendingAmount,
                      long settledAmount, OffsetDateTime settledAt, String category, long commission,
                      long processingFee, long refundedPlatformFee, long disputedFee, long disputedPlatformFee,
                      long platformPending, long feeDeferred, boolean holdUntilDelivered, OffsetDateTime deliveredAt,
                      long reserveAmount, long reserveReleased, String releaseCondition, String releaseStatus) {
        Allocation(int line, UUID merchantId, String merchantName, String kind, String description, long amount,
                   long merchantFee, long refunded, long refundedFee, long disputed, long pendingAmount,
                   long settledAmount, OffsetDateTime settledAt, String category, long commission,
                   long processingFee, long refundedPlatformFee, long disputedFee, long disputedPlatformFee,
                   long platformPending, long feeDeferred, boolean holdUntilDelivered, OffsetDateTime deliveredAt) {
            this(line, merchantId, merchantName, kind, description, amount, merchantFee, refunded, refundedFee,
                    disputed, pendingAmount, settledAmount, settledAt, category, commission, processingFee,
                    refundedPlatformFee, disputedFee, disputedPlatformFee, platformPending, feeDeferred,
                    holdUntilDelivered, deliveredAt, 0L, 0L, "DELIVERY_CONFIRMED", holdUntilDelivered ? "HELD" : "RELEASED");
        }

        Allocation withMerchantName(String name) {
            return new Allocation(line, merchantId, name, kind, description, amount, merchantFee, refunded, refundedFee,
                    disputed, pendingAmount, settledAmount, settledAt, category, commission, processingFee,
                    refundedPlatformFee, disputedFee, disputedPlatformFee, platformPending, feeDeferred,
                    holdUntilDelivered, deliveredAt, reserveAmount, reserveReleased, releaseCondition, releaseStatus);
        }

        /** The marketplace's share: category commission plus processing fee. */
        long platformFee() { return commission + processingFee; }

        long net() { return amount - merchantFee - platformFee() - reserveAmount; }

        boolean awaitingDelivery() { return holdUntilDelivered && deliveredAt == null; }

        String status(String checkoutStatus) {
            if (!checkoutStatus.equals("paid")) return checkoutStatus;
            if (refunded == amount) return "refunded";
            if (disputed > 0) return "disputed";
            if (awaitingDelivery()) return refunded > 0 ? "partially_refunded" : "awaiting_delivery";
            if (settledAt != null && pendingAmount == 0 && platformPending == 0) {
                return refunded > 0 ? "partially_refunded_settled" : "settled";
            }
            return refunded > 0 ? "partially_refunded" : "pending_settlement";
        }
    }

    record Checkout(UUID id, String reference, UUID collectorId, String collectorName, UUID branchId, String status,
                    long subtotal, String description, String paymentMethod, long customerFee, Long totalPaid,
                    UUID customerId, UUID journalTx, OffsetDateTime expiresAt, OffsetDateTime paidAt,
                    OffsetDateTime createdAt, OffsetDateTime deliveryDeadline, List<Allocation> allocations) {}

    /** One row of a marketplace's commission grid. */
    record FeeRule(String category, int commissionBps, long commissionMin, long processingFee, int reserveBps) {
        FeeRule(String category, int commissionBps, long commissionMin, long processingFee) {
            this(category, commissionBps, commissionMin, processingFee, 0);
        }
        static final FeeRule NONE = new FeeRule("*", 0, 0, 0, 0);

        long commission(long amount) {
            return Math.max(Math.multiplyExact(amount, (long) commissionBps) / 10_000L, commissionMin);
        }

        long reserve(long amount) {
            return reserveBps > 0 ? Math.multiplyExact(amount, (long) reserveBps) / 10_000L : 0L;
        }
    }

    record PaymentResult(String reference, String status, String method, long subtotal, long fee, long total,
                         UUID attemptId, String railStatus, String redirectUrl, String message) {}

    /**
     * How an amount taken back from a line (refund or dispute hold) splits between the seller, ELEPAY's fee
     * and the marketplace's share, and which bucket each part comes from. Fees return in proportion; the
     * last part returns whatever remains, so the totals always close exactly.
     */
    record Take(long seller, long fee, long platform, long sellerFromPending, long sellerFromReserved,
                long feeFromDeferred, long platformFromPending) {
        long sellerFromAvailable() { return seller - sellerFromPending - sellerFromReserved; }
        long feeFromRevenue() { return fee - feeFromDeferred; }
        long platformFromAvailable() { return platform - platformFromPending; }
    }

    record Refund(UUID id, String reference, String checkout, int line, long amount, long feeReturned,
                  long platformFeeReturned, String destination, String status, UUID journalTx) {}

    record Dispute(UUID id, String checkout, int line, long amount, String reason, String status, UUID holdTx,
                   UUID resolutionTx, String refund) {}

    record SettlementBatch(UUID id, String reference, UUID merchantId, long amount, int lines, UUID journalTx,
                           OffsetDateTime createdAt) {}

    record FeeSchedule(UUID marketplaceId, int deliveryConfirmDays, int reserveBps, int version, List<FeeRule> rules) {
        FeeSchedule(UUID marketplaceId, int deliveryConfirmDays, List<FeeRule> rules) {
            this(marketplaceId, deliveryConfirmDays, 0, 1, rules);
        }
    }

    record ReserveRelease(UUID id, UUID checkoutId, int line, UUID merchantId, long amount, UUID journalTx, String status) {}

    static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) return null;
        String c = category.trim().toLowerCase(Locale.ROOT);
        if (!CATEGORY.matcher(c).matches()) {
            throw ApiException.badRequest("invalid_category", "Category must be lowercase letters, digits, - or _");
        }
        return c;
    }

    static Take take(Allocation a, long amount) {
        long cumTaken = a.refunded() + a.disputed() + amount;
        boolean last = cumTaken == a.amount();
        long feeLeft = a.merchantFee() - a.refundedFee() - a.disputedFee();
        long platformLeft = a.platformFee() - a.refundedPlatformFee() - a.disputedPlatformFee();
        long targetFee = last ? a.merchantFee() : Math.multiplyExact(a.merchantFee(), cumTaken) / a.amount();
        long targetPlatform = last ? a.platformFee() : Math.multiplyExact(a.platformFee(), cumTaken) / a.amount();
        long fee = Math.min(feeLeft, Math.max(0L, targetFee - a.refundedFee() - a.disputedFee()));
        long platform = Math.min(platformLeft, Math.max(0L, targetPlatform - a.refundedPlatformFee() - a.disputedPlatformFee()));
        fee = Math.min(fee, amount);
        platform = Math.min(platform, amount - fee);
        long seller = amount - fee - platform;
        long sellerFromPending = Math.min(a.pendingAmount(), seller);
        long remainingSeller = seller - sellerFromPending;
        long availableReserve = Math.max(0L, a.reserveAmount() - a.reserveReleased());
        long sellerFromReserved = Math.min(availableReserve, remainingSeller);
        return new Take(seller, fee, platform, sellerFromPending, sellerFromReserved,
                Math.min(a.feeDeferred(), fee), Math.min(a.platformPending(), platform));
    }

    // ---- persistence & lookups --------------------------------------------------------------

    Checkout load(String reference, boolean lock) {
        return store.load(reference, lock);
    }

    Checkout load(UUID id, boolean lock) {
        return store.load(id, lock);
    }

    FeeRule feeRule(UUID marketplace, String category) {
        return creation.feeRule(marketplace, category);
    }

    int deliveryConfirmDays(UUID marketplace) {
        return store.deliveryConfirmDays(marketplace);
    }

    long customerFee(String method, long subtotal) {
        return creation.customerFee(method, subtotal);
    }

    // ---- creation & cancellation ------------------------------------------------------------

    Checkout create(UUID collectorId, UUID branchId, UUID paymentLink, List<AllocationRequest> requested,
                    String description, int expiresInMinutes, String createdBy) {
        return creation.create(collectorId, branchId, paymentLink, requested, description, expiresInMinutes, createdBy);
    }

    Checkout create(UUID collectorId, UUID branchId, UUID paymentLink, List<AllocationRequest> requested,
                    String description, int expiresInMinutes, String createdBy, String orderReference) {
        return creation.create(collectorId, branchId, paymentLink, requested, description, expiresInMinutes, createdBy, orderReference);
    }

    Checkout cancel(UUID collector, UUID branch, String reference, String actor) {
        return creation.cancel(collector, branch, reference, actor);
    }

    // ---- payment & completion ---------------------------------------------------------------

    PaymentResult payWithWallet(String reference, UUID customer) {
        return completion.payWithWallet(reference, customer);
    }

    PaymentResult startRailPayment(String reference, String method, String msisdn, String network, String email,
                                   String returnUrl) {
        return completion.startRailPayment(reference, method, msisdn, network, email, returnUrl);
    }

    void resetProcessing(UUID merchantId, String reference) {
        completion.resetProcessing(merchantId, reference);
    }

    UUID railTxOf(UUID attempt) {
        return completion.railTxOf(attempt);
    }

    PaymentResult status(String reference) {
        return completion.status(reference);
    }

    void noteChannel(String reference, String channel) {
        completion.noteChannel(reference, channel);
    }

    // ---- refunds ----------------------------------------------------------------------------

    Refund refund(MerchantAccess.Access access, String reference, int line, long amount, String reason) {
        return refunds.refund(access, reference, line, amount, reason);
    }

    Refund refund(MerchantAccess.Access access, String reference, int line, long amount, String reason, String idempotencyKey) {
        return refunds.refund(access, reference, line, amount, reason, idempotencyKey);
    }

    UUID pendingRefundRailTx(UUID refundId) {
        return refunds.pendingRefundRailTx(refundId);
    }

    // ---- delivery, settlement & reserves ----------------------------------------------------

    Checkout confirmDelivery(UUID collector, String reference, List<Integer> lines, String actor) {
        return reserves.confirmDelivery(collector, reference, lines, actor);
    }

    List<SettlementBatch> settleDue(boolean force, UUID onlyMerchant) {
        return reserves.settleDue(force, onlyMerchant);
    }

    List<SettlementBatch> settleDue(boolean force, UUID onlyMerchant, UUID onlyCheckout) {
        return reserves.settleDue(force, onlyMerchant, onlyCheckout);
    }

    SettlementBatch settlementBatch(UUID id) {
        return reserves.settlementBatch(id);
    }

    ReserveRelease releaseReserve(UUID merchantId, String reference, int line, long amount, String reason, String actor) {
        return reserves.releaseReserve(merchantId, reference, line, amount, reason, actor);
    }

    ReserveRelease releaseReserve(UUID merchantId, String reference, int line, long amount, String reason, String actor, String idempotencyKey) {
        return reserves.releaseReserve(merchantId, reference, line, amount, reason, actor, idempotencyKey);
    }

    // ---- disputes ---------------------------------------------------------------------------

    Dispute openDispute(String actor, String reference, int line, long amount, String reason, String providerCase) {
        return disputes.openDispute(actor, reference, line, amount, reason, providerCase);
    }

    Dispute resolveDispute(String actor, UUID id, String outcome, String note) {
        return disputes.resolveDispute(actor, id, outcome, note);
    }

    Optional<UUID> disputeRefundRailTx(UUID disputeId) {
        return disputes.disputeRefundRailTx(disputeId);
    }

    Dispute dispute(UUID id) {
        return disputes.dispute(id);
    }

    // ---- marketplace fee schedule -----------------------------------------------------------

    FeeSchedule feeSchedule(UUID marketplace) {
        return creation.feeSchedule(marketplace);
    }

    FeeSchedule saveFeeSchedule(UUID marketplace, int deliveryConfirmDays, int reserveBps, List<FeeRule> rules, String reason, String actor) {
        return creation.saveFeeSchedule(marketplace, deliveryConfirmDays, reserveBps, rules, reason, actor);
    }

    FeeSchedule saveFeeSchedule(UUID marketplace, int deliveryConfirmDays, List<FeeRule> rules, String actor) {
        return creation.saveFeeSchedule(marketplace, deliveryConfirmDays, rules, actor);
    }
}
