package com.elepay.checkout;

import com.elepay.checkout.CheckoutService.Allocation;
import com.elepay.checkout.CheckoutService.Checkout;
import com.elepay.checkout.CheckoutService.Take;
import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import com.elepay.merchant.MerchantAccess;
import com.elepay.rails.RailTransactions;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Shared checkout persistence loader and pure allocation/ledger helper primitives used across
 * internal checkout services.
 */
@Service
class CheckoutStore {
    private final JdbcClient db;
    private final MerchantAccess merchants;
    private final RailTransactions rails;
    private final CheckoutConfigService config;

    CheckoutStore(JdbcClient db, MerchantAccess merchants, RailTransactions rails, CheckoutConfigService config) {
        this.db = db;
        this.merchants = merchants;
        this.rails = rails;
        this.config = config;
    }

    Checkout load(String reference, boolean lock) {
        UUID id = db.sql("SELECT id FROM checkout WHERE reference = :r" + (lock ? " FOR UPDATE" : ""))
                .param("r", reference).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Checkout not found"));
        return load(id, lock);
    }

    Checkout load(UUID id, boolean lock) {
        var head = db.sql("""
                SELECT c.id, c.reference, c.collector_id, NULL, c.branch_id, c.status, c.subtotal, c.description,
                       c.payment_method, c.customer_fee, c.total_paid, c.customer_id, c.journal_tx, c.expires_at,
                       c.paid_at, c.created_at, c.delivery_deadline
                FROM checkout c WHERE c.id = :id""" + (lock ? " FOR UPDATE OF c" : ""))
                .param("id", id).query((rs, i) -> new Object[]{
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getLong(7), rs.getString(8), rs.getString(9),
                        rs.getLong(10), (Long) rs.getObject(11), rs.getObject(12, UUID.class), rs.getObject(13, UUID.class),
                        rs.getObject(14, OffsetDateTime.class), rs.getObject(15, OffsetDateTime.class),
                        rs.getObject(16, OffsetDateTime.class), rs.getObject(17, OffsetDateTime.class)})
                .optional().orElseThrow(() -> ApiException.notFound("Checkout not found"));
        List<Allocation> lines = db.sql("""
                SELECT a.line, a.merchant_id, NULL, a.kind, a.description, a.amount, a.merchant_fee, a.refunded,
                       a.refunded_fee, a.disputed, a.pending_amount, a.settled_amount, a.settled_at, a.category,
                       a.commission, a.processing_fee, a.refunded_platform_fee, a.disputed_fee,
                       a.disputed_platform_fee, a.platform_pending, a.fee_deferred, a.hold_until_delivered,
                       a.delivered_at, COALESCE(a.reserve_amount, 0), COALESCE(a.reserve_released, 0),
                       COALESCE(a.release_condition, 'DELIVERY_CONFIRMED'), COALESCE(a.release_status, 'RELEASED')
                FROM checkout_allocation a
                WHERE a.checkout_id = :id ORDER BY a.line""" + (lock ? " FOR UPDATE OF a" : ""))
                .param("id", id).query((rs, i) -> new Allocation(rs.getInt(1), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                        rs.getLong(9), rs.getLong(10), rs.getLong(11), rs.getLong(12),
                        rs.getObject(13, OffsetDateTime.class), rs.getString(14), rs.getLong(15), rs.getLong(16),
                        rs.getLong(17), rs.getLong(18), rs.getLong(19), rs.getLong(20), rs.getLong(21),
                        rs.getBoolean(22), rs.getObject(23, OffsetDateTime.class),
                        rs.getLong(24), rs.getLong(25), rs.getString(26), rs.getString(27))).list();
        // Merchant names come from the merchant module, not a join on its table.
        Map<UUID, String> names = new HashMap<>();
        lines = lines.stream().map(a -> a.withMerchantName(names.computeIfAbsent(a.merchantId(), this::tradingName)))
                .toList();
        head[3] = names.computeIfAbsent((UUID) head[2], this::tradingName);
        return new Checkout((UUID) head[0], (String) head[1], (UUID) head[2], (String) head[3], (UUID) head[4],
                (String) head[5], (long) head[6], (String) head[7], (String) head[8], (long) head[9], (Long) head[10],
                (UUID) head[11], (UUID) head[12], (OffsetDateTime) head[13], (OffsetDateTime) head[14],
                (OffsetDateTime) head[15], (OffsetDateTime) head[16], lines);
    }

    private String tradingName(UUID merchant) {
        return merchants.find(merchant).map(MerchantAccess.Merchant::tradingName).orElse(null);
    }

    int deliveryConfirmDays(UUID marketplace) {
        return db.sql("SELECT delivery_confirm_days FROM marketplace_fee_schedule WHERE marketplace_id = :m")
                .param("m", marketplace).query(Integer.class).optional()
                .orElseGet(config::defaultDeliveryConfirmDays);
    }

    /** Starts returning money to the customer through the rail that took the payment. */
    UUID railRefund(Checkout c, UUID refundId, long amount) {
        UUID charge = db.sql("SELECT rail_tx FROM payment_attempt WHERE checkout_id = :c AND status = 'succeeded'")
                .param("c", c.id()).query(UUID.class).single();
        var original = rails.find(charge).orElseThrow();
        return rails.create(original.rail(), "refund", "refund", refundId, amount, new RailTransactions.Request(
                original.channel(), original.msisdn(), null, null, null, "Refund " + c.reference(), null, null,
                null, null, original.reference(), original.railReference())).id();
    }

    static Allocation allocation(Checkout c, int line) {
        return c.allocations().stream().filter(x -> x.line() == line).findFirst()
                .orElseThrow(() -> ApiException.notFound("Allocation not found"));
    }

    /** Debit legs that pull a {@link Take} out of the seller, ELEPAY and marketplace accounts. */
    static List<Leg> takeLegs(Ledger ledger, Checkout c, Allocation a, Take t) {
        List<Leg> legs = new ArrayList<>();
        if (t.sellerFromPending() > 0) legs.add(Leg.debit(Ledger.code("merchant", a.merchantId(), "pending", Ledger.GHS), t.sellerFromPending()));
        if (t.sellerFromReserved() > 0) legs.add(Leg.debit(Ledger.code("merchant", a.merchantId(), "reserved", Ledger.GHS), t.sellerFromReserved()));
        if (t.sellerFromAvailable() > 0) legs.add(Leg.debit(Ledger.code("merchant", a.merchantId(), "available", Ledger.GHS), t.sellerFromAvailable()));
        if (t.feeFromDeferred() > 0) {
            String deferredStripe = ledger.openStripedSystem(Ledger.DEFERRED_REVENUE, "deferred_revenue", Ledger.GHS, true, c.id());
            legs.add(Leg.debit(deferredStripe, t.feeFromDeferred()));
        }
        if (t.feeFromRevenue() > 0) {
            ledger.openStripedSystem(Ledger.REVENUE, "revenue", Ledger.GHS, false, c.id());
            legs.addAll(ledger.debitStripeOrAcrossStripes(Ledger.REVENUE, c.id(), t.feeFromRevenue()));
        }
        if (t.platformFromPending() > 0) legs.add(Leg.debit(Ledger.code("merchant", c.collectorId(), "pending", Ledger.GHS), t.platformFromPending()));
        if (t.platformFromAvailable() > 0) legs.add(Leg.debit(Ledger.code("merchant", c.collectorId(), "available", Ledger.GHS), t.platformFromAvailable()));
        return legs;
    }

    static Set<UUID> recipients(Checkout c) {
        Set<UUID> ids = new LinkedHashSet<>();
        ids.add(c.collectorId());
        c.allocations().forEach(a -> ids.add(a.merchantId()));
        return ids;
    }

    static Set<UUID> setOf(UUID... ids) {
        Set<UUID> set = new LinkedHashSet<>();
        for (UUID id : ids) {
            if (id != null) set.add(id);
        }
        return Collections.unmodifiableSet(set);
    }
}
