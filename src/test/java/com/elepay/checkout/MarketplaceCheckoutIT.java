package com.elepay.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.ledger.Ledger;
import com.elepay.rails.SimulatedRail;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Acceptance test 3 and rail payment resilience, through the public HTTP entry points. */
class MarketplaceCheckoutIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired SimulatedRail simulated;

    TestApi api;
    String ops, mpOwner, aOwner, bOwner, dOwner, customerToken;
    UUID marketplace, merchantA, merchantB, delivery, customer;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        ops = api.token("ops-" + s, "ops");
        mpOwner = api.token("mp-" + s);
        aOwner = api.token("a-" + s);
        bOwner = api.token("b-" + s);
        dOwner = api.token("d-" + s);
        marketplace = merchant(mpOwner, "ELEMARKET " + s, "marketplace", 0);
        merchantA = merchant(aOwner, "Seller A " + s, "merchant", 150);
        merchantB = merchant(bOwner, "Seller B " + s, "merchant", 100);
        delivery = merchant(dOwner, "Rider D " + s, "delivery", 0);
        for (var seller : List.of(Map.entry(merchantA, aOwner), Map.entry(merchantB, bOwner), Map.entry(delivery, dOwner))) {
            String code = api.call("GET", "/v1/merchants/" + seller.getKey(), seller.getValue(), null, null)
                    .body().get("merchantCode").asString();
            assertThat(api.call("POST", "/v1/merchants/" + marketplace + "/sellers", mpOwner, null,
                    Map.of("merchantCode", code)).status()).isEqualTo(200);
            assertThat(api.call("POST", "/v1/merchants/" + seller.getKey() + "/marketplaces/" + marketplace + "/accept",
                    seller.getValue(), null, null).status()).isEqualTo(200);
        }
        String subject = "customer-" + s;
        customerToken = api.token(subject);
        customer = api.enroll(ops, subject, TestApi.uniquePhone(), "Efua Customer");
    }

    UUID merchant(String owner, String name, String kind, int feeBps) {
        var created = api.call("POST", "/v1/merchants", owner, null, Map.of("legalName", name + " Ltd",
                "tradingName", name, "kind", kind, "contactPhone", "0241234567"));
        assertThat(created.status()).isEqualTo(200);
        UUID id = UUID.fromString(created.body().get("id").asString());
        assertThat(api.call("POST", "/v1/ops/merchants/" + id + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", feeBps, "feeFixed", 0, "settlementDelayHours", 24))
                .body().get("status").asString()).isEqualTo("active");
        return id;
    }

    long bal(String ownerType, UUID id, String bucket) {
        return ledger.balance(Ledger.code(ownerType, id, bucket, "GHS"));
    }

    void callback(String railReference, String status, Long amount) {
        var signed = simulated.callback(railReference, status, amount);
        int code = api.http.post().uri("/v1/rails/sandbox/callbacks")
                .header("x-sandbox-signature", signed.getKey()).header("Content-Type", "application/json")
                .body(signed.getValue()).exchange((req, res) -> res.getStatusCode().value());
        assertThat(code).isEqualTo(200);
    }

    String createThreeWayCheckout() {
        var created = api.call("POST", "/v1/merchants/" + marketplace + "/checkouts", mpOwner, UUID.randomUUID().toString(),
                Map.of("description", "Order 1001", "allocations", List.of(
                        Map.of("merchantId", merchantA, "kind", "sale", "amount", 8000, "description", "Shoes"),
                        Map.of("merchantId", merchantB, "kind", "sale", "amount", 5000, "description", "Bag"),
                        Map.of("merchantId", delivery, "kind", "delivery", "amount", 2000, "description", "Delivery"))));
        assertThat(created.status()).isEqualTo(200);
        return created.body().get("reference").asString();
    }

    @Test
    void fundPayThreeWayRefundBSettleAndReconcileEveryPesewa() {
        long clearingBefore = ledger.balance(Ledger.clearing("sandbox"));
        long revenueBefore = ledger.balance(Ledger.REVENUE);

        // Fund GH₵150 through the wallet top-up entry point (simulated MoMo rail).
        var topUp = api.call("POST", "/v1/wallet/topups", customerToken, "fund-" + customer,
                Map.of("amount", 15000, "method", "momo", "phone", "0245550005", "network", "mtn"));
        assertThat(topUp.body().get("status").asString()).isEqualTo("pending");
        assertThat(bal("customer", customer, "available")).isZero(); // not credited before confirmation
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);
        assertThat(bal("customer", customer, "available")).isEqualTo(15000);

        // One checkout: GH₵80 to A, GH₵50 to B, GH₵20 delivery.
        String reference = createThreeWayCheckout();
        JsonNode view = api.call("GET", "/v1/public/checkouts/" + reference, null, null, null).body();
        assertThat(view.get("subtotal").asLong()).isEqualTo(15000);
        assertThat(view.get("lines")).hasSize(3);
        assertThat(view.get("methods").get(0).get("total").asLong()).isEqualTo(15000);

        String payKey = UUID.randomUUID().toString();
        var paid = api.call("POST", "/v1/checkouts/" + reference + "/pay/wallet", customerToken, payKey, null);
        assertThat(paid.body().get("status").asString()).isEqualTo("paid");
        // Duplicate request returns the original; a new attempt cannot pay twice.
        assertThat(api.call("POST", "/v1/checkouts/" + reference + "/pay/wallet", customerToken, payKey, null).body())
                .isEqualTo(paid.body());
        var again = api.call("POST", "/v1/checkouts/" + reference + "/pay/wallet", customerToken, UUID.randomUUID().toString(), null);
        assertThat(again.status()).isEqualTo(409);

        assertThat(bal("customer", customer, "available")).isZero();
        assertThat(bal("merchant", merchantA, "pending")).isEqualTo(8000 - 120); // 1.5% agreed fee
        assertThat(bal("merchant", merchantB, "pending")).isEqualTo(5000 - 50);  // 1% agreed fee
        assertThat(bal("merchant", delivery, "pending")).isEqualTo(2000);

        // Merchant isolation.
        assertThat(api.call("GET", "/v1/merchants/" + merchantA + "/balances", bOwner, null, null).status()).isEqualTo(404);
        assertThat(api.call("POST", "/v1/merchants/" + merchantB + "/checkouts/" + reference + "/allocations/0/refunds",
                bOwner, UUID.randomUUID().toString(), Map.of("amount", 100, "reason", "not mine")).status()).isEqualTo(404);
        JsonNode bView = api.call("GET", "/v1/merchants/" + merchantB + "/checkouts/" + reference, bOwner, null, null).body();
        assertThat(bView.get("lines")).hasSize(1);
        assertThat(bView.get("lines").get(0).get("line").asInt()).isEqualTo(1);
        assertThat(bView.get("totalPaid").isNull()).isTrue();
        JsonNode bTx = api.call("GET", "/v1/merchants/" + merchantB + "/transactions", bOwner, null, null).body();
        assertThat(bTx).hasSize(1);
        assertThat(bTx.get(0).get("amount").asLong()).isEqualTo(5000);
        assertThat(api.call("GET", "/v1/merchants/" + marketplace + "/checkouts/" + reference, mpOwner, null, null)
                .body().get("lines")).hasSize(3);

        // A cashier cannot refund; finance can.
        String cashierSubject = "cashier-" + UUID.randomUUID();
        var branchResponse = api.call("POST", "/v1/merchants/" + merchantB + "/branches", bOwner, null, Map.of("name", "Osu"));
        assertThat(branchResponse.status()).as(branchResponse.body().toString()).isEqualTo(200);
        UUID branch = UUID.fromString(branchResponse.body().get("id").asString());
        api.call("PUT", "/v1/merchants/" + merchantB + "/members/" + cashierSubject, bOwner, null,
                Map.of("role", "cashier", "branchId", branch));
        assertThat(api.call("POST", "/v1/merchants/" + merchantB + "/checkouts/" + reference + "/allocations/1/refunds",
                api.token(cashierSubject), UUID.randomUUID().toString(), Map.of("amount", 5000, "reason", "x")).status())
                .isEqualTo(403);

        // Refund merchant B's full GH₵50 allocation.
        String refundKey = UUID.randomUUID().toString();
        var refund = api.call("POST", "/v1/merchants/" + merchantB + "/checkouts/" + reference + "/allocations/1/refunds",
                bOwner, refundKey, Map.of("amount", 5000, "reason", "Seller cancelled"));
        assertThat(refund.status()).isEqualTo(200);
        assertThat(refund.body().get("feeReturned").asLong()).isEqualTo(50);
        assertThat(api.call("POST", "/v1/merchants/" + merchantB + "/checkouts/" + reference + "/allocations/1/refunds",
                bOwner, refundKey, Map.of("amount", 5000, "reason", "Seller cancelled")).body()).isEqualTo(refund.body());
        assertThat(api.call("POST", "/v1/merchants/" + merchantB + "/checkouts/" + reference + "/allocations/1/refunds",
                bOwner, UUID.randomUUID().toString(), Map.of("amount", 1, "reason", "again")).status()).isEqualTo(409);
        assertThat(bal("customer", customer, "available")).isEqualTo(5000);
        assertThat(bal("merchant", merchantB, "pending")).isZero();
        assertThat(bal("merchant", merchantA, "pending")).isEqualTo(7880);

        // Marketplace lines wait for delivery: a forced run releases nothing until the marketplace confirms.
        api.call("POST", "/v1/ops/settlements/run", ops, null, Map.of("ignoreDelay", true));
        assertThat(bal("merchant", merchantA, "available")).isZero();
        assertThat(api.call("POST", "/v1/merchants/" + marketplace + "/checkouts/" + reference + "/deliveries",
                mpOwner, null, Map.of()).status()).isEqualTo(200);

        // Settle the remaining proceeds.
        var run = api.call("POST", "/v1/ops/settlements/run", ops, null, Map.of("ignoreDelay", true));
        assertThat(run.status()).as(run.body().toString()).isEqualTo(200);
        JsonNode batches = run.body();
        assertThat(batches.findValues("merchantId").stream().map(JsonNode::asString).toList())
                .contains(merchantA.toString(), delivery.toString()).doesNotContain(merchantB.toString());
        assertThat(bal("merchant", merchantA, "available")).isEqualTo(7880);
        assertThat(bal("merchant", delivery, "available")).isEqualTo(2000);
        assertThat(bal("merchant", merchantA, "pending")).isZero();
        assertThat(bal("merchant", merchantB, "available")).isZero();
        JsonNode lines = api.call("GET", "/v1/merchants/" + marketplace + "/checkouts/" + reference, mpOwner, null, null)
                .body().get("lines");
        assertThat(lines.get(0).get("status").asString()).isEqualTo("settled");
        assertThat(lines.get(1).get("status").asString()).isEqualTo("refunded");
        assertThat(lines.get(2).get("status").asString()).isEqualTo("settled");

        // Reconcile every pesewa: money in from the rail equals what everyone now holds.
        long fundedIn = clearingBefore - ledger.balance(Ledger.clearing("sandbox"));
        long revenue = ledger.balance(Ledger.REVENUE) - revenueBefore;
        assertThat(fundedIn).isEqualTo(15000);
        assertThat(revenue).isEqualTo(120);
        assertThat(bal("customer", customer, "available") + bal("merchant", merchantA, "available")
                + bal("merchant", merchantB, "available") + bal("merchant", delivery, "available") + revenue)
                .isEqualTo(fundedIn);
        assertThat(ledger.integrity().ok()).isTrue();

        // Each merchant sees only its own settlements; refunds and settlement changes are audited.
        assertThat(api.call("GET", "/v1/merchants/" + merchantA + "/settlements", aOwner, null, null).body()).hasSize(1);
        assertThat(api.call("GET", "/v1/merchants/" + merchantA + "/settlements", bOwner, null, null).status()).isEqualTo(404);
        assertThat(db.sql("SELECT count(*) FROM audit_log WHERE action = 'refund.created' AND subject = :s")
                .param("s", "checkout:" + reference).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void railCheckoutResumesAfterDroppedConnectionAndHandlesDuplicateAndOutOfOrderCallbacks() {
        String reference = createThreeWayCheckout();
        Map<String, Object> pay = Map.of("method", "momo", "phone", "0245550003", "network", "mtn");
        var first = api.call("POST", "/v1/public/checkouts/" + reference + "/pay", null, UUID.randomUUID().toString(), pay);
        assertThat(first.body().get("status").asString()).isEqualTo("processing");
        // Dropped connection: the customer reopens the link and taps pay again with a new request.
        var resumed = api.call("POST", "/v1/public/checkouts/" + reference + "/pay", null, UUID.randomUUID().toString(), pay);
        assertThat(resumed.body().get("attemptId").asString()).isEqualTo(first.body().get("attemptId").asString());
        long charges = db.sql("""
                SELECT count(*) FROM rail_transaction t JOIN payment_attempt p ON p.rail_tx = t.id
                JOIN checkout c ON c.id = p.checkout_id WHERE c.reference = :r""").param("r", reference)
                .query(Long.class).single();
        assertThat(charges).isEqualTo(1);
        // A wallet payment cannot race the in-flight rail payment.
        assertThat(api.call("POST", "/v1/checkouts/" + reference + "/pay/wallet", customerToken,
                UUID.randomUUID().toString(), null).status()).isEqualTo(409);

        String railRef = db.sql("""
                SELECT t.reference FROM rail_transaction t JOIN payment_attempt p ON p.rail_tx = t.id
                JOIN checkout c ON c.id = p.checkout_id WHERE c.reference = :r""").param("r", reference)
                .query(String.class).single();
        long clearingBefore = ledger.balance(Ledger.clearing("sandbox"));
        callback(railRef, "pending", null);                 // early pending notification
        simulated.settle(railRef, true);
        callback(railRef, "succeeded", 15000L);
        callback(railRef, "succeeded", 15000L);             // exact duplicate
        callback(railRef, "pending", 15000L);               // stale, out of order
        assertThat(api.call("GET", "/v1/public/checkouts/" + reference + "/status", null, null, null)
                .body().get("status").asString()).isEqualTo("paid");
        assertThat(clearingBefore - ledger.balance(Ledger.clearing("sandbox"))).isEqualTo(15000);
        assertThat(bal("merchant", merchantA, "pending")).isEqualTo(7880);

        callback(railRef, "failed", 15000L);                // contradictory late failure
        assertThat(db.sql("SELECT count(*) FROM ops_item WHERE kind = 'rail_status_conflict' AND subject = :s")
                .param("s", "rail_tx:" + railRef).query(Long.class).single()).isEqualTo(1);
        assertThat(clearingBefore - ledger.balance(Ledger.clearing("sandbox"))).isEqualTo(15000);
        assertThat(ledger.integrity().ok()).isTrue();

        // Forged callbacks are rejected.
        int forged = api.http.post().uri("/v1/rails/sandbox/callbacks").header("x-sandbox-signature", "00")
                .header("Content-Type", "application/json").body("{\"data\":{\"reference\":\"" + railRef + "\"}}")
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(forged).isEqualTo(401);
    }

    @Test
    void timeoutNeverCausesASecondChargeAndAmountMismatchIsNotCredited() {
        String reference = createThreeWayCheckout();
        // Phone ending 2: the rail accepts but the response times out (unknown outcome).
        var r = api.call("POST", "/v1/public/checkouts/" + reference + "/pay", null, UUID.randomUUID().toString(),
                Map.of("method", "momo", "phone", "0245550002", "network", "mtn"));
        assertThat(r.body().get("status").asString()).isEqualTo("processing");
        assertThat(r.body().get("railStatus").asString()).isEqualTo("unknown");
        api.call("POST", "/v1/public/checkouts/" + reference + "/pay", null, UUID.randomUUID().toString(),
                Map.of("method", "momo", "phone", "0245550002", "network", "mtn"));
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);
        assertThat(api.call("GET", "/v1/public/checkouts/" + reference + "/status", null, null, null)
                .body().get("status").asString()).isEqualTo("paid");
        assertThat(db.sql("""
                SELECT count(*) FROM payment_attempt p JOIN checkout c ON c.id = p.checkout_id WHERE c.reference = :r""")
                .param("r", reference).query(Long.class).single()).isEqualTo(1);

        // A callback reporting a different amount marks the charge as mismatched and credits nobody.
        String other = createThreeWayCheckout();
        api.call("POST", "/v1/public/checkouts/" + other + "/pay", null, UUID.randomUUID().toString(),
                Map.of("method", "momo", "phone", "0245550003", "network", "mtn"));
        String railRef = db.sql("""
                SELECT t.reference FROM rail_transaction t JOIN payment_attempt p ON p.rail_tx = t.id
                JOIN checkout c ON c.id = p.checkout_id WHERE c.reference = :r""").param("r", other)
                .query(String.class).single();
        long pendingA = bal("merchant", merchantA, "pending");
        callback(railRef, "succeeded", 14999L);
        assertThat(api.call("GET", "/v1/public/checkouts/" + other + "/status", null, null, null)
                .body().get("status").asString()).isEqualTo("processing");
        assertThat(bal("merchant", merchantA, "pending")).isEqualTo(pendingA);
        assertThat(db.sql("SELECT count(*) FROM ops_item WHERE kind = 'rail_amount_mismatch' AND subject = :s")
                .param("s", "rail_tx:" + railRef).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void paymentLinksMerchantCodesAndFeeQuotes() {
        var link = api.call("POST", "/v1/merchants/" + merchantA + "/payment-links", aOwner, null,
                Map.of("title", "Kenkey", "amount", 500)).body();
        String slug = link.get("slug").asString();
        assertThat(api.call("GET", "/v1/public/links/" + slug, null, null, null).body().get("merchant").asString())
                .startsWith("Seller A");
        String ref = api.call("POST", "/v1/public/links/" + slug + "/checkouts", null, null, Map.of()).body()
                .get("reference").asString();
        assertThat(api.call("GET", "/v1/public/checkouts/" + ref, null, null, null).body().get("subtotal").asLong())
                .isEqualTo(500);
        String code = api.call("GET", "/v1/merchants/" + merchantA + "/qr", aOwner, null, null).body()
                .get("merchantCode").asString();
        var byCode = api.call("POST", "/v1/public/merchant-codes/" + code + "/checkouts", null, null, Map.of("amount", 1000));
        assertThat(byCode.status()).isEqualTo(200);
        assertThat(api.call("GET", "/v1/public/fees/quote?amount=500&method=wallet", null, null, null)
                .body().get("total").asLong()).isEqualTo(500);
        // Non-marketplaces cannot allocate to other merchants.
        assertThat(api.call("POST", "/v1/merchants/" + merchantA + "/checkouts", aOwner, UUID.randomUUID().toString(),
                Map.of("allocations", List.of(Map.of("merchantId", merchantB, "kind", "sale", "amount", 100))))
                .status()).isEqualTo(403);
    }
}
