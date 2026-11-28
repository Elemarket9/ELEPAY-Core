package com.elepay.checkout;

import com.elepay.checkout.CheckoutService.Allocation;
import com.elepay.checkout.CheckoutService.Checkout;
import com.elepay.checkout.CheckoutService.ReserveRelease;
import com.elepay.checkout.CheckoutService.SettlementBatch;
import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.Idempotency;
import com.elepay.common.References;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import com.elepay.merchant.MerchantAccess;
import com.elepay.outbox.Outbox;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivery confirmation (escrow release trigger), due settlement batches with multi-currency
 * deferred revenue recognition, and conditional merchant reserve release.
 */
@Service
class CheckoutReserveService {
    private final JdbcClient db;
    private final Ledger ledger;
    private final MerchantAccess merchants;
    private final Outbox outbox;
    private final Audit audit;
    private final CheckoutConfigService config;
    private final CheckoutStore store;

    CheckoutReserveService(JdbcClient db, Ledger ledger, MerchantAccess merchants, Outbox outbox,
                           Audit audit, CheckoutConfigService config, CheckoutStore store) {
        this.db = db;
        this.ledger = ledger;
        this.merchants = merchants;
        this.outbox = outbox;
        this.audit = audit;
        this.config = config;
        this.store = store;
    }

    /**
     * The marketplace confirms that lines were delivered (all held lines when {@code lines} is empty). A
     * delivered line becomes due for settlement after the seller's settlement delay; with a delay of zero
     * the next settlement run releases it. Repeat confirmations are no-ops.
     */
    @Transactional
    Checkout confirmDelivery(UUID collector, String reference, List<Integer> lines, String actor) {
        Checkout c = store.load(reference, true);
        if (!c.collectorId().equals(collector)) throw ApiException.notFound("Checkout not found");
        if (!c.status().equals("paid")) throw ApiException.conflict("not_paid", "Only paid orders can be delivered");
        List<Allocation> targets;
        if (lines == null || lines.isEmpty()) {
            targets = c.allocations().stream().filter(Allocation::holdUntilDelivered).toList();
        } else {
            targets = lines.stream().distinct().map(l -> CheckoutStore.allocation(c, l)).toList();
        }
        List<Integer> confirmed = new ArrayList<>();
        for (Allocation a : targets) {
            if (!a.holdUntilDelivered()) {
                throw ApiException.badRequest("not_held", "Line " + a.line() + " is not held for delivery");
            }
            if (a.deliveredAt() != null || a.refunded() == a.amount()) continue;
            db.sql("""
                    UPDATE checkout_allocation SET delivered_at = now(), delivery_confirmed_by = :by
                    WHERE checkout_id = :c AND line = :l AND delivered_at IS NULL""")
                    .param("by", actor).param("c", c.id()).param("l", a.line()).update();
            confirmed.add(a.line());
        }
        if (!confirmed.isEmpty()) {
            audit.record(actor, "checkout.delivered", "checkout:" + reference, Map.of("lines", confirmed));
            outbox.publish("checkout", reference, "checkout.delivered",
                    Map.of("reference", reference, "lines", confirmed), CheckoutStore.recipients(c));
        }
        return store.load(c.id(), false);
    }

    @Transactional
    List<SettlementBatch> settleDue(boolean force, UUID onlyMerchant) {
        return settleDue(force, onlyMerchant, null);
    }

    /**
     * Settles due lines: each seller's net and each marketplace's share move from pending to available, one
     * batch per merchant, and ELEPAY's deferred fee for those lines is recognised as revenue. A line held for
     * delivery is due only once the marketplace has confirmed delivery and the seller's settlement delay has passed.
     * The order's delivery deadline is informational: a passed deadline is never treated as delivery and never
     * releases funds or fees. {@code force} skips the delay but never the delivery hold.
     */
    @Transactional
    List<SettlementBatch> settleDue(boolean force, UUID onlyMerchant, UUID onlyCheckout) {
        record Due(UUID checkout, int line, UUID merchant, UUID collector, long pending, long platform, long fee, String currency) {}
        record Candidate(UUID checkout, int line, UUID merchant, UUID collector, long pending, long platform, long fee,
                         boolean held, OffsetDateTime deliveredAt, OffsetDateTime paidAt, OffsetDateTime deadline,
                         OffsetDateTime now, String currency) {}
        List<Candidate> candidates = db.sql("""
                SELECT a.checkout_id, a.line, a.merchant_id, c.collector_id, a.pending_amount, a.platform_pending,
                       a.fee_deferred, a.hold_until_delivered, a.delivered_at, c.paid_at, c.delivery_deadline, now(),
                       c.currency
                FROM checkout_allocation a JOIN checkout c ON c.id = a.checkout_id
                WHERE c.status = 'paid' AND (a.pending_amount > 0 OR a.platform_pending > 0 OR a.fee_deferred > 0)
                  AND (c.currency <> 'GHS' OR EXISTS (SELECT 1 FROM settlement_funded_checkout f WHERE f.checkout_id=c.id)
                       OR c.payment_method='wallet' OR EXISTS (SELECT 1 FROM payment_attempt pa
                           JOIN rail_transaction rt ON rt.id=pa.rail_tx WHERE pa.checkout_id=c.id
                           AND pa.status='succeeded' AND rt.rail='sandbox'))
                  AND (NOT a.hold_until_delivered OR a.delivered_at IS NOT NULL)
                  AND (CAST(:merchant AS uuid) IS NULL OR a.merchant_id = CAST(:merchant AS uuid))
                  AND (CAST(:checkout AS uuid) IS NULL OR a.checkout_id = CAST(:checkout AS uuid))
                ORDER BY a.merchant_id, a.checkout_id, a.line
                LIMIT 500
                FOR UPDATE OF a SKIP LOCKED""")
                .param("merchant", onlyMerchant).param("checkout", onlyCheckout)
                .query((rs, i) -> new Candidate(rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getBoolean(8),
                        rs.getObject(9, OffsetDateTime.class), rs.getObject(10, OffsetDateTime.class),
                        rs.getObject(11, OffsetDateTime.class), rs.getObject(12, OffsetDateTime.class),
                        rs.getString(13) != null ? rs.getString(13).trim() : Ledger.GHS))
                .list();
        // Each seller's settlement delay comes from the merchant module.
        Map<UUID, Integer> delays = new HashMap<>();
        List<Due> due = new ArrayList<>();
        for (Candidate x : candidates) {
            int hours = delays.computeIfAbsent(x.merchant(),
                    m -> merchants.find(m).map(MerchantAccess.Merchant::settlementDelayHours)
                            .orElseGet(config::defaultSettlementDelayHours));
            boolean isDue;
            if (x.held()) {
                boolean delivered = x.deliveredAt() != null && (force || !x.deliveredAt().plusHours(hours).isAfter(x.now()));
                isDue = delivered;
            } else {
                isDue = force || !x.paidAt().plusHours(hours).isAfter(x.now());
            }
            if (isDue) {
                due.add(new Due(x.checkout(), x.line(), x.merchant(), x.collector(), x.pending(), x.platform(), x.fee(), x.currency()));
            }
        }
        if (due.isEmpty()) return List.of();
        // Seller nets go to the seller's batch, marketplace shares to the marketplace's batch, grouped by currency.
        record BatchKey(UUID merchant, String currency) {}
        Map<BatchKey, Long> totals = new LinkedHashMap<>();
        Map<BatchKey, Integer> parts = new LinkedHashMap<>();
        for (Due d : due) {
            if (d.pending() > 0) {
                totals.merge(new BatchKey(d.merchant(), d.currency()), d.pending(), Math::addExact);
                parts.merge(new BatchKey(d.merchant(), d.currency()), 1, Integer::sum);
            }
            if (d.platform() > 0) {
                totals.merge(new BatchKey(d.collector(), d.currency()), d.platform(), Math::addExact);
                parts.merge(new BatchKey(d.collector(), d.currency()), 1, Integer::sum);
            }
        }
        Map<BatchKey, SettlementBatch> byBatch = new LinkedHashMap<>();
        for (var entry : totals.entrySet()) {
            UUID merchant = entry.getKey().merchant();
            String currency = entry.getKey().currency();
            long total = entry.getValue();
            UUID id = UUID.randomUUID();
            String reference = References.next("ST");
            String pendingAcc = ledger.open("merchant", merchant, "pending", currency);
            String availableAcc = ledger.open("merchant", merchant, "available", currency);
            var posted = ledger.post(Ledger.Posting.of("settlement", id.toString(), "settlement", List.of(
                    Leg.debit(pendingAcc, total),
                    Leg.credit(availableAcc, total))).describedAs(reference));
            db.sql("""
                    INSERT INTO settlement_batch (id, reference, merchant_id, amount, lines, journal_tx)
                    VALUES (:id, :ref, :m, :amount, :lines, :tx)""")
                    .param("id", id).param("ref", reference).param("m", merchant).param("amount", total)
                    .param("lines", parts.get(entry.getKey())).param("tx", posted.transactionId()).update();
            outbox.publish("settlement", reference, "settlement.completed", Map.of("settlement", reference,
                    "merchantId", merchant.toString(), "amount", total, "lines", parts.get(entry.getKey()),
                    "currency", currency), Set.of(merchant));
            byBatch.put(entry.getKey(), settlementBatch(id));
        }
        record FeeStripeKey(String currency, int stripe) {}
        Map<FeeStripeKey, Long> feesByKey = new LinkedHashMap<>();
        Map<FeeStripeKey, UUID> sampleCheckoutByKey = new LinkedHashMap<>();
        for (Due d : due) {
            if (d.fee() > 0) {
                FeeStripeKey key = new FeeStripeKey(d.currency(), ledger.stripeIndex(d.checkout()));
                feesByKey.merge(key, d.fee(), Math::addExact);
                sampleCheckoutByKey.putIfAbsent(key, d.checkout());
            }
        }
        Map<FeeStripeKey, UUID> feeTxByKey = new LinkedHashMap<>();
        for (var entry : feesByKey.entrySet()) {
            FeeStripeKey key = entry.getKey();
            String cur = key.currency();
            long curFees = entry.getValue();
            UUID sampleCheckout = sampleCheckoutByKey.get(key);
            UUID run = UUID.randomUUID();
            String deferredAcc = ledger.openStripedSystem("system:deferred_revenue:" + cur, "deferred_revenue", cur, true, sampleCheckout);
            String revenueAcc = ledger.openStripedSystem("system:revenue:" + cur, "revenue", cur, false, sampleCheckout);
            UUID txId = ledger.post(Ledger.Posting.of("fee-recognition", run.toString(), "fee_recognition", List.of(
                    Leg.debit(deferredAcc, curFees), Leg.credit(revenueAcc, curFees)))
                    .describedAs("Fees earned on settled lines (" + cur + ")")).transactionId();
            feeTxByKey.put(key, txId);
        }
        for (Due d : due) {
            store.adjustBankFunding(d.checkout(), d.merchant(), d.pending());
            store.adjustBankFunding(d.checkout(), d.collector(), d.platform());
            SettlementBatch batch = byBatch.get(new BatchKey(d.pending() > 0 ? d.merchant() : d.collector(), d.currency()));
            FeeStripeKey feeKey = new FeeStripeKey(d.currency(), ledger.stripeIndex(d.checkout()));
            db.sql("""
                    UPDATE checkout_allocation SET settled_amount = settled_amount + pending_amount, pending_amount = 0,
                           platform_settled = platform_settled + platform_pending, platform_pending = 0,
                           fee_recognized = fee_recognized + fee_deferred, fee_deferred = 0,
                           settled_at = now(), settlement_tx = :tx, settlement_batch = :batch
                    WHERE checkout_id = :c AND line = :l""")
                    .param("tx", batch != null ? batch.journalTx() : feeTxByKey.get(feeKey))
                    .param("batch", batch != null ? batch.id() : null)
                    .param("c", d.checkout()).param("l", d.line()).update();
        }
        return new ArrayList<>(byBatch.values());
    }

    SettlementBatch settlementBatch(UUID id) {
        return db.sql("SELECT id, reference, merchant_id, amount, lines, journal_tx, created_at FROM settlement_batch WHERE id = :id")
                .param("id", id).query((rs, i) -> new SettlementBatch(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getLong(4), rs.getInt(5), rs.getObject(6, UUID.class),
                        rs.getObject(7, OffsetDateTime.class))).single();
    }

    @Transactional
    ReserveRelease releaseReserve(UUID merchantId, String reference, int line, long amount, String reason, String actor) {
        return releaseReserve(merchantId, reference, line, amount, reason, actor, null);
    }

    @Transactional
    ReserveRelease releaseReserve(UUID merchantId, String reference, int line, long amount, String reason, String actor, String idempotencyKey) {
        Checkout c = store.load(reference, true);
        Allocation a = CheckoutStore.allocation(c, line);
        if (!c.collectorId().equals(merchantId)) {
            throw ApiException.notFound("Allocation not found");
        }
        if (!c.status().equals("paid")) {
            throw ApiException.conflict("not_paid", "Only paid checkouts have retained reserves");
        }
        if (amount <= 0) {
            throw ApiException.badRequest("invalid_amount", "Release amount must be positive");
        }
        long availableReserve = a.reserveAmount() - a.reserveReleased();
        if (amount > availableReserve) {
            throw ApiException.conflict("reserve_exceeded", "Release amount exceeds remaining reserve of " + availableReserve);
        }

        boolean settled = a.settledAt() != null && !a.awaitingDelivery();
        String reserved = Ledger.code("merchant", a.merchantId(), "reserved", Ledger.GHS);
        String target = ledger.open("merchant", a.merchantId(), settled ? "available" : "pending", Ledger.GHS);

        UUID releaseId = UUID.randomUUID();
        String ledgerKey = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? Idempotency.sha256(merchantId + ":" + reference + ":" + line + ":" + idempotencyKey)
                : releaseId.toString();
        List<Leg> legs = List.of(
                Leg.debit(reserved, amount),
                Leg.credit(target, amount)
        );

        var posted = ledger.post(Ledger.Posting.of("reserve-release", ledgerKey, "reserve_release", legs)
                .describedAs("Reserve release for checkout " + reference + " line " + line)
                .withMetadata(Map.of("checkout", reference, "line", String.valueOf(line), "merchant", a.merchantId().toString())));

        if (settled && !posted.replay()) store.adjustBankFunding(c.id(), a.merchantId(), amount);
        String releaseStatus = (a.reserveReleased() + amount >= a.reserveAmount()) ? "RELEASED" : "PARTIALLY_RELEASED";
        db.sql("""
                UPDATE checkout_allocation
                SET reserve_released = reserve_released + :amt,
                    pending_amount = pending_amount + :pendingInc,
                    settled_amount = settled_amount + :settledInc,
                    release_status = :rstat
                WHERE checkout_id = :c AND line = :l""")
                .param("amt", amount)
                .param("pendingInc", settled ? 0L : amount)
                .param("settledInc", settled ? amount : 0L)
                .param("rstat", releaseStatus)
                .param("c", c.id()).param("l", line).update();

        db.sql("""
                INSERT INTO settlement_reserve_release (id, checkout_id, line, merchant_id, amount, journal_tx, reason, released_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """).params(releaseId, c.id(), line, a.merchantId(), amount, posted.transactionId(), reason, actor)
                .update();

        audit.record(actor, "checkout.reserve.released", "checkout:" + reference,
                Map.of("line", line, "amount", amount, "reason", String.valueOf(reason)));
        outbox.publish("checkout", reference, "checkout.reserve.released",
                Map.of("reference", reference, "line", line, "amount", amount), CheckoutStore.setOf(a.merchantId(), c.collectorId()));

        return new ReserveRelease(releaseId, c.id(), line, a.merchantId(), amount, posted.transactionId(), releaseStatus);
    }
}
