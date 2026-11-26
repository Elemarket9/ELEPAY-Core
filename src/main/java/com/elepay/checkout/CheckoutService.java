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
import com.elepay.operations.EmtechCapture;
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

    private final JdbcClient db;
    private final Ledger ledger;
    private final MerchantAccess merchants;
    private final RailTransactions rails;
    private final Outbox outbox;
    private final Audit audit;
    private final OpsQueue ops;
    private final Map<String, int[]> customerFees;
    private final boolean jobsEnabled;
    private final CustomerDirectory customers;
    private final CheckoutConfigService config;
    private final EmtechCapture regulatory;
    private com.elepay.identity.KillSwitchService killSwitch;
    private org.springframework.context.ApplicationEventPublisher events;
    private com.elepay.wallet.TransferLimits transferLimits;
    private com.elepay.risk.RiskEngineService riskEngine;
    private com.elepay.common.ScheduledJobLock jobLock;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setKillSwitch(com.elepay.identity.KillSwitchService killSwitch) {
        this.killSwitch = killSwitch;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransferLimits(com.elepay.wallet.TransferLimits transferLimits) {
        this.transferLimits = transferLimits;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRiskEngine(com.elepay.risk.RiskEngineService riskEngine) {
        this.riskEngine = riskEngine;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJobLock(com.elepay.common.ScheduledJobLock jobLock) {
        this.jobLock = jobLock;
    }

    CheckoutService(JdbcClient db, Ledger ledger, MerchantAccess merchants, RailTransactions rails, Outbox outbox,
                    Audit audit, OpsQueue ops, CustomerDirectory customers, CheckoutConfigService config,
                    EmtechCapture regulatory,
                    @Value("${elepay.fees.customer.wallet-bps:0}") int walletBps,
                    @Value("${elepay.fees.customer.wallet-fixed:0}") int walletFixed,
                    @Value("${elepay.fees.customer.momo-bps:0}") int momoBps,
                    @Value("${elepay.fees.customer.momo-fixed:0}") int momoFixed,
                    @Value("${elepay.fees.customer.card-bps:0}") int cardBps,
                    @Value("${elepay.fees.customer.card-fixed:0}") int cardFixed,
                    @Value("${elepay.checkout.jobs-enabled:true}") boolean jobsEnabled) {
        this.db = db;
        this.customers = customers;
        this.regulatory = regulatory;
        this.ledger = ledger;
        this.merchants = merchants;
        this.rails = rails;
        this.outbox = outbox;
        this.audit = audit;
        this.ops = ops;
        this.config = config;
        this.customerFees = Map.of("wallet", new int[]{walletBps, walletFixed},
                "momo", new int[]{momoBps, momoFixed}, "card", new int[]{cardBps, cardFixed});
        this.jobsEnabled = jobsEnabled;
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

    static final java.util.regex.Pattern CATEGORY = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) return null;
        String c = category.trim().toLowerCase(Locale.ROOT);
        if (!CATEGORY.matcher(c).matches()) {
            throw ApiException.badRequest("invalid_category", "Category must be lowercase letters, digits, - or _");
        }
        return c;
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

    int deliveryConfirmDays(UUID marketplace) {
        return db.sql("SELECT delivery_confirm_days FROM marketplace_fee_schedule WHERE marketplace_id = :m")
                .param("m", marketplace).query(Integer.class).optional()
                .orElseGet(config::defaultDeliveryConfirmDays);
    }

    long customerFee(String method, long subtotal) {
        return config.calculateCustomerFee(method, subtotal);
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
            String category = normalizeCategory(a.category());
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
        String boundPhone = db.sql("SELECT payer_phone FROM checkout WHERE reference = :r")
                .param("r", reference).query(String.class).optional().orElse(null);
        if (boundPhone != null) {
            String customerPhone = customers.phoneOf(customer).orElse(null);
            if (customerPhone == null || !boundPhone.equals(customerPhone)) {
                throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                        "payer_phone_mismatch", "This checkout is bound to a different phone number");
            }
        }
        long fee = customerFee("wallet", c.subtotal());
        long total = Math.addExact(c.subtotal(), fee);
        if (transferLimits != null) {
            transferLimits.check(customer, total);
        }
        if (riskEngine != null) {
            var assessment = riskEngine.assessTransaction(new com.elepay.risk.RiskEngineService.AssessmentRequest(
                    customer.toString(), c.reference(), "CHECKOUT_WALLET", total, Ledger.GHS,
                    c.collectorId().toString(), null, null, null, null, null, false, false, false, false, false, null));
            if (assessment.decision() == com.elepay.risk.RiskDecision.REJECT) {
                throw ApiException.conflict("risk_blocked", "Checkout blocked by risk engine: " + assessment.explanation());
            }
        }
        UUID attempt = UUID.randomUUID();
        db.sql("INSERT INTO payment_attempt (id, checkout_id, method, status) VALUES (:id, :c, 'wallet', 'succeeded')")
                .param("id", attempt).param("c", c.id()).update();
        complete(c, "wallet", CustomerDirectory.available(customer), customer, fee, total);
        UUID journal = db.sql("SELECT journal_tx FROM checkout WHERE id = :c").param("c", c.id()).query(UUID.class).single();
        customers.recordOperation(References.next("WLT"), customer, "payment", total, "completed",
                c.collectorName() + " " + c.reference(), journal, c.id());
        return new PaymentResult(c.reference(), "paid", "wallet", c.subtotal(), fee, total, attempt, null, null, null);
    }

    /** Records a rail charge for the checkout, or returns the attempt already in flight. */
    @Transactional
    PaymentResult startRailPayment(String reference, String method, String msisdn, String network, String email,
                                   String returnUrl) {
        Checkout current = load(reference, true);
        if (current.status().equals("paid")) return status(reference);
        String boundPhone = db.sql("SELECT payer_phone FROM checkout WHERE reference = :r")
                .param("r", reference).query(String.class).optional().orElse(null);
        if (boundPhone != null && (msisdn == null || !boundPhone.equals(msisdn))) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "payer_phone_mismatch", "This checkout is bound to a different phone number");
        }
        var inFlight = db.sql("SELECT id FROM payment_attempt WHERE checkout_id = :c AND status = 'pending'")
                .param("c", current.id()).query(UUID.class).optional();
        if (inFlight.isPresent()) return status(reference);
        Checkout c = lockPayable(reference);
        long fee = customerFee(method, c.subtotal());
        long total = Math.addExact(c.subtotal(), fee);
        if (riskEngine != null) {
            String actorId = msisdn != null ? msisdn : (email != null ? email : c.reference());
            var assessment = riskEngine.assessTransaction(new com.elepay.risk.RiskEngineService.AssessmentRequest(
                    actorId, c.reference(), "CHECKOUT_" + method.toUpperCase(Locale.ROOT), total, Ledger.GHS,
                    c.collectorId().toString(), null, null, msisdn, network, null, false, false, false, false, false, null));
            if (assessment.decision() == com.elepay.risk.RiskDecision.REJECT) {
                throw ApiException.conflict("risk_blocked", "Checkout blocked by risk engine: " + assessment.explanation());
            }
        }
        UUID attempt = UUID.randomUUID();
        var tx = rails.create(rails.defaultRail(), "charge", "checkout", attempt, total,
                new RailTransactions.Request(method, msisdn, network, email, null,
                        c.collectorName() + " " + c.reference(), returnUrl, null, null, null, null, null));
        db.sql("INSERT INTO payment_attempt (id, checkout_id, method, rail_tx, status) VALUES (:id, :c, :m, :tx, 'pending')")
                .param("id", attempt).param("c", c.id()).param("m", method).param("tx", tx.id()).update();
        db.sql("UPDATE checkout SET status = 'processing', payment_method = :m, customer_fee = :fee WHERE id = :id")
                .param("m", method).param("fee", fee).param("id", c.id()).update();
        String msg = "ghqr".equals(method) ? "Scan the GhQR code to complete payment"
                : "Approve the payment on your phone";
        return new PaymentResult(c.reference(), "processing", method, c.subtotal(), fee, total, attempt, "created", null, msg);
    }

    @Transactional
    void resetProcessing(UUID merchantId, String reference) {
        Checkout c = load(reference, true);
        if (!c.collectorId().equals(merchantId)) {
            throw ApiException.notFound("Checkout not found");
        }
        if (c.status().equals("paid")) throw ApiException.conflict("already_paid", "A paid checkout cannot be reset");
        List<UUID> pendingRailTxs = db.sql("""
                SELECT rail_tx FROM payment_attempt
                WHERE checkout_id = :c AND status = 'pending' AND rail_tx IS NOT NULL""")
                .param("c", c.id()).query(UUID.class).list();
        for (UUID railTxId : pendingRailTxs) {
            var txOpt = rails.find(railTxId);
            if (txOpt.isPresent()) {
                String rs = txOpt.get().status();
                if ("created".equals(rs) || "pending".equals(rs) || "unknown".equals(rs) || "succeeded".equals(rs)) {
                    throw ApiException.conflict("rail_in_flight",
                            "Cannot reset checkout while payment rail transaction is " + rs + "; wait for finality or run rail recovery");
                }
            }
        }
        db.sql("UPDATE payment_attempt SET status = 'failed' WHERE checkout_id = :c AND status = 'pending'")
                .param("c", c.id()).update();
        db.sql("UPDATE checkout SET status = 'open', payment_method = NULL WHERE id = :id AND status = 'processing'")
                .param("id", c.id()).update();
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

    /**
     * Posts the payment: source debited once; each allocation's net to the merchant's pending bucket, the
     * marketplace's commission and processing fee to the marketplace's pending bucket, and ELEPAY's merchant
     * fee to deferred revenue. Nothing is earned until the line settles. The customer fee is ELEPAY's charge
     * to the payer, not merchant funds, and is recognised now.
     */
    private void complete(Checkout c, String method, String source, UUID customer, long customerFee, long total) {
        List<Leg> legs = new ArrayList<>();
        legs.add(Leg.debit(source, total));
        long deferred = 0;
        long platform = 0;
        boolean held = false;
        for (Allocation a : c.allocations()) {
            if (a.net() > 0) {
                legs.add(Leg.credit(ledger.open("merchant", a.merchantId(), "pending", Ledger.GHS), a.net()));
            }
            if (a.reserveAmount() > 0) {
                legs.add(Leg.credit(ledger.open("merchant", a.merchantId(), "reserved", Ledger.GHS), a.reserveAmount()));
            }
            platform = Math.addExact(platform, a.platformFee());
            deferred = Math.addExact(deferred, a.merchantFee());
            held |= a.holdUntilDelivered();
        }
        if (platform > 0) {
            legs.add(Leg.credit(ledger.open("merchant", c.collectorId(), "pending", Ledger.GHS), platform));
        }
        if (deferred > 0) {
            legs.add(Leg.credit(ledger.openSystem(Ledger.DEFERRED_REVENUE, "deferred_revenue", Ledger.GHS, true), deferred));
        }
        if (customerFee > 0) {
            legs.add(Leg.credit(ledger.openSystem(Ledger.REVENUE, "revenue", Ledger.GHS, false), customerFee));
        }
        var posted = ledger.post(Ledger.Posting.of("checkout-payment", c.id().toString(), "checkout_payment", legs)
                .describedAs(c.reference()).withMetadata(Map.of("checkout", c.reference(), "method", method)));
        for (Allocation a : c.allocations()) {
            db.sql("""
                    UPDATE checkout_allocation SET pending_amount = :p, platform_pending = :pp, fee_deferred = :fd
                    WHERE checkout_id = :c AND line = :l""")
                    .param("p", a.net()).param("pp", a.platformFee()).param("fd", a.merchantFee())
                    .param("c", c.id()).param("l", a.line()).update();
        }
        if (held) {
            db.sql("UPDATE checkout SET delivery_deadline = now() + make_interval(days => :d) WHERE id = :id")
                    .param("d", deliveryConfirmDays(c.collectorId())).param("id", c.id()).update();
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
        regulatory.paidCheckout(c.id());
        if (events != null) {
            String channel = db.sql("SELECT channel FROM checkout WHERE id = :id")
                    .param("id", c.id()).query(String.class).optional().orElse(null);
            events.publishEvent(new CheckoutPaidEvent(c.id(), c.reference(), posted.transactionId(), total, Ledger.GHS, channel));
        }
    }

    /** Stamps the customer channel. Web and app are not inferred from the handset's location. */
    void noteChannel(String reference, String channel) {
        if (channel == null || channel.isBlank()) return;
        db.sql("UPDATE checkout SET channel = :c WHERE reference = :r").param("c", channel).param("r", reference).update();
    }

    private static Set<UUID> recipients(Checkout c) {
        Set<UUID> ids = new LinkedHashSet<>();
        ids.add(c.collectorId());
        c.allocations().forEach(a -> ids.add(a.merchantId()));
        return ids;
    }

    @EventListener
    @Transactional
    void onRailSucceeded(RailEvents.Succeeded event) {
        if (!event.purpose().equals("checkout")) return;
        var attempt = db.sql("SELECT checkout_id, method FROM payment_attempt WHERE id = :id")
                .param("id", event.purposeId())
                .query((rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)})
                .single();
        UUID checkoutId = (UUID) attempt[0];
        String attemptMethod = (String) attempt[1];
        Checkout c = load(checkoutId, true);
        db.sql("UPDATE payment_attempt SET status = 'succeeded' WHERE id = :id").param("id", event.purposeId()).update();
        if (c.status().equals("paid")) {
            // Defensive: the unique active-attempt index makes this unreachable; never credit twice.
            ops.raise("checkout_double_payment", "high", "checkout:" + c.reference(), Map.of("railTx", event.railTxId().toString()));
            ledger.post(Ledger.Posting.of("checkout-overpayment", event.railTxId().toString(), "suspense", List.of(
                    Leg.debit(Ledger.clearing(event.rail()), event.amount()), Leg.credit(Ledger.SUSPENSE, event.amount()))));
            return;
        }
        String method = c.paymentMethod() != null ? c.paymentMethod() : attemptMethod;
        String clearing = ledger.openSystem(Ledger.clearing(event.rail()), "clearing", Ledger.GHS, true);
        complete(c, method, clearing, null, c.customerFee(), event.amount());
    }

    @EventListener
    @Transactional
    void onRailFailed(RailEvents.Failed event) {
        if (!event.purpose().equals("checkout")) return;
        UUID checkoutId = db.sql("SELECT checkout_id FROM payment_attempt WHERE id = :id")
                .param("id", event.purposeId()).query(UUID.class).optional().orElse(null);
        if (checkoutId == null) return;
        load(checkoutId, true);
        db.sql("UPDATE payment_attempt SET status = 'failed' WHERE id = :id").param("id", event.purposeId()).update();
        db.sql("UPDATE checkout SET status = 'open', payment_method = NULL WHERE id = :id AND status = 'processing'")
                .param("id", checkoutId).update();
        regulatory.failedCheckout(checkoutId, event.reason());
    }

    // ---- taking money back from a line ------------------------------------------------------

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

    static Take take(Allocation a, long amount) {
        boolean last = a.refunded() + a.disputed() + amount == a.amount();
        long feeLeft = a.merchantFee() - a.refundedFee() - a.disputedFee();
        long platformLeft = a.platformFee() - a.refundedPlatformFee() - a.disputedPlatformFee();
        long fee = last ? feeLeft : Math.min(feeLeft, Math.multiplyExact(a.merchantFee(), amount) / a.amount());
        long platform = last ? platformLeft
                : Math.min(platformLeft, Math.multiplyExact(a.platformFee(), amount) / a.amount());
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

    /** Debit legs that pull a {@link Take} out of the seller, ELEPAY and marketplace accounts. */
    private static List<Leg> takeLegs(Checkout c, Allocation a, Take t) {
        List<Leg> legs = new ArrayList<>();
        if (t.sellerFromPending() > 0) legs.add(Leg.debit(Ledger.code("merchant", a.merchantId(), "pending", Ledger.GHS), t.sellerFromPending()));
        if (t.sellerFromReserved() > 0) legs.add(Leg.debit(Ledger.code("merchant", a.merchantId(), "reserved", Ledger.GHS), t.sellerFromReserved()));
        if (t.sellerFromAvailable() > 0) legs.add(Leg.debit(Ledger.code("merchant", a.merchantId(), "available", Ledger.GHS), t.sellerFromAvailable()));
        if (t.feeFromDeferred() > 0) legs.add(Leg.debit(Ledger.DEFERRED_REVENUE, t.feeFromDeferred()));
        if (t.feeFromRevenue() > 0) legs.add(Leg.debit(Ledger.REVENUE, t.feeFromRevenue()));
        if (t.platformFromPending() > 0) legs.add(Leg.debit(Ledger.code("merchant", c.collectorId(), "pending", Ledger.GHS), t.platformFromPending()));
        if (t.platformFromAvailable() > 0) legs.add(Leg.debit(Ledger.code("merchant", c.collectorId(), "available", Ledger.GHS), t.platformFromAvailable()));
        return legs;
    }

    /** Starts returning money to the customer through the rail that took the payment. */
    private UUID railRefund(Checkout c, UUID refundId, long amount) {
        UUID charge = db.sql("SELECT rail_tx FROM payment_attempt WHERE checkout_id = :c AND status = 'succeeded'")
                .param("c", c.id()).query(UUID.class).single();
        var original = rails.find(charge).orElseThrow();
        return rails.create(original.rail(), "refund", "refund", refundId, amount, new RailTransactions.Request(
                original.channel(), original.msisdn(), null, null, null, "Refund " + c.reference(), null, null,
                null, null, original.reference(), original.railReference())).id();
    }

    // ---- refunds ----------------------------------------------------------------------------

    record Refund(UUID id, String reference, String checkout, int line, long amount, long feeReturned,
                  long platformFeeReturned, String destination, String status, UUID journalTx) {}

    @Transactional
    Refund refund(MerchantAccess.Access access, String reference, int line, long amount, String reason) {
        if (killSwitch != null) killSwitch.requirePayoutsAllowed(access.merchantId());
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
        Take t = take(a, amount);
        UUID id = UUID.randomUUID();
        String refundRef = References.next("RF");
        boolean toWallet = c.paymentMethod().equals("wallet");
        List<Leg> legs = takeLegs(c, a, t);
        legs.add(Leg.credit(toWallet ? CustomerDirectory.available(c.customerId()) : Ledger.REFUNDS_IN_FLIGHT, amount));
        var posted = ledger.post(Ledger.Posting.of("refund", id.toString(), "refund", legs)
                .relatedTo(c.journalTx()).describedAs(refundRef)
                .withMetadata(Map.of("checkout", c.reference(), "line", String.valueOf(line))));
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
        UUID railTx = toWallet ? null : railRefund(c, id, amount);
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
                setOf(a.merchantId(), c.collectorId()));
        if (toWallet) regulatory.refund(id);
        return new Refund(id, refundRef, c.reference(), line, amount, t.fee(), t.platform(),
                toWallet ? "wallet" : "rail", toWallet ? "completed" : "pending", posted.transactionId());
    }

    UUID pendingRefundRailTx(UUID refundId) {
        return db.sql("SELECT rail_tx FROM refund WHERE id = :id").param("id", refundId).query(UUID.class).single();
    }

    @EventListener
    @Transactional
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
        regulatory.refund(event.purposeId());
    }

    @EventListener
    @Transactional
    void onRefundRailFailed(RailEvents.Failed event) {
        if (!event.purpose().equals("refund")) return;
        int failed = db.sql("UPDATE refund SET status = 'failed' WHERE id = :id AND status = 'pending'")
                .param("id", event.purposeId()).update();
        if (failed == 1) regulatory.refund(event.purposeId());
        ops.raise("refund_delivery_failed", "high", "refund:" + event.purposeId(), Map.of(
                "amount", event.amount(), "reason", String.valueOf(event.reason()),
                "note", "Funds are held in refunds_in_flight; retry or credit the customer's wallet"));
    }

    private static Allocation allocation(Checkout c, int line) {
        return c.allocations().stream().filter(x -> x.line() == line).findFirst()
                .orElseThrow(() -> ApiException.notFound("Allocation not found"));
    }

    // ---- delivery ---------------------------------------------------------------------------

    /**
     * The marketplace confirms that lines were delivered (all held lines when {@code lines} is empty). A
     * delivered line becomes due for settlement after the seller's settlement delay; with a delay of zero
     * the next settlement run releases it. Repeat confirmations are no-ops.
     */
    @Transactional
    Checkout confirmDelivery(UUID collector, String reference, List<Integer> lines, String actor) {
        Checkout c = load(reference, true);
        if (!c.collectorId().equals(collector)) throw ApiException.notFound("Checkout not found");
        if (!c.status().equals("paid")) throw ApiException.conflict("not_paid", "Only paid orders can be delivered");
        List<Allocation> targets;
        if (lines == null || lines.isEmpty()) {
            targets = c.allocations().stream().filter(Allocation::holdUntilDelivered).toList();
        } else {
            targets = lines.stream().distinct().map(l -> allocation(c, l)).toList();
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
                    Map.of("reference", reference, "lines", confirmed), recipients(c));
        }
        return load(c.id(), false);
    }

    // ---- disputes ---------------------------------------------------------------------------

    record Dispute(UUID id, String checkout, int line, long amount, String reason, String status, UUID holdTx,
                   UUID resolutionTx, String refund) {}

    @Transactional
    Dispute openDispute(String actor, String reference, int line, long amount, String reason, String providerCase) {
        Checkout c = load(reference, true);
        Allocation a = allocation(c, line);
        if (!c.status().equals("paid")) throw ApiException.conflict("not_paid", "Only paid checkouts can be disputed");
        if (amount <= 0 || Math.addExact(Math.addExact(a.refunded(), a.disputed()), amount) > a.amount()) {
            throw ApiException.conflict("dispute_exceeds_allocation", "Dispute exceeds the remaining allocation");
        }
        Take t = take(a, amount);
        String reserved = Ledger.code("merchant", a.merchantId(), "reserved", Ledger.GHS);
        UUID id = UUID.randomUUID();
        List<Leg> legs = takeLegs(c, a, t);
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
                "line", line, "amount", amount), setOf(a.merchantId(), c.collectorId()));
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
        Checkout c = load(d.checkout(), true);
        Allocation a = allocation(c, d.line());
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
            UUID railTx = toWallet ? null : railRefund(c, refundId, d.amount());
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
                "checkout", c.reference()), setOf(a.merchantId(), c.collectorId()));
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

    // ---- settlement -------------------------------------------------------------------------

    record SettlementBatch(UUID id, String reference, UUID merchantId, long amount, int lines, UUID journalTx,
                           OffsetDateTime createdAt) {}

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
        Map<String, Long> feesByCurrency = new LinkedHashMap<>();
        for (Due d : due) {
            if (d.fee() > 0) {
                feesByCurrency.merge(d.currency(), d.fee(), Math::addExact);
            }
        }
        Map<String, UUID> feeTxByCurrency = new LinkedHashMap<>();
        for (var entry : feesByCurrency.entrySet()) {
            String cur = entry.getKey();
            long curFees = entry.getValue();
            UUID run = UUID.randomUUID();
            String deferredAcc = ledger.openSystem("system:deferred_revenue:" + cur, "deferred_revenue", cur, true);
            String revenueAcc = ledger.openSystem("system:revenue:" + cur, "revenue", cur, false);
            UUID txId = ledger.post(Ledger.Posting.of("fee-recognition", run.toString(), "fee_recognition", List.of(
                    Leg.debit(deferredAcc, curFees), Leg.credit(revenueAcc, curFees)))
                    .describedAs("Fees earned on settled lines (" + cur + ")")).transactionId();
            feeTxByCurrency.put(cur, txId);
        }
        for (Due d : due) {
            SettlementBatch batch = byBatch.get(new BatchKey(d.pending() > 0 ? d.merchant() : d.collector(), d.currency()));
            db.sql("""
                    UPDATE checkout_allocation SET settled_amount = settled_amount + pending_amount, pending_amount = 0,
                           platform_settled = platform_settled + platform_pending, platform_pending = 0,
                           fee_recognized = fee_recognized + fee_deferred, fee_deferred = 0,
                           settled_at = now(), settlement_tx = :tx, settlement_batch = :batch
                    WHERE checkout_id = :c AND line = :l""")
                    .param("tx", batch != null ? batch.journalTx() : feeTxByCurrency.get(d.currency()))
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

    // ---- marketplace fee schedule -----------------------------------------------------------

    record FeeSchedule(UUID marketplaceId, int deliveryConfirmDays, int reserveBps, int version, List<FeeRule> rules) {
        FeeSchedule(UUID marketplaceId, int deliveryConfirmDays, List<FeeRule> rules) {
            this(marketplaceId, deliveryConfirmDays, 0, 1, rules);
        }
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
            String category = r.category() == null || r.category().equals("*") ? "*" : normalizeCategory(r.category());
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

    FeeSchedule saveFeeSchedule(UUID marketplace, int deliveryConfirmDays, List<FeeRule> rules, String actor) {
        return saveFeeSchedule(marketplace, deliveryConfirmDays, 0, rules, null, actor);
    }

    record ReserveRelease(UUID id, UUID checkoutId, int line, UUID merchantId, long amount, UUID journalTx, String status) {}

    @Transactional
    ReserveRelease releaseReserve(UUID merchantId, String reference, int line, long amount, String reason, String actor) {
        Checkout c = load(reference, true);
        Allocation a = allocation(c, line);
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
        List<Leg> legs = List.of(
                Leg.debit(reserved, amount),
                Leg.credit(target, amount)
        );

        var posted = ledger.post(Ledger.Posting.of("reserve-release", releaseId.toString(), "reserve_release", legs)
                .describedAs("Reserve release for checkout " + reference + " line " + line)
                .withMetadata(Map.of("checkout", reference, "line", String.valueOf(line), "merchant", a.merchantId().toString())));

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
                Map.of("reference", reference, "line", line, "amount", amount), setOf(a.merchantId(), c.collectorId()));

        return new ReserveRelease(releaseId, c.id(), line, a.merchantId(), amount, posted.transactionId(), releaseStatus);
    }

    @Scheduled(fixedDelayString = "${elepay.checkout.job-ms:60000}")
    @Transactional
    void jobs() {
        if (!jobsEnabled) return;
        if (jobLock != null && !jobLock.tryLockInCurrentTx("checkout-jobs")) return;
        settleDue(false, null, null);
        db.sql("""
                UPDATE checkout SET status = 'expired'
                WHERE id IN (
                    SELECT id FROM checkout
                    WHERE status = 'open' AND expires_at < now()
                    ORDER BY expires_at ASC
                    LIMIT 1000
                    FOR UPDATE SKIP LOCKED
                )""").update();
    }

    private static Set<UUID> setOf(UUID... ids) {
        Set<UUID> set = new LinkedHashSet<>();
        for (UUID id : ids) {
            if (id != null) set.add(id);
        }
        return Collections.unmodifiableSet(set);
    }
}
