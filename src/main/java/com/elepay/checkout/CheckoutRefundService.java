package com.elepay.checkout;

import com.elepay.checkout.CheckoutService.Allocation;
import com.elepay.checkout.CheckoutService.Checkout;
import com.elepay.checkout.CheckoutService.Refund;
import com.elepay.checkout.CheckoutService.Take;
import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.Idempotency;
import com.elepay.common.OpsQueue;
import com.elepay.common.References;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import com.elepay.merchant.MerchantAccess;
import com.elepay.operations.EmtechCapture;
import com.elepay.outbox.Outbox;
import com.elepay.rails.RailEvents;
import com.elepay.wallet.CustomerDirectory;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-allocation refunds (wallet and rail), multi-merchant clawback accounting, deterministic
 * idempotent ledger keys, and refund rail finality listeners.
 */
@Service
class CheckoutRefundService {
    private final JdbcClient db;
    private final Ledger ledger;
    private final Outbox outbox;
    private final Audit audit;
    private final OpsQueue ops;
    private final CustomerDirectory customers;
    private final EmtechCapture regulatory;
    private final CheckoutStore store;
    private com.elepay.identity.KillSwitchService killSwitch;

    @Autowired(required = false)
    void setKillSwitch(com.elepay.identity.KillSwitchService killSwitch) {
        this.killSwitch = killSwitch;
    }

    CheckoutRefundService(JdbcClient db, Ledger ledger, Outbox outbox, Audit audit, OpsQueue ops,
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
    Refund refund(MerchantAccess.Access access, String reference, int line, long amount, String reason) {
        return refund(access, reference, line, amount, reason, null);
    }

    @Transactional
    Refund refund(MerchantAccess.Access access, String reference, int line, long amount, String reason, String idempotencyKey) {
        if (killSwitch != null) killSwitch.requirePayoutsAllowed(access.merchantId());
        Checkout c = store.load(reference, true);
        Allocation a = CheckoutStore.allocation(c, line);
        if (!access.merchantId().equals(a.merchantId()) && !access.merchantId().equals(c.collectorId())) {
            throw ApiException.notFound("Allocation not found");
        }
        if (!c.status().equals("paid")) throw ApiException.conflict("not_paid", "Only paid checkouts can be refunded");
        if (amount <= 0) throw ApiException.badRequest("invalid_amount", "Refund amount must be positive");
        UUID id = UUID.randomUUID();
        String ledgerKey = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? Idempotency.sha256(access.merchantId() + ":" + reference + ":" + line + ":" + idempotencyKey)
                : id.toString();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingTx = ledger.findTransactionByIdempotency("refund", ledgerKey);
            if (existingTx.isPresent()) {
                Optional<Refund> existingRefund = findRefundByJournalTx(existingTx.get());
                if (existingRefund.isPresent()) {
                    Refund prev = existingRefund.get();
                    if (prev.amount() != amount || prev.line() != line || !prev.checkout().equals(c.reference())) {
                        throw ApiException.idempotencyConflict();
                    }
                    return prev;
                }
            }
        }
        if (Math.addExact(Math.addExact(a.refunded(), a.disputed()), amount) > a.amount()) {
            throw ApiException.conflict("refund_exceeds_allocation", "Refund exceeds the remaining allocation");
        }
        Take t = CheckoutService.take(a, amount);
        String refundRef = References.next("RF");
        boolean toWallet = c.paymentMethod().equals("wallet");
        List<Leg> legs = CheckoutStore.takeLegs(ledger, c, a, t);
        legs.add(Leg.credit(toWallet ? CustomerDirectory.available(c.customerId()) : Ledger.REFUNDS_IN_FLIGHT, amount));
        var posted = ledger.post(Ledger.Posting.of("refund", ledgerKey, "refund", legs)
                .relatedTo(c.journalTx()).describedAs(refundRef)
                .withMetadata(Map.of("checkout", c.reference(), "line", String.valueOf(line))));
        if (posted.replay()) {
            return findRefundByJournalTx(posted.transactionId()).orElseThrow();
        }
        db.sql("""
                UPDATE checkout_allocation SET refunded = refunded + :amount, refunded_fee = refunded_fee + :fee,
                       refunded_platform_fee = refunded_platform_fee + :platform,
                       pending_amount = pending_amount - :pending,
                       reserve_released = reserve_released + :reserved,
                       platform_pending = platform_pending - :pp,
                       fee_deferred = fee_deferred - :fd
                WHERE checkout_id = :c AND line = :l""")
                .param("amount", amount).param("fee", t.fee()).param("platform", t.platform())
                .param("pending", t.sellerFromPending()).param("reserved", t.sellerFromReserved())
                .param("pp", t.platformFromPending())
                .param("fd", t.feeFromDeferred()).param("c", c.id()).param("l", line).update();
        UUID railTx = toWallet ? null : store.railRefund(c, id, amount);
        db.sql("""
                INSERT INTO refund (id, reference, checkout_id, line, amount, fee_returned, platform_fee_returned,
                    destination, status, journal_tx, rail_tx, reason, created_by)
                VALUES (:id, :ref, :c, :l, :amount, :fee, :platform, :dest, :status, :tx, :rail, :reason, :by)""")
                .param("id", id).param("ref", refundRef).param("c", c.id()).param("l", line).param("amount", amount)
                .param("fee", t.fee()).param("platform", t.platform()).param("dest", toWallet ? "wallet" : "rail")
                .param("status", toWallet ? "completed" : "pending").param("tx", posted.transactionId())
                .param("rail", railTx).param("reason", reason).param("by", access.actor().subject()).update();
        if (toWallet) {
            customers.recordOperation(refundRef, c.customerId(), "refund", amount, "completed",
                    a.merchantName() + " " + c.reference(), posted.transactionId(), id);
        }
        audit.record(access.actor().subject(), "refund.created", "checkout:" + c.reference(),
                Map.of("line", line, "amount", amount, "refund", refundRef, "reason", String.valueOf(reason)));
        outbox.publish("refund", refundRef, toWallet ? "refund.completed" : "refund.pending", Map.of(
                "refund", refundRef, "checkout", c.reference(), "line", line, "amount", amount),
                CheckoutStore.setOf(a.merchantId(), c.collectorId()));
        if (toWallet) regulatory.refund(id);
        return new Refund(id, refundRef, c.reference(), line, amount, t.fee(), t.platform(),
                toWallet ? "wallet" : "rail", toWallet ? "completed" : "pending", posted.transactionId());
    }

    private Optional<Refund> findRefundByJournalTx(UUID journalTx) {
        return db.sql("""
                SELECT r.id, r.reference, c.reference, r.line, r.amount, r.fee_returned,
                       r.platform_fee_returned, r.destination, r.status, r.journal_tx
                FROM refund r
                JOIN checkout c ON c.id = r.checkout_id
                WHERE r.journal_tx = :tx""")
                .param("tx", journalTx)
                .query((rs, i) -> new Refund(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getLong(5), rs.getLong(6), rs.getLong(7),
                        rs.getString(8), rs.getString(9), rs.getObject(10, UUID.class)))
                .optional();
    }

    UUID pendingRefundRailTx(UUID refundId) {
        return db.sql("SELECT rail_tx FROM refund WHERE id = :id").param("id", refundId).query(UUID.class).single();
    }

    @EventListener
    @Transactional
    void onRefundRail(RailEvents.Succeeded event) {
        if (!event.purpose().equals("refund")) return;
        var refundOpt = db.sql("SELECT reference, amount, status, rail_tx FROM refund WHERE id = :id FOR UPDATE")
                .param("id", event.purposeId())
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getLong(2), rs.getString(3), rs.getObject(4, UUID.class)})
                .optional();
        if (refundOpt.isEmpty()) return;
        String reference = (String) refundOpt.get()[0];
        long amount = (long) refundOpt.get()[1];
        String status = (String) refundOpt.get()[2];
        UUID currentRailTx = (UUID) refundOpt.get()[3];
        if (currentRailTx != null && !currentRailTx.equals(event.railTxId())) {
            ops.raise("refund_stale_rail_event", "high", "refund:" + reference, Map.of(
                    "event", "succeeded", "eventRailTx", event.railTxId().toString(),
                    "currentRailTx", currentRailTx.toString(), "status", status));
            return;
        }
        if (!"pending".equals(status)) return;
        String clearing = ledger.openStripedSystem(Ledger.clearing(event.rail()), "clearing", Ledger.GHS, true, event.purposeId());
        var posted = ledger.post(Ledger.Posting.of("refund-completion", event.railTxId().toString(), "refund_completion",
                List.of(Leg.debit(Ledger.REFUNDS_IN_FLIGHT, amount), Leg.credit(clearing, amount))));
        db.sql("UPDATE refund SET status = 'completed', completion_tx = :tx WHERE id = :id")
                .param("tx", posted.transactionId()).param("id", event.purposeId()).update();
        regulatory.refund(event.purposeId());
    }

    @EventListener
    @Transactional
    void onRefundRailFailed(RailEvents.Failed event) {
        if (!event.purpose().equals("refund")) return;
        var refundOpt = db.sql("SELECT reference, status, rail_tx FROM refund WHERE id = :id FOR UPDATE")
                .param("id", event.purposeId())
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class)})
                .optional();
        if (refundOpt.isEmpty()) return;
        String reference = (String) refundOpt.get()[0];
        String status = (String) refundOpt.get()[1];
        UUID currentRailTx = (UUID) refundOpt.get()[2];
        if (currentRailTx != null && !currentRailTx.equals(event.railTxId())) {
            ops.raise("refund_stale_rail_event", "high", "refund:" + reference, Map.of(
                    "event", "failed", "eventRailTx", event.railTxId().toString(),
                    "currentRailTx", currentRailTx.toString(), "status", status));
            return;
        }
        int failed = db.sql("UPDATE refund SET status = 'failed' WHERE id = :id AND status = 'pending' AND rail_tx = :rtx")
                .param("id", event.purposeId()).param("rtx", event.railTxId()).update();
        if (failed == 1) regulatory.refund(event.purposeId());
        ops.raise("refund_delivery_failed", "high", "refund:" + event.purposeId(), Map.of(
                "amount", event.amount(), "reason", String.valueOf(event.reason()),
                "note", "Funds are held in refunds_in_flight; retry or credit the customer's wallet"));
    }

    /**
     * Handles a rail-level return (reversal) of a previously completed customer refund.
     * Moves the returned funds from clearing back into {@code system:refunds_in_flight:GHS}
     * and transitions the refund to {@code failed} so it can be safely retried from the Custodian.
     */
    @EventListener
    @Transactional
    void onRefundRailReturned(RailEvents.Returned event) {
        if (!event.purpose().equals("refund")) return;
        var refundOpt = db.sql("SELECT reference, amount, status, rail_tx FROM refund WHERE id = :id FOR UPDATE")
                .param("id", event.purposeId())
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getLong(2), rs.getString(3), rs.getObject(4, UUID.class)})
                .optional();
        if (refundOpt.isEmpty()) return;
        String reference = (String) refundOpt.get()[0];
        long amount = (long) refundOpt.get()[1];
        String status = (String) refundOpt.get()[2];
        UUID currentRailTx = (UUID) refundOpt.get()[3];
        if (currentRailTx != null && !currentRailTx.equals(event.railTxId())) {
            ops.raise("refund_stale_rail_event", "high", "refund:" + reference, Map.of(
                    "event", "returned", "eventRailTx", event.railTxId().toString(),
                    "currentRailTx", currentRailTx.toString(), "status", status));
            return;
        }
        if (!"completed".equals(status)) {
            ops.raise("refund_return_unexpected", "high", "refund:" + reference, Map.of("status", status));
            return;
        }
        String clearing = ledger.openStripedSystem(Ledger.clearing(event.rail()), "clearing", Ledger.GHS, true, event.purposeId());
        ledger.post(Ledger.Posting.of("refund-return", event.railTxId().toString(), "refund_return",
                List.of(Leg.debit(clearing, amount), Leg.credit(Ledger.REFUNDS_IN_FLIGHT, amount)))
                .describedAs("Returned refund " + reference));
        db.sql("UPDATE refund SET status = 'failed', completion_tx = NULL WHERE id = :id")
                .param("id", event.purposeId()).update();
        ops.raise("refund_returned_by_rail", "high", "refund:" + reference, Map.of(
                "amount", amount,
                "reason", String.valueOf(event.reason()),
                "note", "Returned refund funds restored to refunds_in_flight; retry from Custodian or credit customer wallet"));
        regulatory.refund(event.purposeId());
    }

    /**
     * Retries a failed or returned rail refund using the funds already safeguarded in
     * {@code system:refunds_in_flight:GHS}, creating a new rail transaction to pull from the Custodian.
     * Must be submitted to {@code RailTransactions.submit(railTxId)} after this transaction commits.
     */
    @Transactional
    UUID retryFailedRailRefund(UUID refundId, String actor) {
        if (killSwitch != null) killSwitch.requirePayoutsAllowed(null);
        record RefundRow(UUID id, String reference, UUID checkoutId, long amount, String destination, String status) {}
        RefundRow row = db.sql("""
                SELECT id, reference, checkout_id, amount, destination, status
                FROM refund WHERE id = :id FOR UPDATE""")
                .param("id", refundId)
                .query((rs, i) -> new RefundRow(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getLong(4), rs.getString(5), rs.getString(6)))
                .optional()
                .orElseThrow(() -> ApiException.notFound("Refund not found"));
        if (!"rail".equals(row.destination()) || !"failed".equals(row.status())) {
            throw ApiException.conflict("refund_not_retryable",
                    "Only failed rail refunds can be retried (current destination=" + row.destination()
                            + ", status=" + row.status() + ")");
        }
        Checkout c = store.load(row.checkoutId(), false);
        UUID newRailTx = store.railRefund(c, row.id(), row.amount());
        db.sql("UPDATE refund SET status = 'pending', rail_tx = :rail WHERE id = :id")
                .param("rail", newRailTx).param("id", row.id()).update();
        audit.record(actor != null ? actor : "system", "refund.retried", "refund:" + row.reference(),
                Map.of("refundId", row.id().toString(), "amount", row.amount(), "railTx", newRailTx.toString()));
        return newRailTx;
    }
}
