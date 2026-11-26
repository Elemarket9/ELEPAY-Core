package com.elepay.checkout;

import com.elepay.checkout.CheckoutService.Allocation;
import com.elepay.checkout.CheckoutService.Checkout;
import com.elepay.checkout.CheckoutService.PaymentResult;
import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.OpsQueue;
import com.elepay.common.References;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import com.elepay.operations.EmtechCapture;
import com.elepay.outbox.Outbox;
import com.elepay.rails.RailEvents;
import com.elepay.rails.RailTransactions;
import com.elepay.wallet.CustomerDirectory;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment initiation, wallet/rail settlement completion, multi-merchant allocation posting,
 * processing reset, and rail payment finality listeners.
 */
@Service
class CheckoutCompletionService {
    private final JdbcClient db;
    private final Ledger ledger;
    private final RailTransactions rails;
    private final Outbox outbox;
    private final Audit audit;
    private final OpsQueue ops;
    private final CustomerDirectory customers;
    private final CheckoutConfigService config;
    private final EmtechCapture regulatory;
    private final CheckoutStore store;
    private ApplicationEventPublisher events;
    private com.elepay.wallet.TransferLimits transferLimits;
    private com.elepay.risk.RiskEngineService riskEngine;

    @Autowired(required = false)
    void setEvents(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Autowired(required = false)
    void setTransferLimits(com.elepay.wallet.TransferLimits transferLimits) {
        this.transferLimits = transferLimits;
    }

    @Autowired(required = false)
    void setRiskEngine(com.elepay.risk.RiskEngineService riskEngine) {
        this.riskEngine = riskEngine;
    }

    CheckoutCompletionService(JdbcClient db, Ledger ledger, RailTransactions rails, Outbox outbox,
                              Audit audit, OpsQueue ops, CustomerDirectory customers, CheckoutConfigService config,
                              EmtechCapture regulatory, CheckoutStore store) {
        this.db = db;
        this.ledger = ledger;
        this.rails = rails;
        this.outbox = outbox;
        this.audit = audit;
        this.ops = ops;
        this.customers = customers;
        this.config = config;
        this.regulatory = regulatory;
        this.store = store;
    }

    private Checkout lockPayable(String reference) {
        Checkout c = store.load(reference, true);
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
        long fee = config.calculateCustomerFee("wallet", c.subtotal());
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
        Checkout current = store.load(reference, true);
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
        long fee = config.calculateCustomerFee(method, c.subtotal());
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
        Checkout c = store.load(reference, true);
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
        Checkout c = store.load(reference, false);
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
        long fee = method == null ? 0 : (c.totalPaid() != null ? c.customerFee() : config.calculateCustomerFee(method, c.subtotal()));
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
            legs.add(Leg.credit(ledger.openStripedSystem(Ledger.DEFERRED_REVENUE, "deferred_revenue", Ledger.GHS, true, c.id()), deferred));
        }
        if (customerFee > 0) {
            legs.add(Leg.credit(ledger.openStripedSystem(Ledger.REVENUE, "revenue", Ledger.GHS, false, c.id()), customerFee));
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
                    .param("d", store.deliveryConfirmDays(c.collectorId())).param("id", c.id()).update();
        }
        db.sql("""
                UPDATE checkout SET status = 'paid', payment_method = :m, customer_id = :cust, customer_fee = :fee,
                       total_paid = :total, journal_tx = :tx, paid_at = now() WHERE id = :id""")
                .param("m", method).param("cust", customer).param("fee", customerFee).param("total", total)
                .param("tx", posted.transactionId()).param("id", c.id()).update();
        outbox.publish("checkout", c.reference(), "checkout.paid", Map.of(
                "reference", c.reference(), "method", method, "total", total,
                "allocations", c.allocations().stream().map(a -> Map.of("line", a.line(),
                        "merchantId", a.merchantId().toString(), "amount", a.amount())).toList()), CheckoutStore.recipients(c));
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
        Checkout c = store.load(checkoutId, true);
        db.sql("UPDATE payment_attempt SET status = 'succeeded' WHERE id = :id").param("id", event.purposeId()).update();
        String clearing = ledger.openStripedSystem(Ledger.clearing(event.rail()), "clearing", Ledger.GHS, true, c.id());
        if (c.status().equals("paid")) {
            // Defensive: the unique active-attempt index makes this unreachable; never credit twice.
            ops.raise("checkout_double_payment", "high", "checkout:" + c.reference(), Map.of("railTx", event.railTxId().toString()));
            ledger.post(Ledger.Posting.of("checkout-overpayment", event.railTxId().toString(), "suspense", List.of(
                    Leg.debit(clearing, event.amount()), Leg.credit(Ledger.SUSPENSE, event.amount()))));
            return;
        }
        String method = c.paymentMethod() != null ? c.paymentMethod() : attemptMethod;
        complete(c, method, clearing, null, c.customerFee(), event.amount());
    }

    @EventListener
    @Transactional
    void onRailFailed(RailEvents.Failed event) {
        if (!event.purpose().equals("checkout")) return;
        UUID checkoutId = db.sql("SELECT checkout_id FROM payment_attempt WHERE id = :id")
                .param("id", event.purposeId()).query(UUID.class).optional().orElse(null);
        if (checkoutId == null) return;
        store.load(checkoutId, true);
        db.sql("UPDATE payment_attempt SET status = 'failed' WHERE id = :id").param("id", event.purposeId()).update();
        db.sql("UPDATE checkout SET status = 'open', payment_method = NULL WHERE id = :id AND status = 'processing'")
                .param("id", checkoutId).update();
        regulatory.failedCheckout(checkoutId, event.reason());
    }
}
