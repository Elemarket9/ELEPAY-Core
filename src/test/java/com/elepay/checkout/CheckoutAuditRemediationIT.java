package com.elepay.checkout;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.ledger.Ledger;
import com.elepay.merchant.MerchantAccess;
import com.elepay.rails.RailEvents;
import com.elepay.rails.SimulatedRail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutAuditRemediationIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired CheckoutService checkoutService;
    @Autowired MerchantAccess merchantAccess;
    @Autowired SimulatedRail simulated;
    @Autowired ApplicationEventPublisher events;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired(required = false) com.elepay.identity.KillSwitchService killSwitchService;

    TestApi api;
    String ops, mpOwner, sellerOwner, customerToken;
    UUID marketplace, seller, customer;

    @BeforeEach
    void setUp() {
        if (killSwitchService != null) {
            killSwitchService.setModuleEnabled("consumer-wallet", true, "test setup", "test");
            killSwitchService.setModuleEnabled("mandates", true, "test setup", "test");
        }
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        ops = api.token("ops-" + s, "ops");
        mpOwner = api.token("mp-" + s);
        sellerOwner = api.token("seller-" + s);
        marketplace = createMerchant(mpOwner, "MP " + s, "marketplace", 0);
        seller = createMerchant(sellerOwner, "Seller " + s, "merchant", 0);

        String code = api.call("GET", "/v1/merchants/" + seller, sellerOwner, null, null)
                .body().get("merchantCode").asString();
        assertThat(api.call("POST", "/v1/merchants/" + marketplace + "/sellers", mpOwner, null,
                Map.of("merchantCode", code)).status()).isEqualTo(200);
        assertThat(api.call("POST", "/v1/merchants/" + seller + "/marketplaces/" + marketplace + "/accept",
                sellerOwner, null, null).status()).isEqualTo(200);

        String custSub = "cust-" + s;
        customerToken = api.token(custSub);
        customer = api.enroll(ops, custSub, TestApi.uniquePhone(), "Ama Buyer");
        fundCustomerWallet(20_000L);
    }

    UUID createMerchant(String owner, String name, String kind, int feeBps) {
        var created = api.call("POST", "/v1/merchants", owner, null, Map.of(
                "legalName", name + " Ltd", "tradingName", name, "kind", kind, "contactPhone", "0241234567"));
        assertThat(created.status()).isEqualTo(200);
        UUID id = UUID.fromString(created.body().get("id").asString());
        assertThat(api.call("POST", "/v1/ops/merchants/" + id + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", feeBps, "feeFixed", 0, "settlementDelayHours", 0))
                .body().get("status").asString()).isEqualTo("active");
        return id;
    }

    void fundCustomerWallet(long amount) {
        var topUp = api.call("POST", "/v1/wallet/topups", customerToken, "topup-" + UUID.randomUUID(),
                Map.of("amount", amount, "method", "momo", "phone", "0245550005", "network", "mtn"));
        assertThat(topUp.status()).as("topup: %s", topUp.body()).isEqualTo(200);
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);
        assertThat(ledger.balance(Ledger.code("customer", customer, "available", "GHS")))
                .as("wallet balance after topup+recover").isEqualTo(amount);
    }

    String createPaidReserveCheckout(long amount, int reserveBps) {
        var created = api.call("POST", "/v1/merchants/" + marketplace + "/checkouts", mpOwner,
                UUID.randomUUID().toString(),
                Map.of("description", "Reserve Order", "allocations", List.of(
                        Map.of("merchantId", seller, "kind", "sale", "amount", amount,
                                "description", "Item", "reserveBps", reserveBps))));
        assertThat(created.status()).isEqualTo(200);
        String ref = created.body().get("reference").asString();
        var paid = api.call("POST", "/v1/checkouts/" + ref + "/pay/wallet", customerToken,
                UUID.randomUUID().toString(), null);
        assertThat(paid.status()).as("pay/wallet: %s", paid.body()).isEqualTo(200);
        return ref;
    }

    @Test
    @DisplayName("Finding 1: Seller cannot unilaterally release their own retained reserve; collector can")
    void testSellerCannotSelfReleaseReserve() {
        String ref = createPaidReserveCheckout(5_000L, 2000); // 1,000 reserved

        // Seller attempts to release their own reserve -> rejected with 404
        var sellerAttempt = api.call("POST",
                "/v1/merchants/" + seller + "/checkouts/" + ref + "/allocations/0/release-reserve",
                sellerOwner, null, Map.of("amount", 1_000L, "reason", "self-release"));
        assertThat(sellerAttempt.status()).isEqualTo(404);
        assertThat(ledger.balance(Ledger.code("merchant", seller, "reserved", "GHS"))).isEqualTo(1_000L);

        // Marketplace collector releases reserve -> succeeds
        var collectorRelease = api.call("POST",
                "/v1/merchants/" + marketplace + "/checkouts/" + ref + "/allocations/0/release-reserve",
                mpOwner, null, Map.of("amount", 1_000L, "reason", "approved release"));
        assertThat(collectorRelease.status()).isEqualTo(200);
        assertThat(ledger.balance(Ledger.code("merchant", seller, "reserved", "GHS"))).isEqualTo(0L);
    }

    @Test
    @DisplayName("Finding 2: Full refund on unsettled allocation with reserveBps > 0 and available == 0 succeeds")
    void testFullRefundPullsFromReservedWhenAvailableIsZero() {
        String ref = createPaidReserveCheckout(5_000L, 2000); // 4,000 pending, 1,000 reserved, 0 available
        assertThat(ledger.balance(Ledger.code("merchant", seller, "available", "GHS"))).isEqualTo(0L);

        var refund = api.call("POST",
                "/v1/merchants/" + marketplace + "/checkouts/" + ref + "/allocations/0/refunds",
                mpOwner, UUID.randomUUID().toString(),
                Map.of("amount", 5_000L, "reason", "full refund with reserve"));
        assertThat(refund.status()).isEqualTo(200);
        assertThat(ledger.balance(Ledger.code("merchant", seller, "pending", "GHS"))).isEqualTo(0L);
        assertThat(ledger.balance(Ledger.code("merchant", seller, "reserved", "GHS"))).isEqualTo(0L);
        assertThat(ledger.balance(Ledger.code("merchant", seller, "available", "GHS"))).isEqualTo(0L);

        Long reserveReleased = db.sql("""
                SELECT a.reserve_released FROM checkout_allocation a
                JOIN checkout c ON c.id = a.checkout_id WHERE c.reference = :r AND a.line = 0""")
                .param("r", ref).query(Long.class).single();
        assertThat(reserveReleased).isEqualTo(1_000L);
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    @DisplayName("Finding 3: Releasing reserve before settleDue increments pending_amount so settleDue moves it to available")
    void testReleaseReserveBeforeSettleDueDoesNotStrandFunds() {
        String ref = createPaidReserveCheckout(5_000L, 2000); // 4,000 pending, 1,000 reserved

        // Release reserve before delivery confirmation / settlement
        var rel = api.call("POST",
                "/v1/merchants/" + marketplace + "/checkouts/" + ref + "/allocations/0/release-reserve",
                mpOwner, null, Map.of("amount", 1_000L, "reason", "early release"));
        assertThat(rel.status()).isEqualTo(200);

        // Confirm delivery and run settleDue
        var delivered = api.call("POST", "/v1/merchants/" + marketplace + "/checkouts/" + ref + "/deliveries",
                mpOwner, null, Map.of("lines", List.of(0)));
        assertThat(delivered.status()).isEqualTo(200);

        checkoutService.settleDue(true, seller);

        assertThat(ledger.balance(Ledger.code("merchant", seller, "pending", "GHS"))).isEqualTo(0L);
        assertThat(ledger.balance(Ledger.code("merchant", seller, "reserved", "GHS"))).isEqualTo(0L);
        assertThat(ledger.balance(Ledger.code("merchant", seller, "available", "GHS"))).isEqualTo(5_000L);
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    @DisplayName("Finding 8: resetProcessing followed by RailEvents.Succeeded completes checkout without NPE")
    void testOnRailSucceededAfterResetProcessingDoesNotThrowNpe() {
        var created = api.call("POST", "/v1/merchants/" + marketplace + "/checkouts", mpOwner,
                UUID.randomUUID().toString(),
                Map.of("description", "Reset Race", "allocations", List.of(
                        Map.of("merchantId", seller, "kind", "sale", "amount", 5_000L, "description", "Item"))));
        assertThat(created.status()).isEqualTo(200);
        String ref = created.body().get("reference").asString();

        var pay = api.call("POST", "/v1/public/checkouts/" + ref + "/pay", null,
                UUID.randomUUID().toString(),
                Map.of("method", "momo", "phone", "0241112233", "network", "mtn"));
        assertThat(pay.status()).isEqualTo(200);
        UUID attemptId = UUID.fromString(pay.body().get("attemptId").asString());
        UUID railTxId = checkoutService.railTxOf(attemptId);

        // Reset processing while rail_transaction is in flight must be rejected with 409 rail_in_flight (prevents double-charge)
        var reset = api.call("POST", "/v1/merchants/" + marketplace + "/checkouts/" + ref + "/reset-payment",
                mpOwner, null, null);
        assertThat(reset.status()).isEqualTo(409);

        // Even if payment_method is NULL on checkout, late RailEvents.Succeeded must fall back to payment_attempt.method without NPE
        db.sql("UPDATE checkout SET payment_method = NULL WHERE reference = :ref").param("ref", ref).update();
        new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(status ->
                events.publishEvent(new RailEvents.Succeeded(
                        railTxId, "sandbox", "charge", "checkout", attemptId, 5_000L, 0L)));

        var status = api.call("GET", "/v1/public/checkouts/" + ref + "/status", null, null, null);
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.body().get("status").asString()).isEqualTo("paid");
        assertThat(status.body().get("method").asString()).isEqualTo("momo");
    }

    @Test
    @DisplayName("Finding 9: CheckoutService.settleDue parameterizes DEFERRED_REVENUE and REVENUE by currency")
    void testSettleDueMultiCurrencyUsd() {
        UUID checkoutId = UUID.randomUUID();
        String ref = "EP-USD-" + UUID.randomUUID().toString().substring(0, 8);
        String clearingUsd = ledger.openSystem("system:clearing:card:USD", "clearing", "USD", true);
        String sellerPendingUsd = ledger.open("merchant", seller, "pending", "USD");
        String deferredUsd = ledger.openSystem("system:deferred_revenue:USD", "deferred_revenue", "USD", true);
        long revUsdBefore = ledger.balance("system:revenue:USD");

        var posted = ledger.post(Ledger.Posting.of("test-usd-checkout", checkoutId.toString(), "checkout_payment", List.of(
                Ledger.Leg.debit(clearingUsd, 10_000L),
                Ledger.Leg.credit(sellerPendingUsd, 9_500L),
                Ledger.Leg.credit(deferredUsd, 500L)
        )));

        db.sql("""
                INSERT INTO checkout (id, reference, collector_id, status, subtotal, currency, payment_method,
                                      customer_fee, total_paid, journal_tx, paid_at, expires_at, created_by)
                VALUES (:id, :ref, :col, 'paid', 10000, 'USD', 'card', 0, 10000, :tx, now(), now() + interval '1 hour', 'test')""")
                .param("id", checkoutId).param("ref", ref).param("col", marketplace).param("tx", posted.transactionId()).update();
        db.sql("""
                INSERT INTO checkout_allocation (checkout_id, line, merchant_id, kind, description, amount,
                    merchant_fee, pending_amount, fee_deferred, hold_until_delivered)
                VALUES (:id, 0, :m, 'sale', 'USD item', 10000, 500, 9500, 500, false)""")
                .param("id", checkoutId).param("m", seller).update();

        checkoutService.settleDue(true, seller, checkoutId);

        assertThat(ledger.balance(Ledger.code("merchant", seller, "available", "USD"))).isEqualTo(9_500L);
        assertThat(ledger.balance("system:revenue:USD")).isEqualTo(revUsdBefore + 500L);
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    @DisplayName("Finding 12: PublicCheckouts.pay ignores remoteAddr in idempotency scope and MandateService requires Idempotency-Key")
    void testPublicCheckoutIpIndependentIdempotencyAndMandateKeyRequired() {
        var created = api.call("POST", "/v1/merchants/" + marketplace + "/checkouts", mpOwner,
                UUID.randomUUID().toString(),
                Map.of("description", "Idem IP", "allocations", List.of(
                        Map.of("merchantId", seller, "kind", "sale", "amount", 4_000L, "description", "Item"))));
        String ref = created.body().get("reference").asString();
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("method", "momo", "phone", "0241112233", "network", "mtn");

        // Verify idempotency row scope does NOT contain IP address
        var first = api.call("POST", "/v1/public/checkouts/" + ref + "/pay", null, key, body);
        assertThat(first.status()).isEqualTo(200);
        Long scopeCount = db.sql("SELECT count(*) FROM idempotent_request WHERE scope = :s AND key = :k")
                .param("s", "checkout-pay:" + ref).param("k", key).query(Long.class).single();
        assertThat(scopeCount).isEqualTo(1L);

        // MandateService.createMandate without Idempotency-Key -> 400
        var noKeyMandate = api.call("POST", "/v1/customers/mandates", customerToken, null,
                Map.of("merchantId", seller.toString(), "maxAmountPesewas", 5000,
                        "frequency", "monthly", "consentReference", "SUB-NO-KEY"));
        assertThat(noKeyMandate.status()).isEqualTo(400);
    }
}
