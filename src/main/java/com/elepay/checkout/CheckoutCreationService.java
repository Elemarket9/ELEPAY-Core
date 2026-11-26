package com.elepay.checkout;

import com.elepay.checkout.CheckoutService.AllocationRequest;
import com.elepay.checkout.CheckoutService.Checkout;
import com.elepay.checkout.CheckoutService.FeeRule;
import com.elepay.checkout.CheckoutService.FeeSchedule;
import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.References;
import com.elepay.merchant.MerchantAccess;
import com.elepay.outbox.Outbox;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checkout creation, cancellation, split/seller validation, fee &amp; reserve calculation,
 * and versioned marketplace fee schedules.
 */
@Service
class CheckoutCreationService {
    private final JdbcClient db;
    private final MerchantAccess merchants;
    private final Outbox outbox;
    private final Audit audit;
    private final CheckoutConfigService config;
    private final CheckoutStore store;

    CheckoutCreationService(JdbcClient db, MerchantAccess merchants, Outbox outbox, Audit audit,
                            CheckoutConfigService config, CheckoutStore store) {
        this.db = db;
        this.merchants = merchants;
        this.outbox = outbox;
        this.audit = audit;
        this.config = config;
        this.store = store;
    }

    /** The category's rule, else the marketplace's default ('*') rule, else no commission. */
    FeeRule feeRule(UUID marketplace, String category) {
        return db.sql("""
                SELECT category, commission_bps, commission_min, processing_fee, COALESCE(reserve_bps, 0)
                FROM marketplace_fee_rule
                WHERE marketplace_id = :m AND category IN (:c, '*')
                ORDER BY (category = '*') LIMIT 1""")
                .param("m", marketplace).param("c", category == null ? "*" : category)
                .query((rs, i) -> new FeeRule(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getLong(4), rs.getInt(5)))
                .optional().orElse(FeeRule.NONE);
    }

    long customerFee(String method, long subtotal) {
        return config.calculateCustomerFee(method, subtotal);
    }

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
        int maxAllocations = config.maxAllocations();
        long maxTotal = config.maxTotal();
        if (requested == null || requested.isEmpty() || requested.size() > maxAllocations) {
            throw ApiException.badRequest("invalid_allocations", "A checkout needs 1 to " + maxAllocations + " allocations");
        }
        if (expiresInMinutes < 1 || expiresInMinutes > 7 * 24 * 60) {
            throw ApiException.badRequest("invalid_expiry", "Expiry must be between 1 minute and 7 days");
        }
        long subtotal = 0;
        List<long[]> fees = new ArrayList<>();
        List<String> categories = new ArrayList<>();
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
            String category = CheckoutService.normalizeCategory(a.category());
            boolean marketplaceLine = collector.kind().equals("marketplace") && !recipient.id().equals(collectorId);
            long merchantFee = MerchantAccess.fee(recipient, a.amount());
            long commission = 0;
            long processing = 0;
            long reserve = 0;
            if (a.reserveBps() != null && (a.reserveBps() < 0 || a.reserveBps() > 5000)) {
                throw ApiException.badRequest("invalid_reserve_bps", "Reserve must be 0 to 50%");
            }
            if (marketplaceLine && kind.equals("sale")) {
                FeeRule rule = feeRule(collectorId, category);
                commission = rule.commission(a.amount());
                processing = rule.processingFee();
                reserve = a.reserveBps() != null ? Math.multiplyExact(a.amount(), (long) a.reserveBps()) / 10000L : rule.reserve(a.amount());
            } else if (a.reserveBps() != null && a.reserveBps() > 0) {
                reserve = Math.multiplyExact(a.amount(), (long) a.reserveBps()) / 10000L;
            }
            if (Math.addExact(Math.addExact(merchantFee, commission), Math.addExact(processing, reserve)) > a.amount()) {
                throw ApiException.badRequest("fees_exceed_amount", "Commission, fees and reserves exceed the price of "
                        + (a.description() == null ? "an item" : a.description()));
            }
            subtotal = Math.addExact(subtotal, a.amount());
            fees.add(new long[]{merchantFee, commission, processing, marketplaceLine ? 1 : 0, reserve});
            categories.add(category);
        }
        if (subtotal > maxTotal) throw ApiException.badRequest("amount_too_large", "Checkout total exceeds the limit");
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
            long[] f = fees.get(line);
            db.sql("""
                    INSERT INTO checkout_allocation (checkout_id, line, merchant_id, kind, description, amount, merchant_fee,
                        category, commission, processing_fee, hold_until_delivered, reserve_amount, reserve_released,
                        release_condition, release_status)
                    VALUES (:id, :line, :m, :kind, :d, :amount, :fee, :category, :commission, :processing, :hold,
                        :res, 0, :cond, :rstat)""")
                    .param("id", id).param("line", line).param("m", a.merchantId())
                    .param("kind", a.kind() == null ? "sale" : a.kind()).param("d", a.description())
                    .param("amount", a.amount()).param("fee", f[0]).param("category", categories.get(line))
                    .param("commission", f[1]).param("processing", f[2])
                    .param("hold", f[3] == 1).param("res", f[4])
                    .param("cond", a.releaseCondition() != null ? a.releaseCondition() : "DELIVERY_CONFIRMED")
                    .param("rstat", f[3] == 1 || f[4] > 0 ? "HELD" : "RELEASED").update();
        }
        return store.load(id, false);
    }

    @Transactional
    Checkout cancel(UUID collector, UUID branch, String reference, String actor) {
        Checkout c = store.load(reference, true);
        if (!c.collectorId().equals(collector) || (branch != null && !branch.equals(c.branchId()))) {
            throw ApiException.notFound("Checkout not found");
        }
        if (c.status().equals("cancelled")) return c;
        if (!c.status().equals("open") && !c.status().equals("expired")) {
            throw ApiException.conflict("not_cancellable", "A " + c.status() + " checkout cannot be cancelled");
        }
        db.sql("UPDATE checkout SET status = 'cancelled' WHERE id = :id").param("id", c.id()).update();
        audit.record(actor, "checkout.cancelled", "checkout:" + reference, Map.of());
        outbox.publish("checkout", reference, "checkout.cancelled", Map.of("reference", reference), CheckoutStore.recipients(c));
        return store.load(c.id(), false);
    }

    FeeSchedule feeSchedule(UUID marketplace) {
        var sched = db.sql("""
                SELECT delivery_confirm_days, COALESCE(reserve_bps, 0), COALESCE(version, 1)
                FROM marketplace_fee_schedule WHERE marketplace_id = :m""")
                .param("m", marketplace)
                .query((rs, i) -> new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3)})
                .optional().orElse(new int[]{config.defaultDeliveryConfirmDays(), 0, 1});

        List<FeeRule> rules = db.sql("""
                SELECT category, commission_bps, commission_min, processing_fee, COALESCE(reserve_bps, 0)
                FROM marketplace_fee_rule
                WHERE marketplace_id = :m ORDER BY (category = '*') DESC, category""")
                .param("m", marketplace)
                .query((rs, i) -> new FeeRule(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getLong(4), rs.getInt(5))).list();
        return new FeeSchedule(marketplace, sched[0], sched[1], sched[2], rules);
    }

    /** Replaces a marketplace's commission grid with versioning and audit history. */
    @Transactional
    FeeSchedule saveFeeSchedule(UUID marketplace, int deliveryConfirmDays, int reserveBps, List<FeeRule> rules, String reason, String actor) {
        var m = merchants.find(marketplace).orElseThrow(() -> ApiException.notFound("Merchant not found"));
        if (!m.kind().equals("marketplace")) {
            throw ApiException.conflict("not_marketplace", "Only marketplaces set a commission grid");
        }
        if (deliveryConfirmDays < 1 || deliveryConfirmDays > 60) {
            throw ApiException.badRequest("invalid_delivery_days", "Delivery confirmation window must be 1 to 60 days");
        }
        if (reserveBps < 0 || reserveBps > 5000) {
            throw ApiException.badRequest("invalid_reserve_bps", "Reserve must be 0 to 50%");
        }
        Set<String> seen = new HashSet<>();
        List<FeeRule> clean = new ArrayList<>();
        for (FeeRule r : rules == null ? List.<FeeRule>of() : rules) {
            String category = r.category() == null || r.category().equals("*") ? "*" : CheckoutService.normalizeCategory(r.category());
            if (!seen.add(category)) throw ApiException.badRequest("duplicate_category", "Category listed twice: " + category);
            if (r.commissionBps() < 0 || r.commissionBps() > 5000 || r.commissionMin() < 0 || r.processingFee() < 0
                    || r.reserveBps() < 0 || r.reserveBps() > 5000) {
                throw ApiException.badRequest("invalid_rule", "Commission and reserve must be 0 to 50% and fees non-negative");
            }
            clean.add(new FeeRule(category, r.commissionBps(), r.commissionMin(), r.processingFee(), r.reserveBps()));
        }

        int currentVersion = db.sql("SELECT COALESCE(version, 0) FROM marketplace_fee_schedule WHERE marketplace_id = :m")
                .param("m", marketplace).query(Integer.class).optional().orElse(0);
        int nextVersion = currentVersion + 1;

        db.sql("""
                INSERT INTO marketplace_fee_schedule (marketplace_id, delivery_confirm_days, reserve_bps, version, updated_by)
                VALUES (:m, :d, :res, :v, :by) ON CONFLICT (marketplace_id)
                DO UPDATE SET delivery_confirm_days = :d, reserve_bps = :res, version = :v, updated_by = :by, updated_at = now()""")
                .param("m", marketplace).param("d", deliveryConfirmDays).param("res", reserveBps)
                .param("v", nextVersion).param("by", actor).update();
        db.sql("DELETE FROM marketplace_fee_rule WHERE marketplace_id = :m").param("m", marketplace).update();
        for (FeeRule r : clean) {
            db.sql("""
                    INSERT INTO marketplace_fee_rule (marketplace_id, category, commission_bps, commission_min, processing_fee, reserve_bps)
                    VALUES (:m, :c, :bps, :min, :fee, :res)""")
                    .param("m", marketplace).param("c", r.category()).param("bps", r.commissionBps())
                    .param("min", r.commissionMin()).param("fee", r.processingFee()).param("res", r.reserveBps()).update();
        }

        UUID historyId = UUID.randomUUID();
        String rulesSummary = clean.stream().map(r -> r.category() + ":" + r.commissionBps() + "bps+" + r.processingFee() + "+" + r.reserveBps() + "res")
                .reduce((a, b) -> a + ";" + b).orElse("none");
        db.sql("""
                INSERT INTO marketplace_fee_schedule_history (id, marketplace_id, version, delivery_confirm_days, reserve_bps, rules_json, change_reason, created_by)
                VALUES (:id, :m, :v, :d, :res, :rules, :reason, :by)""")
                .param("id", historyId).param("m", marketplace).param("v", nextVersion).param("d", deliveryConfirmDays)
                .param("res", reserveBps).param("rules", rulesSummary).param("reason", reason != null ? reason : "Fee schedule update")
                .param("by", actor).update();

        audit.record(actor, "marketplace.fee_schedule.updated", "merchant:" + marketplace,
                Map.of("rules", clean.size(), "deliveryConfirmDays", deliveryConfirmDays, "reserveBps", reserveBps, "version", nextVersion));
        return feeSchedule(marketplace);
    }

    @Transactional
    FeeSchedule saveFeeSchedule(UUID marketplace, int deliveryConfirmDays, List<FeeRule> rules, String actor) {
        return saveFeeSchedule(marketplace, deliveryConfirmDays, 0, rules, null, actor);
    }
}
