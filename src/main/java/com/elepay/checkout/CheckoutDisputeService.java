package com.elepay.checkout;

import com.elepay.checkout.CheckoutService.Allocation;
import com.elepay.checkout.CheckoutService.Checkout;
import com.elepay.checkout.CheckoutService.Dispute;
import com.elepay.checkout.CheckoutService.Take;
import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.OpsQueue;
import com.elepay.common.References;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import com.elepay.operations.EmtechCapture;
import com.elepay.outbox.Outbox;
import com.elepay.wallet.CustomerDirectory;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dispute opening (reserve hold), resolution (merchant_won / customer_won chargeback &amp; refund),
 * and dispute query helpers.
 */
@Service
class CheckoutDisputeService {
    private final JdbcClient db;
    private final Ledger ledger;
    private final Outbox outbox;
    private final Audit audit;
    private final OpsQueue ops;
    private final CustomerDirectory customers;
    private final EmtechCapture regulatory;
    private final CheckoutStore store;

    CheckoutDisputeService(JdbcClient db, Ledger ledger, Outbox outbox, Audit audit, OpsQueue ops,
                           CustomerDirectory customers, EmtechCapture regulatory, CheckoutStore store) {
        this.db = db;
        this.ledger = ledger;
        this.outbox = outbox;
        this.audit = audit;
        this.ops = ops;
        this.customers = customers;
        this.regulatory = regulatory;
        this.store = store;
    }

    @Transactional
    Dispute openDispute(String actor, String reference, int line, long amount, String reason, String providerCase) {
        Checkout c = store.load(reference, true);
        Allocation a = CheckoutStore.allocation(c, line);
        if (!c.status().equals("paid")) throw ApiException.conflict("not_paid", "Only paid checkouts can be disputed");
        if (amount <= 0 || Math.addExact(Math.addExact(a.refunded(), a.disputed()), amount) > a.amount()) {
            throw ApiException.conflict("dispute_exceeds_allocation", "Dispute exceeds the remaining allocation");
        }
        Take t = CheckoutService.take(a, amount);
        String reserved = Ledger.code("merchant", a.merchantId(), "reserved", Ledger.GHS);
        UUID id = UUID.randomUUID();
        List<Leg> legs = CheckoutStore.takeLegs(c, a, t);
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
                UPDATE checkout_allocation SET disputed = disputed + :amount, disputed_fee = disputed_fee + :fee,
                       disputed_platform_fee = disputed_platform_fee + :platform,
                       pending_amount = pending_amount - :p,
                       reserve_released = reserve_released + :reserved,
                       platform_pending = platform_pending - :pp,
                       fee_deferred = fee_deferred - :fd
                WHERE checkout_id = :c AND line = :l""")
                .param("amount", amount).param("fee", t.fee()).param("platform", t.platform())
                .param("p", t.sellerFromPending()).param("reserved", t.sellerFromReserved())
                .param("pp", t.platformFromPending()).param("fd", t.feeFromDeferred())
                .param("c", c.id()).param("l", line).update();
        db.sql("""
                INSERT INTO dispute (id, checkout_id, line, amount, reason, status, hold_tx, rail_case, opened_by,
                    seller_part, seller_reserved_part, fee_part, platform_part)
                VALUES (:id, :c, :l, :amount, :reason, 'open', :tx, :case, :by, :seller, :sellerReserved, :fee, :platform)""")
                .param("id", id).param("c", c.id()).param("l", line).param("amount", amount).param("reason", reason)
                .param("tx", hold.transactionId()).param("case", providerCase).param("by", actor)
                .param("seller", t.seller()).param("sellerReserved", t.sellerFromReserved())
                .param("fee", t.fee()).param("platform", t.platform()).update();
        audit.record(actor, "dispute.opened", "checkout:" + reference, Map.of("line", line, "amount", amount));
        outbox.publish("dispute", id, "dispute.opened", Map.of("dispute", id.toString(), "checkout", reference,
                "line", line, "amount", amount), CheckoutStore.setOf(a.merchantId(), c.collectorId()));
        regulatory.disputeOpened(id);
        return dispute(id);
    }

    @Transactional
    Dispute resolveDispute(String actor, UUID id, String outcome, String note) {
        record Held(UUID checkout, int line, long amount, String status, long seller, long sellerReserved, long fee, long platform) {}
        Held d = db.sql("""
                SELECT d.checkout_id, d.line, d.amount, d.status, d.seller_part, d.seller_reserved_part, d.fee_part, d.platform_part
                FROM dispute d WHERE d.id = :id FOR UPDATE""").param("id", id)
                .query((rs, i) -> new Held(rs.getObject(1, UUID.class), rs.getInt(2), rs.getLong(3), rs.getString(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)))
                .optional().orElseThrow(() -> ApiException.notFound("Dispute not found"));
        if (!d.status().equals("open")) throw ApiException.conflict("dispute_closed", "Dispute is already resolved");
        if (!Set.of("merchant_won", "customer_won").contains(outcome)) {
            throw ApiException.badRequest("invalid_outcome", "Use merchant_won or customer_won");
        }
        Checkout c = store.load(d.checkout(), true);
        Allocation a = CheckoutStore.allocation(c, d.line());
        // Disputes opened before V28 did not record their parts: the seller part is the whole amount less the
        // fee that the old hold took from revenue.
        boolean legacy = d.seller() + d.fee() + d.platform() != d.amount();
        long fee = legacy ? Math.min(a.merchantFee(), d.amount()) : d.fee();
        long platform = legacy ? 0 : d.platform();
        long seller = d.amount() - fee - platform;
        boolean settled = a.settledAt() != null && !a.awaitingDelivery();
        String reserved = Ledger.code("merchant", a.merchantId(), "reserved", Ledger.GHS);
        List<Leg> legs = new ArrayList<>();
        legs.add(Leg.debit(reserved, d.amount()));
        String refundRef = null;
        UUID refundId = null;
        if (outcome.equals("merchant_won")) {
            // Each party gets back exactly what the hold took, into the bucket the line is now in.
            // Any portion originally pulled from the rolling reserve must return to reserved, not pending/available.
            boolean pending = !settled && !legacy;
            long sellerReserved = legacy ? 0L : Math.min(seller, Math.max(0L, d.sellerReserved()));
            long sellerNonReserved = seller - sellerReserved;
            if (sellerReserved > 0) legs.add(Leg.credit(reserved, sellerReserved));
            if (sellerNonReserved > 0) legs.add(Leg.credit(Ledger.code("merchant", a.merchantId(), pending ? "pending" : "available", Ledger.GHS), sellerNonReserved));
            if (fee > 0) legs.add(Leg.credit(pending ? Ledger.DEFERRED_REVENUE : Ledger.REVENUE, fee));
            if (platform > 0) legs.add(Leg.credit(Ledger.code("merchant", c.collectorId(), pending ? "pending" : "available", Ledger.GHS), platform));
            db.sql("""
                    UPDATE checkout_allocation SET disputed = disputed - :amount,
                           disputed_fee = greatest(disputed_fee - :fee, 0),
                           disputed_platform_fee = disputed_platform_fee - :platform,
                           pending_amount = pending_amount + :p, platform_pending = platform_pending + :pp,
                           fee_deferred = fee_deferred + :fd,
                           settled_amount = settled_amount + :sa, platform_settled = platform_settled + :ps,
                           fee_recognized = fee_recognized + :fr,
                           reserve_released = greatest(reserve_released - :sr, 0),
                           release_status = CASE
                               WHEN :sr > 0 AND greatest(reserve_released - :sr, 0) = 0 THEN 'HELD'
                               WHEN :sr > 0 AND greatest(reserve_released - :sr, 0) < reserve_amount THEN 'PARTIALLY_RELEASED'
                               ELSE release_status
                           END
                    WHERE checkout_id = :c AND line = :l""")
                    .param("amount", d.amount()).param("fee", legacy ? 0 : fee).param("platform", platform)
                    .param("p", pending ? sellerNonReserved : 0).param("pp", pending ? platform : 0).param("fd", pending ? fee : 0)
                    .param("sa", pending ? 0 : sellerNonReserved).param("ps", pending ? 0 : platform)
                    .param("fr", pending || legacy ? 0 : fee)
                    .param("sr", sellerReserved)
                    .param("c", c.id()).param("l", a.line()).update();
        } else {
            boolean toWallet = "wallet".equals(c.paymentMethod());
            legs.add(Leg.credit(toWallet ? CustomerDirectory.available(c.customerId()) : Ledger.REFUNDS_IN_FLIGHT, d.amount()));
            db.sql("""
                    UPDATE checkout_allocation SET disputed = disputed - :amount, refunded = refunded + :amount,
                           disputed_fee = greatest(disputed_fee - :fee, 0), refunded_fee = refunded_fee + :fee,
                           disputed_platform_fee = disputed_platform_fee - :platform,
                           refunded_platform_fee = refunded_platform_fee + :platform
                    WHERE checkout_id = :c AND line = :l""")
                    .param("amount", d.amount()).param("fee", legacy ? 0 : fee).param("platform", platform)
                    .param("c", c.id()).param("l", a.line()).update();
            refundId = UUID.randomUUID();
            refundRef = References.next("RF");
        }
        var posted = ledger.post(Ledger.Posting.of("dispute-resolution", id.toString(), "dispute_" + outcome, legs));
        if (refundId != null) {
            boolean toWallet = "wallet".equals(c.paymentMethod());
            UUID railTx = toWallet ? null : store.railRefund(c, refundId, d.amount());
            db.sql("""
                    INSERT INTO refund (id, reference, checkout_id, line, amount, fee_returned, platform_fee_returned,
                        destination, status, journal_tx, rail_tx, reason, created_by)
                    VALUES (:id, :ref, :c, :l, :amount, :fee, :platform, :dest, :status, :tx, :rail, :reason, :by)""")
                    .param("id", refundId).param("ref", refundRef).param("c", c.id()).param("l", a.line())
                    .param("amount", d.amount()).param("fee", fee).param("platform", platform)
                    .param("dest", toWallet ? "wallet" : "rail").param("status", toWallet ? "completed" : "pending")
                    .param("tx", posted.transactionId()).param("rail", railTx)
                    .param("reason", "Dispute " + id + " resolved for the customer").param("by", actor).update();
            if (toWallet) {
                customers.recordOperation(refundRef, c.customerId(), "refund", d.amount(), "completed",
                        a.merchantName() + " " + c.reference(), posted.transactionId(), refundId);
            }
        }
        db.sql("""
                UPDATE dispute SET status = :s, resolution_tx = :tx, resolved_by = :by, resolution_note = :note,
                       resolved_at = now(), refund_id = :refund WHERE id = :id""")
                .param("s", outcome).param("tx", posted.transactionId()).param("by", actor).param("note", note)
                .param("refund", refundId).param("id", id).update();
        audit.record(actor, "dispute.resolved", "dispute:" + id, Map.of("outcome", outcome));
        outbox.publish("dispute", id, "dispute.resolved", Map.of("dispute", id.toString(), "outcome", outcome,
                "checkout", c.reference()), CheckoutStore.setOf(a.merchantId(), c.collectorId()));
        regulatory.disputeResolved(id);
        return dispute(id);
    }

    /** The rail refund a customer-won dispute started, if it still needs submitting. */
    Optional<UUID> disputeRefundRailTx(UUID disputeId) {
        return db.sql("""
                SELECT r.rail_tx FROM dispute d JOIN refund r ON r.id = d.refund_id
                WHERE d.id = :id AND r.status = 'pending' AND r.rail_tx IS NOT NULL""")
                .param("id", disputeId).query(UUID.class).optional();
    }

    Dispute dispute(UUID id) {
        return db.sql("""
                SELECT d.id, c.reference, d.line, d.amount, d.reason, d.status, d.hold_tx, d.resolution_tx, r.reference
                FROM dispute d JOIN checkout c ON c.id = d.checkout_id LEFT JOIN refund r ON r.id = d.refund_id
                WHERE d.id = :id""").param("id", id)
                .query((rs, i) -> new Dispute(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class),
                        rs.getString(9)))
                .single();
    }
}
