package com.elepay.checkout;

import com.elepay.common.ApiException;
import com.elepay.rails.GhQrDynamicService;
import com.elepay.rails.RailEvents;
import com.elepay.rails.RailTransactions;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Public checkout module contract for POS. No direct reads of checkout tables by merchant tools. */
@Service
public class PosCheckoutGateway {
    private final CheckoutService checkouts;
    private final RailTransactions rails;
    private final GhQrDynamicService ghQrDynamicService;
    private final JdbcClient db;
    private final ApplicationEventPublisher events;
    private final String publicBaseUrl;

    PosCheckoutGateway(CheckoutService checkouts,
                       RailTransactions rails,
                       GhQrDynamicService ghQrDynamicService,
                       JdbcClient db,
                       ApplicationEventPublisher events,
                       @Value("${elepay.public-base-url}") String publicBaseUrl) {
        this.checkouts = checkouts;
        this.rails = rails;
        this.ghQrDynamicService = ghQrDynamicService;
        this.db = db;
        this.events = events;
        this.publicBaseUrl = publicBaseUrl;
    }

    public record PaidEvidence(String method, UUID journalTx) {}

    public record CheckoutPaidEvent(String reference, UUID merchantId, UUID branchId, String method, long amount, UUID journalTx) {}

    public record RailStartResult(String reference, String status, UUID attemptId, String railStatus, String redirectUrl, String message) {}

    public record GhQrResult(String qrReference, String payloadText) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public String create(UUID merchant, UUID branch, UUID linkId, long amount, String actor) {
        return createSplitAware(merchant, branch, linkId, amount, null, actor);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String createSplitAware(UUID merchant, UUID branch, UUID linkId, long amount, String splitKey, String actor) {
        String orderRef = (splitKey == null || splitKey.isBlank()) ? "pos:" + linkId : "pos:" + linkId + ":" + splitKey;
        return checkouts.create(merchant, branch, null,
                List.of(new CheckoutService.AllocationRequest(merchant, "sale", amount, "POS payment")),
                "POS payment", 30, actor, orderRef).reference();
    }

    @Transactional
    public RailStartResult startAndSubmitRail(String reference, String method, String msisdn, String network, String email) {
        var started = checkouts.startRailPayment(reference, method, msisdn, network, email, payUrl(reference));
        if (started != null && started.attemptId() != null) {
            UUID railTx = checkouts.railTxOf(started.attemptId());
            if (railTx != null) {
                rails.submit(railTx);
            }
        }
        var status = checkouts.status(reference);
        return new RailStartResult(status.reference(), status.status(), status.attemptId(),
                status.railStatus(), status.redirectUrl(), status.message());
    }

    @Transactional
    public GhQrResult createGhQrIntent(UUID merchantId, String terminalId, long amountPesewas, String merchantName) {
        var intent = ghQrDynamicService.createDynamicIntent(
                merchantId,
                terminalId != null ? terminalId : "POS",
                amountPesewas,
                "GHS",
                merchantName,
                "Accra",
                Duration.ofMinutes(15)
        );
        return new GhQrResult(intent.qrReference(), intent.payloadText());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRailSucceeded(RailEvents.Succeeded event) {
        if (!"checkout".equals(event.purpose())) {
            return;
        }
        var refOpt = db.sql("""
                SELECT c.reference
                FROM payment_attempt a
                JOIN checkout c ON c.id = a.checkout_id
                WHERE a.id = :attemptId
                """)
                .param("attemptId", event.purposeId())
                .query(String.class)
                .optional();
        if (refOpt.isEmpty()) {
            return;
        }
        CheckoutService.Checkout c = checkouts.load(refOpt.get(), false);
        if ("paid".equals(c.status()) && c.journalTx() != null) {
            events.publishEvent(new CheckoutPaidEvent(
                    c.reference(), c.collectorId(), c.branchId(), c.paymentMethod(), c.subtotal(), c.journalTx()));
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PaidEvidence requirePaid(String reference, UUID merchant, UUID branch, long amount) {
        var c = checkouts.load(reference, true);
        if (!merchant.equals(c.collectorId()) || !Objects.equals(branch, c.branchId()) || c.subtotal() != amount
                || c.allocations().size() != 1 || !merchant.equals(c.allocations().get(0).merchantId())
                || c.allocations().get(0).amount() != amount)
            throw ApiException.notFound("Matching POS checkout not found");
        var allocation = c.allocations().get(0);
        if (!"paid".equals(c.status()) || c.journalTx() == null || c.totalPaid() == null
                || c.totalPaid() != Math.addExact(amount, c.customerFee()) || allocation.refunded() != 0 || allocation.disputed() != 0)
            throw ApiException.conflict("payment_not_confirmed", "Checkout is not an undisputed, fully paid ledger transaction");
        return new PaidEvidence(c.paymentMethod(), c.journalTx());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(UUID merchant, UUID branch, String ref, String actor) {
        checkouts.cancel(merchant, branch, ref, actor);
    }

    public String payUrl(String reference) { return publicBaseUrl + "/pay/" + reference; }
}
