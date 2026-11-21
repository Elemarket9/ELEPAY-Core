package com.elepay.checkout;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.OpsQueue;
import com.elepay.common.References;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import com.elepay.merchant.MerchantAccess;
import com.elepay.outbox.Outbox;
import com.elepay.rails.RailEvents;
import com.elepay.rails.RailTransactions;
import com.elepay.wallet.CustomerDirectory;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checkout lifecycle: one customer payment allocated across merchants,
 * delivery partners and agreed fees, with per-allocation refunds, disputes and
 * settlement. All money moves through the ledger; this class keeps the
 * per-allocation state that explains each posting.
 */
@Service
class CheckoutService {
    static final int MAX_ALLOCATIONS = 50;
    static final long MAX_TOTAL = 5_000_000_00L;

    private final JdbcClient db;
    private final Ledger ledger;
    private final MerchantAccess merchants;
    private final RailTransactions rails;
    private final Outbox outbox;
    private final Audit audit;
    private final OpsQueue ops;
    private final Map<String, int[]> customerFees;
    private final boolean jobsEnabled;

    CheckoutService(JdbcClient db, Ledger ledger, MerchantAccess merchants, RailTransactions rails, Outbox outbox,
                    Audit audit, OpsQueue ops,
                    @Value("${elepay.fees.customer.wallet-bps:0}") int walletBps,
                    @Value("${elepay.fees.customer.wallet-fixed:0}") int walletFixed,
                    @Value("${elepay.fees.customer.momo-bps:0}") int momoBps,
                    @Value("${elepay.fees.customer.momo-fixed:0}") int momoFixed,
                    @Value("${elepay.fees.customer.card-bps:0}") int cardBps,
                    @Value("${elepay.fees.customer.card-fixed:0}") int cardFixed,
                    @Value("${elepay.checkout.jobs-enabled:true}") boolean jobsEnabled) {
        this.db = db;
        this.ledger = ledger;
        this.merchants = merchants;
        this.rails = rails;
        this.outbox = outbox;
        this.audit = audit;
        this.ops = ops;
        this.customerFees = Map.of("wallet", new int[]{walletBps, walletFixed},
                "momo", new int[]{momoBps, momoFixed}, "card", new int[]{cardBps, cardFixed});
        this.jobsEnabled = jobsEnabled;
    }

    // ---- model ------------------------------------------------------------------------------

    record AllocationRequest(UUID merchantId, String kind, long amount, String description) {}

    record Allocation(int line, UUID merchantId, String merchantName, String kind, String description, long amount,
                      long merchantFee, long refunded, long refundedFee, long disputed, long pendingAmount,
                      long settledAmount, OffsetDateTime settledAt) {
        long net() { return amount - merchantFee; }

        String status(String checkoutStatus) {
            if (!checkoutStatus.equals("paid")) return checkoutStatus;
            if (refunded == amount) return "refunded";
            if (disputed > 0) return "disputed";
            if (settledAt != null && pendingAmount == 0) return refunded > 0 ? "partially_refunded_settled" : "settled";
            return refunded > 0 ? "partially_refunded" : "pending_settlement";
        }
    }

    record Checkout(UUID id, String reference, UUID collectorId, String collectorName, UUID branchId, String status,
                    long subtotal, String description, String paymentMethod, long customerFee, Long totalPaid,
                    UUID customerId, UUID journalTx, OffsetDateTime expiresAt, OffsetDateTime paidAt,
                    OffsetDateTime createdAt, List<Allocation> allocations) {}

    Checkout load(String reference, boolean lock) {
        UUID id = db.sql("SELECT id FROM checkout WHERE reference = :r" + (lock ? " FOR UPDATE" : ""))
                .param("r", reference).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Checkout not found"));
        return load(id, lock);
    }

    Checkout load(UUID id, boolean lock) {
        var head = db.sql("""
                SELECT c.id, c.reference, c.collector_id, m.trading_name, c.branch_id, c.status, c.subtotal, c.description,
                       c.payment_method, c.customer_fee, c.total_paid, c.customer_id, c.journal_tx, c.expires_at,
                       c.paid_at, c.created_at
                FROM checkout c JOIN merchant m ON m.id = c.collector_id WHERE c.id = :id""" + (lock ? " FOR UPDATE OF c" : ""))
                .param("id", id).query((rs, i) -> new Object[]{
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getLong(7), rs.getString(8), rs.getString(9),
                        rs.getLong(10), (Long) rs.getObject(11), rs.getObject(12, UUID.class), rs.getObject(13, UUID.class),
                        rs.getObject(14, OffsetDateTime.class), rs.getObject(15, OffsetDateTime.class),
                        rs.getObject(16, OffsetDateTime.class)})
                .optional().orElseThrow(() -> ApiException.notFound("Checkout not found"));
        List<Allocation> lines = db.sql("""
                SELECT a.line, a.merchant_id, m.trading_name, a.kind, a.description, a.amount, a.merchant_fee, a.refunded,
                       a.refunded_fee, a.disputed, a.pending_amount, a.settled_amount, a.settled_at
                FROM checkout_allocation a JOIN merchant m ON m.id = a.merchant_id
                WHERE a.checkout_id = :id ORDER BY a.line""" + (lock ? " FOR UPDATE OF a" : ""))
                .param("id", id).query((rs, i) -> new Allocation(rs.getInt(1), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                        rs.getLong(9), rs.getLong(10), rs.getLong(11), rs.getLong(12),
                        rs.getObject(13, OffsetDateTime.class))).list();
        return new Checkout((UUID) head[0], (String) head[1], (UUID) head[2], (String) head[3], (UUID) head[4],
                (String) head[5], (long) head[6], (String) head[7], (String) head[8], (long) head[9], (Long) head[10],
                (UUID) head[11], (UUID) head[12], (OffsetDateTime) head[13], (OffsetDateTime) head[14],
                (OffsetDateTime) head[15], lines);
    }

    long customerFee(String method, long subtotal) {
        int[] f = customerFees.get(method);
        if (f == null) throw ApiException.badRequest("invalid_method", "Choose wallet, momo or card");
        return Math.addExact(Math.multiplyExact(subtotal, f[0]) / 10_000, f[1]);
    }

    // ---- creation ---------------------------------------------------------------------------

    @Transactional
    Checkout create(UUID collectorId, UUID branchId, UUID paymentLink, List<AllocationRequest> requested,
                    String description, int expiresInMinutes, String createdBy) {
        return create(collectorId, branchId, paymentLink, requested, description, expiresInMinutes, createdBy, null);
    }

    @Transactional
    Checkout create(UUID collectorId, UUID branchId, UUID paymentLink, List<AllocationRequest> requested,
                    String description, int expiresInMinutes, String createdBy, String orderReference) {
        if (orderReference != null && db.sql("SELECT count(*) FROM checkout WHERE collector_id = :m AND order_reference = :o")
                .param("m", collectorId).param("o", orderReference).query(Long.class).single() > 0) {
            throw ApiException.conflict("order_exists", "A payment order already exists for this order reference");
        }
        var collector = merchants.find(collectorId).orElseThrow();
        if (!collector.status().equals("active")) throw ApiException.conflict("merchant_not_active", "Merchant is not active");
        if (requested == null || requested.isEmpty() || requested.size() > MAX_ALLOCATIONS) {
            throw ApiException.badRequest("invalid_allocations", "A checkout needs 1 to " + MAX_ALLOCATIONS + " allocations");
        }
        if (expiresInMinutes < 1 || expiresInMinutes > 7 * 24 * 60) {
            throw ApiException.badRequest("invalid_expiry", "Expiry must be between 1 minute and 7 days");
        }
        long subtotal = 0;
        List<long[]> fees = new ArrayList<>();
        for (AllocationRequest a : requested) {
            if (a == null || a.merchantId() == null || a.amount() <= 0) {
                throw ApiException.badRequest("invalid_allocation", "Each allocation needs a merchant and a positive amount");
            }
            String kind = a.kind() == null ? "sale" : a.kind();
            if (!Set.of("sale", "delivery").contains(kind)) throw ApiException.badRequest("invalid_kind", "Use sale or delivery");
            var recipient = merchants.find(a.merchantId())
                    .orElseThrow(() -> ApiException.badRequest("unknown_merchant", "Unknown allocation merchant"));
            if (!recipient.status().equals("active")) {
                throw ApiException.conflict("merchant_not_active", recipient.tradingName() + " cannot receive payments");
            }
            if (!recipient.id().equals(collectorId)) {
                if (!collector.kind().equals("marketplace") || !merchants.isActiveSeller(collectorId, recipient.id())) {
                    throw ApiException.forbidden("Allocations to other merchants require an active marketplace relationship");
                }
            }
            if (kind.equals("delivery") != recipient.kind().equals("delivery")) {
                throw ApiException.badRequest("invalid_kind", "Delivery allocations go to delivery partners only");
            }
            subtotal = Math.addExact(subtotal, a.amount());
            fees.add(new long[]{MerchantAccess.fee(recipient, a.amount())});
        }
        if (subtotal > MAX_TOTAL) throw ApiException.badRequest("amount_too_large", "Checkout total exceeds the limit");
        UUID id = UUID.randomUUID();
        String reference = References.next("EP");
        db.sql("""
                INSERT INTO checkout (id, reference, collector_id, branch_id, payment_link_id, subtotal, description,
                    expires_at, created_by, order_reference)
                VALUES (:id, :ref, :collector, :branch, :link, :subtotal, :description,
                    now() + make_interval(mins => :mins), :by, :order)""")
                .param("id", id).param("ref", reference).param("collector", collectorId).param("branch", branchId)
                .param("link", paymentLink).param("subtotal", subtotal).param("description", description)
                .param("mins", expiresInMinutes).param("by", createdBy).param("order", orderReference).update();
        for (int line = 0; line < requested.size(); line++) {
            AllocationRequest a = requested.get(line);
            db.sql("""
                    INSERT INTO checkout_allocation (checkout_id, line, merchant_id, kind, description, amount, merchant_fee)
                    VALUES (:id, :line, :m, :kind, :d, :amount, :fee)""")
                    .param("id", id).param("line", line).param("m", a.merchantId())
                    .param("kind", a.kind() == null ? "sale" : a.kind()).param("d", a.description())
                    .param("amount", a.amount()).param("fee", fees.get(line)[0]).update();
        }
        return load(id, false);
    }

    @Transactional
    Checkout cancel(UUID collector, UUID branch, String reference, String actor) {
        Checkout c = load(reference, true);
        if (!c.collectorId().equals(collector) || (branch != null && !branch.equals(c.branchId()))) {
            throw ApiException.notFound("Checkout not found");
        }
        if (c.status().equals("cancelled")) return c;
        if (!c.status().equals("open") && !c.status().equals("expired")) {
            throw ApiException.conflict("not_cancellable", "A " + c.status() + " checkout cannot be cancelled");
        }
        db.sql("UPDATE checkout SET status = 'cancelled' WHERE id = :id").param("id", c.id()).update();
        audit.record(actor, "checkout.cancelled", "checkout:" + reference, Map.of());
        outbox.publish("checkout", reference, "checkout.cancelled", Map.of("reference", reference), recipients(c));
        return load(c.id(), false);
    }

    // ---- payment ----------------------------------------------------------------------------

    private Checkout lockPayable(String reference) {
        Checkout c = load(reference, true);
        if (c.status().equals("paid")) throw ApiException.conflict("already_paid", "This checkout is already paid");
        if (!Set.of("open", "processing").contains(c.status())) {
            throw ApiException.conflict("checkout_" + c.status(), "This checkout is " + c.status());
        }
        if (c.status().equals("open") && !c.expiresAt().isAfter(OffsetDateTime.now())) {
            db.sql("UPDATE checkout SET status = 'expired' WHERE id = :id").param("id", c.id()).update();
            throw ApiException.conflict("checkout_expired", "This checkout has expired");
        }
        return c;
    }

    record PaymentResult(String reference, String status, String method, long subtotal, long fee, long total,
                         UUID attemptId, String railStatus, String redirectUrl, String message) {}

    @Transactional
    PaymentResult payWithWallet(String reference, UUID customer) {
        Checkout c = lockPayable(reference);
        if (c.status().equals("processing")) {
            throw ApiException.conflict("payment_in_progress", "Another payment for this checkout is in progress");
        }
        long fee = customerFee("wallet", c.subtotal());
        long total = Math.addExact(c.subtotal(), fee);
        UUID attempt = UUID.randomUUID();
        db.sql("INSERT INTO payment_attempt (id, checkout_id, method, status) VALUES (:id, :c, 'wallet', 'succeeded')")
                .param("id", attempt).param("c", c.id()).update();
        complete(c, "wallet", CustomerDirectory.available(customer), customer, fee, total);
        db.sql("""
                INSERT INTO wallet_operation (id, reference, customer_id, kind, amount, status, counterparty, journal_tx, related_id)
                VALUES (:id, :ref, :cust, 'payment', :amount, 'completed', :cp, (SELECT journal_tx FROM checkout WHERE id = :c), :c)""")
                .param("id", UUID.randomUUID()).param("ref", References.next("WLT")).param("cust", customer)
                .param("amount", total).param("cp", c.collectorName() + " " + c.reference()).param("c", c.id()).update();
        return new PaymentResult(c.reference(), "paid", "wallet", c.subtotal(), fee, total, attempt, null, null, null);
    }

    /** Records a rail charge for the checkout, or returns the attempt already in flight. */
    @Transactional
    PaymentResult startRailPayment(String reference, String method, String msisdn, String network, String email,
                                   String returnUrl) {
        Checkout current = load(reference, true);
        if (current.status().equals("paid")) return status(reference);
        var inFlight = db.sql("SELECT id FROM payment_attempt WHERE checkout_id = :c AND status = 'pending'")
                .param("c", current.id()).query(UUID.class).optional();
        if (inFlight.isPresent()) return status(reference);
        Checkout c = lockPayable(reference);
        long fee = customerFee(method, c.subtotal());
        long total = Math.addExact(c.subtotal(), fee);
        UUID attempt = UUID.randomUUID();
        var tx = rails.create(rails.defaultRail(), "charge", "checkout", attempt, total,
                new RailTransactions.Request(method, msisdn, network, email, null,
                        c.collectorName() + " " + c.reference(), returnUrl, null, null, null, null, null));
        db.sql("INSERT INTO payment_attempt (id, checkout_id, method, rail_tx, status) VALUES (:id, :c, :m, :tx, 'pending')")
                .param("id", attempt).param("c", c.id()).param("m", method).param("tx", tx.id()).update();
        db.sql("UPDATE checkout SET status = 'processing', payment_method = :m, customer_fee = :fee WHERE id = :id")
                .param("m", method).param("fee", fee).param("id", c.id()).update();
        return new PaymentResult(c.reference(), "processing", method, c.subtotal(), fee, total, attempt, "created", null,
                "Approve the payment on your phone");
    }

    UUID railTxOf(UUID attempt) {
        return db.sql("SELECT rail_tx FROM payment_attempt WHERE id = :id").param("id", attempt).query(UUID.class).single();
    }

    PaymentResult status(String reference) {
        Checkout c = load(reference, false);
        record Attempt(UUID id, String method, String status, UUID railTx) {}
        var attempt = db.sql("""
                SELECT id, method, status, rail_tx FROM payment_attempt WHERE checkout_id = :c
                ORDER BY created_at DESC LIMIT 1""").param("c", c.id())
                .query((rs, i) -> new Attempt(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class))).optional();
        String railStatus = null;
        String redirect = null;
        if (attempt.isPresent() && attempt.get().railTx() != null) {
            var tx = rails.find(attempt.get().railTx()).orElseThrow();
            railStatus = tx.status();
            redirect = tx.status().equals("pending") ? tx.redirectUrl() : null;
        }
        String method = c.paymentMethod() != null ? c.paymentMethod() : attempt.map(Attempt::method).orElse(null);
        long fee = method == null ? 0 : (c.totalPaid() != null ? c.customerFee() : customerFee(method, c.subtotal()));
        String message = switch (c.status()) {
            case "paid" -> "Payment received";
            case "processing" -> "Waiting for payment confirmation. You can safely reopen this link with reference " + c.reference();
            case "expired" -> "This checkout expired before payment";
            default -> attempt.filter(a -> a.status().equals("failed")).isPresent()
                    ? "The last payment attempt failed. You can try again." : "Awaiting payment";
        };
        return new PaymentResult(c.reference(), c.status(), method, c.subtotal(), fee, c.subtotal() + fee,
                attempt.map(Attempt::id).orElse(null), railStatus, redirect, message);
    }

    /** Posts the payment: source debited once, each allocation's net to the merchant's pending bucket. */
    private void complete(Checkout c, String method, String source, UUID customer, long customerFee, long total) {
        List<Leg> legs = new ArrayList<>();
        legs.add(Leg.debit(source, total));
        long revenue = customerFee;
        for (Allocation a : c.allocations()) {
            if (a.net() > 0) legs.add(Leg.credit(Ledger.code("merchant", a.merchantId(), "pending", Ledger.GHS), a.net()));
            revenue += a.merchantFee();
        }
        if (revenue > 0) legs.add(Leg.credit(Ledger.REVENUE, revenue));
        var posted = ledger.post(Ledger.Posting.of("checkout-payment", c.id().toString(), "checkout_payment", legs)
                .describedAs(c.reference()).withMetadata(Map.of("checkout", c.reference(), "method", method)));
        for (Allocation a : c.allocations()) {
            db.sql("UPDATE checkout_allocation SET pending_amount = :p WHERE checkout_id = :c AND line = :l")
                    .param("p", a.net()).param("c", c.id()).param("l", a.line()).update();
        }
        db.sql("""
                UPDATE checkout SET status = 'paid', payment_method = :m, customer_id = :cust, customer_fee = :fee,
                       total_paid = :total, journal_tx = :tx, paid_at = now() WHERE id = :id""")
                .param("m", method).param("cust", customer).param("fee", customerFee).param("total", total)
                .param("tx", posted.transactionId()).param("id", c.id()).update();
        outbox.publish("checkout", c.reference(), "checkout.paid", Map.of(
                "reference", c.reference(), "method", method, "total", total,
                "allocations", c.allocations().stream().map(a -> Map.of("line", a.line(),
                        "merchantId", a.merchantId().toString(), "amount", a.amount())).toList()), recipients(c));
        audit.record("system", "checkout.paid", "checkout:" + c.reference(), Map.of("method", method, "total", total));
    }

    private static Set<UUID> recipients(Checkout c) {
        Set<UUID> ids = new LinkedHashSet<>();
        ids.add(c.collectorId());
        c.allocations().forEach(a -> ids.add(a.merchantId()));
        return ids;
    }

    @EventListener
    void onRailSucceeded(RailEvents.Succeeded event) {
        if (!event.purpose().equals("checkout")) return;
        UUID checkoutId = db.sql("SELECT checkout_id FROM payment_attempt WHERE id = :id")
                .param("id", event.purposeId()).query(UUID.class).single();
        Checkout c = load(checkoutId, true);
        db.sql("UPDATE payment_attempt SET status = 'succeeded' WHERE id = :id").param("id", event.purposeId()).update();
        if (c.status().equals("paid")) {
            // Defensive: the unique active-attempt index makes this unreachable; never credit twice.
            ops.raise("checkout_double_payment", "high", "checkout:" + c.reference(), Map.of("railTx", event.railTxId().toString()));
            ledger.post(Ledger.Posting.of("checkout-overpayment", event.railTxId().toString(), "suspense", List.of(
                    Leg.debit(Ledger.clearing(event.rail()), event.amount()), Leg.credit(Ledger.SUSPENSE, event.amount()))));
            return;
        }
        complete(c, c.paymentMethod(), Ledger.clearing(event.rail()), null, c.customerFee(), event.amount());
    }

    @EventListener
    void onRailFailed(RailEvents.Failed event) {
        if (!event.purpose().equals("checkout")) return;
        UUID checkoutId = db.sql("SELECT checkout_id FROM payment_attempt WHERE id = :id")
                .param("id", event.purposeId()).query(UUID.class).single();
        load(checkoutId, true);
        db.sql("UPDATE payment_attempt SET status = 'failed' WHERE id = :id").param("id", event.purposeId()).update();
        db.sql("UPDATE checkout SET status = 'open', payment_method = NULL WHERE id = :id AND status = 'processing'")
                .param("id", checkoutId).update();
    }

    // ---- refunds ----------------------------------------------------------------------------

    record Refund(UUID id, String reference, String checkout, int line, long amount, long feeReturned,
                  String destination, String status, UUID journalTx) {}

    @Transactional
    Refund refund(MerchantAccess.Access access, String reference, int line, long amount, String reason) {
        Checkout c = load(reference, true);
        Allocation a = allocation(c, line);
        if (!access.merchantId().equals(a.merchantId()) && !access.merchantId().equals(c.collectorId())) {
            throw ApiException.notFound("Allocation not found");
        }
        if (!c.status().equals("paid")) throw ApiException.conflict("not_paid", "Only paid checkouts can be refunded");
        if (amount <= 0) throw ApiException.badRequest("invalid_amount", "Refund amount must be positive");
        if (Math.addExact(Math.addExact(a.refunded(), a.disputed()), amount) > a.amount()) {
            throw ApiException.conflict("refund_exceeds_allocation", "Refund exceeds the remaining allocation");
        }
        long feeReturn = a.refunded() + amount == a.amount()
                ? a.merchantFee() - a.refundedFee()
                : Math.min(a.merchantFee() - a.refundedFee(), Math.multiplyExact(a.merchantFee(), amount) / a.amount());
        long merchantDebit = amount - feeReturn;
        long fromPending = Math.min(a.pendingAmount(), merchantDebit);
        long fromAvailable = merchantDebit - fromPending;

        UUID id = UUID.randomUUID();
        String refundRef = References.next("RF");
        boolean toWallet = c.paymentMethod().equals("wallet");
        List<Leg> legs = new ArrayList<>();
        if (fromPending > 0) legs.add(Leg.debit(Ledger.code("merchant", a.merchantId(), "pending", Ledger.GHS), fromPending));
        if (fromAvailable > 0) legs.add(Leg.debit(Ledger.code("merchant", a.merchantId(), "available", Ledger.GHS), fromAvailable));
        if (feeReturn > 0) legs.add(Leg.debit(Ledger.REVENUE, feeReturn));
        legs.add(Leg.credit(toWallet ? CustomerDirectory.available(c.customerId()) : Ledger.REFUNDS_IN_FLIGHT, amount));
        var posted = ledger.post(Ledger.Posting.of("refund", id.toString(), "refund", legs)
                .relatedTo(c.journalTx()).describedAs(refundRef)
                .withMetadata(Map.of("checkout", c.reference(), "line", String.valueOf(line))));
        db.sql("""
                UPDATE checkout_allocation SET refunded = refunded + :amount, refunded_fee = refunded_fee + :fee,
                       pending_amount = pending_amount - :pending
                WHERE checkout_id = :c AND line = :l""")
                .param("amount", amount).param("fee", feeReturn).param("pending", fromPending)
                .param("c", c.id()).param("l", line).update();
        UUID railTx = null;
        if (!toWallet) {
            String railRef = db.sql("""
                    SELECT t.reference FROM payment_attempt p JOIN rail_transaction t ON t.id = p.rail_tx
                    WHERE p.checkout_id = :c AND p.status = 'succeeded'""").param("c", c.id()).query(String.class).single();
            var original = rails.findByReference(railRef).orElseThrow();
            railTx = rails.create(original.rail(), "refund", "refund", id, amount, new RailTransactions.Request(
                    original.channel(), original.msisdn(), null, null, null, "Refund " + c.reference(), null, null,
                    null, null, original.reference(), original.railReference())).id();
        }
        db.sql("""
                INSERT INTO refund (id, reference, checkout_id, line, amount, fee_returned, destination, status, journal_tx,
                    rail_tx, reason, created_by)
                VALUES (:id, :ref, :c, :l, :amount, :fee, :dest, :status, :tx, :rail, :reason, :by)""")
                .param("id", id).param("ref", refundRef).param("c", c.id()).param("l", line).param("amount", amount)
                .param("fee", feeReturn).param("dest", toWallet ? "wallet" : "rail")
                .param("status", toWallet ? "completed" : "pending").param("tx", posted.transactionId())
                .param("rail", railTx).param("reason", reason).param("by", access.actor().subject()).update();
        if (toWallet) {
            db.sql("""
                    INSERT INTO wallet_operation (id, reference, customer_id, kind, amount, status, counterparty, journal_tx, related_id)
                    VALUES (:id, :ref, :cust, 'refund', :amount, 'completed', :cp, :tx, :refund)""")
                    .param("id", UUID.randomUUID()).param("ref", refundRef).param("cust", c.customerId())
                    .param("amount", amount).param("cp", a.merchantName() + " " + c.reference())
                    .param("tx", posted.transactionId()).param("refund", id).update();
        }
        audit.record(access.actor().subject(), "refund.created", "checkout:" + c.reference(),
                Map.of("line", line, "amount", amount, "refund", refundRef, "reason", String.valueOf(reason)));
        outbox.publish("refund", refundRef, toWallet ? "refund.completed" : "refund.pending", Map.of(
                "refund", refundRef, "checkout", c.reference(), "line", line, "amount", amount),
                Set.of(a.merchantId(), c.collectorId()));
        return new Refund(id, refundRef, c.reference(), line, amount, feeReturn, toWallet ? "wallet" : "rail",
                toWallet ? "completed" : "pending", posted.transactionId());
    }

    UUID pendingRefundRailTx(UUID refundId) {
        return db.sql("SELECT rail_tx FROM refund WHERE id = :id").param("id", refundId).query(UUID.class).single();
    }

    @EventListener
    void onRefundRail(RailEvents.Succeeded event) {
        if (!event.purpose().equals("refund")) return;
        var refund = db.sql("SELECT reference, amount FROM refund WHERE id = :id AND status = 'pending' FOR UPDATE")
                .param("id", event.purposeId()).query((rs, i) -> new Object[]{rs.getString(1), rs.getLong(2)}).optional();
        if (refund.isEmpty()) return;
        long amount = (long) refund.get()[1];
        var posted = ledger.post(Ledger.Posting.of("refund-completion", event.purposeId().toString(), "refund_completion",
                List.of(Leg.debit(Ledger.REFUNDS_IN_FLIGHT, amount), Leg.credit(Ledger.clearing(event.rail()), amount))));
        db.sql("UPDATE refund SET status = 'completed', completion_tx = :tx WHERE id = :id")
                .param("tx", posted.transactionId()).param("id", event.purposeId()).update();
    }

    @EventListener
    void onRefundRailFailed(RailEvents.Failed event) {
        if (!event.purpose().equals("refund")) return;
        db.sql("UPDATE refund SET status = 'failed' WHERE id = :id AND status = 'pending'").param("id", event.purposeId()).update();
        ops.raise("refund_delivery_failed", "high", "refund:" + event.purposeId(), Map.of(
                "amount", event.amount(), "reason", String.valueOf(event.reason()),
                "note", "Funds are held in refunds_in_flight; retry or credit the customer's wallet"));
    }

    private static Allocation allocation(Checkout c, int line) {
        return c.allocations().stream().filter(x -> x.line() == line).findFirst()
                .orElseThrow(() -> ApiException.notFound("Allocation not found"));
    }

    // ---- disputes ---------------------------------------------------------------------------

    record Dispute(UUID id, String checkout, int line, long amount, String reason, String status, UUID holdTx,
                   UUID resolutionTx) {}

    @Transactional
    Dispute openDispute(String actor, String reference, int line, long amount, String reason, String providerCase) {
        Checkout c = load(reference, true);
        Allocation a = allocation(c, line);
        if (!c.status().equals("paid")) throw ApiException.conflict("not_paid", "Only paid checkouts can be disputed");
        if (amount <= 0 || Math.addExact(Math.addExact(a.refunded(), a.disputed()), amount) > a.amount()) {
            throw ApiException.conflict("dispute_exceeds_allocation", "Dispute exceeds the remaining allocation");
        }
        long fromPending = Math.min(a.pendingAmount(), amount);
        long fromAvailable = amount - fromPending;
        String reserved = Ledger.code("merchant", a.merchantId(), "reserved", Ledger.GHS);
        UUID id = UUID.randomUUID();
        List<Leg> legs = new ArrayList<>();
        if (fromPending > 0) legs.add(Leg.debit(Ledger.code("merchant", a.merchantId(), "pending", Ledger.GHS), fromPending));
        if (fromAvailable > 0) legs.add(Leg.debit(Ledger.code("merchant", a.merchantId(), "available", Ledger.GHS), fromAvailable));
        legs.add(Leg.credit(reserved, amount));
        Ledger.Posted hold;
        try {
            hold = ledger.post(Ledger.Posting.of("dispute-hold", id.toString(), "dispute_hold", legs).relatedTo(c.journalTx()));
        } catch (ApiException e) {
            if (e.code().equals("insufficient_funds")) {
                ops.raise("dispute_unfunded", "high", "checkout:" + reference + ":" + line,
                        Map.of("amount", amount, "merchant", a.merchantId().toString()));
            }
            throw e;
        }
        db.sql("""
                UPDATE checkout_allocation SET disputed = disputed + :amount, pending_amount = pending_amount - :p
                WHERE checkout_id = :c AND line = :l""")
                .param("amount", amount).param("p", fromPending).param("c", c.id()).param("l", line).update();
        db.sql("""
                INSERT INTO dispute (id, checkout_id, line, amount, reason, status, hold_tx, rail_case, opened_by)
                VALUES (:id, :c, :l, :amount, :reason, 'open', :tx, :case, :by)""")
                .param("id", id).param("c", c.id()).param("l", line).param("amount", amount).param("reason", reason)
                .param("tx", hold.transactionId()).param("case", providerCase).param("by", actor).update();
        audit.record(actor, "dispute.opened", "checkout:" + reference, Map.of("line", line, "amount", amount));
        outbox.publish("dispute", id, "dispute.opened", Map.of("dispute", id.toString(), "checkout", reference,
                "line", line, "amount", amount), Set.of(a.merchantId(), c.collectorId()));
        return dispute(id);
    }

    @Transactional
    Dispute resolveDispute(String actor, UUID id, String outcome, String note) {
        var d = db.sql("""
                SELECT d.checkout_id, d.line, d.amount, d.status, c.payment_method, c.customer_id, a.merchant_id, c.reference
                FROM dispute d JOIN checkout c ON c.id = d.checkout_id
                JOIN checkout_allocation a ON a.checkout_id = d.checkout_id AND a.line = d.line
                WHERE d.id = :id FOR UPDATE OF d, a""").param("id", id)
                .query((rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getInt(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getString(8)})
                .optional().orElseThrow(() -> ApiException.notFound("Dispute not found"));
        if (!d[3].equals("open")) throw ApiException.conflict("dispute_closed", "Dispute is already resolved");
        long amount = (long) d[2];
        UUID merchant = (UUID) d[6];
        String reserved = Ledger.code("merchant", merchant, "reserved", Ledger.GHS);
        String status;
        List<Leg> legs;
        if (outcome.equals("merchant_won")) {
            status = "merchant_won";
            legs = List.of(Leg.debit(reserved, amount), Leg.credit(Ledger.code("merchant", merchant, "available", Ledger.GHS), amount));
            db.sql("UPDATE checkout_allocation SET disputed = disputed - :a WHERE checkout_id = :c AND line = :l")
                    .param("a", amount).param("c", d[0]).param("l", d[1]).update();
        } else if (outcome.equals("customer_won")) {
            status = "customer_won";
            String destination = "wallet".equals(d[4]) ? CustomerDirectory.available((UUID) d[5]) : Ledger.REFUNDS_IN_FLIGHT;
            legs = List.of(Leg.debit(reserved, amount), Leg.credit(destination, amount));
            if (!"wallet".equals(d[4])) {
                ops.raise("dispute_refund_delivery", "medium", "dispute:" + id,
                        Map.of("amount", amount, "note", "Return funds to the customer through the original rail"));
            }
        } else {
            throw ApiException.badRequest("invalid_outcome", "Use merchant_won or customer_won");
        }
        var posted = ledger.post(Ledger.Posting.of("dispute-resolution", id.toString(), "dispute_" + status, legs));
        db.sql("""
                UPDATE dispute SET status = :s, resolution_tx = :tx, resolved_by = :by, resolution_note = :note,
                       resolved_at = now() WHERE id = :id""")
                .param("s", status).param("tx", posted.transactionId()).param("by", actor).param("note", note)
                .param("id", id).update();
        audit.record(actor, "dispute.resolved", "dispute:" + id, Map.of("outcome", status));
        outbox.publish("dispute", id, "dispute.resolved", Map.of("dispute", id.toString(), "outcome", status,
                "checkout", (String) d[7]), Set.of(merchant));
        return dispute(id);
    }

    Dispute dispute(UUID id) {
        return db.sql("""
                SELECT d.id, c.reference, d.line, d.amount, d.reason, d.status, d.hold_tx, d.resolution_tx
                FROM dispute d JOIN checkout c ON c.id = d.checkout_id WHERE d.id = :id""").param("id", id)
                .query((rs, i) -> new Dispute(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class)))
                .single();
    }

    // ---- settlement -------------------------------------------------------------------------

    record SettlementBatch(UUID id, String reference, UUID merchantId, long amount, int lines, UUID journalTx,
                           OffsetDateTime createdAt) {}

    /**
     * Moves due pending proceeds to available, one batch per merchant. With
     * {@code force}, ignores the merchant's settlement delay (operator action).
     */
    @Transactional
    List<SettlementBatch> settleDue(boolean force, UUID onlyMerchant) {
        record Due(UUID checkout, int line, UUID merchant, long pending) {}
        List<Due> due = db.sql("""
                SELECT a.checkout_id, a.line, a.merchant_id, a.pending_amount
                FROM checkout_allocation a JOIN checkout c ON c.id = a.checkout_id JOIN merchant m ON m.id = a.merchant_id
                WHERE c.status = 'paid' AND a.pending_amount > 0
                  AND (:force OR c.paid_at + make_interval(hours => m.settlement_delay_hours) <= now())
                  AND (CAST(:merchant AS uuid) IS NULL OR a.merchant_id = CAST(:merchant AS uuid))
                ORDER BY a.merchant_id, a.checkout_id, a.line
                FOR UPDATE OF a SKIP LOCKED""")
                .param("force", force).param("merchant", onlyMerchant)
                .query((rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, UUID.class),
                        rs.getLong(4))).list();
        Map<UUID, List<Due>> byMerchant = new LinkedHashMap<>();
        due.forEach(d -> byMerchant.computeIfAbsent(d.merchant(), k -> new ArrayList<>()).add(d));
        List<SettlementBatch> batches = new ArrayList<>();
        for (var entry : byMerchant.entrySet()) {
            UUID merchant = entry.getKey();
            long total = entry.getValue().stream().mapToLong(Due::pending).sum();
            UUID id = UUID.randomUUID();
            String reference = References.next("ST");
            var posted = ledger.post(Ledger.Posting.of("settlement", id.toString(), "settlement", List.of(
                    Leg.debit(Ledger.code("merchant", merchant, "pending", Ledger.GHS), total),
                    Leg.credit(Ledger.code("merchant", merchant, "available", Ledger.GHS), total))).describedAs(reference));
            db.sql("""
                    INSERT INTO settlement_batch (id, reference, merchant_id, amount, lines, journal_tx)
                    VALUES (:id, :ref, :m, :amount, :lines, :tx)""")
                    .param("id", id).param("ref", reference).param("m", merchant).param("amount", total)
                    .param("lines", entry.getValue().size()).param("tx", posted.transactionId()).update();
            for (Due d : entry.getValue()) {
                db.sql("""
                        UPDATE checkout_allocation SET settled_amount = settled_amount + pending_amount, pending_amount = 0,
                               settled_at = now(), settlement_tx = :tx, settlement_batch = :batch
                        WHERE checkout_id = :c AND line = :l""")
                        .param("tx", posted.transactionId()).param("batch", id).param("c", d.checkout())
                        .param("l", d.line()).update();
            }
            outbox.publish("settlement", reference, "settlement.completed", Map.of("settlement", reference,
                    "merchantId", merchant.toString(), "amount", total, "lines", entry.getValue().size()), Set.of(merchant));
            batches.add(settlementBatch(id));
        }
        return batches;
    }

    SettlementBatch settlementBatch(UUID id) {
        return db.sql("SELECT id, reference, merchant_id, amount, lines, journal_tx, created_at FROM settlement_batch WHERE id = :id")
                .param("id", id).query((rs, i) -> new SettlementBatch(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getLong(4), rs.getInt(5), rs.getObject(6, UUID.class),
                        rs.getObject(7, OffsetDateTime.class))).single();
    }

    @Scheduled(fixedDelayString = "${elepay.checkout.job-ms:60000}")
    void jobs() {
        if (!jobsEnabled) return;
        settleDue(false, null);
        db.sql("""
                UPDATE checkout SET status = 'expired' WHERE status = 'open' AND expires_at < now()""").update();
    }
}
