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

/**
 * Jumia-style marketplace fees through the public API: a commission grid per category with minimums and a
 * processing fee, every seller line held until delivery, commission earned only on delivered items, and
 * ELEPAY's fee deferred until settlement (BoG Notice 43).
 */
class MarketplaceCommissionIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired SimulatedRail simulated;

    TestApi api;
    String ops, mpOwner, aOwner, bOwner, dOwner, customerToken;
    UUID marketplace, sellerA, sellerB, rider, customer;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        ops = api.token("ops-" + s, "ops");
        mpOwner = api.token("mp-" + s);
        aOwner = api.token("a-" + s);
        bOwner = api.token("b-" + s);
        dOwner = api.token("d-" + s);
        marketplace = merchant(mpOwner, "JUMIA GH " + s, "marketplace", 0);
        sellerA = merchant(aOwner, "Fashion GH " + s, "merchant", 150);
        sellerB = merchant(bOwner, "Phone Hub " + s, "merchant", 100);
        rider = merchant(dOwner, "Rider " + s, "delivery", 0);
        for (var seller : List.of(Map.entry(sellerA, aOwner), Map.entry(sellerB, bOwner), Map.entry(rider, dOwner))) {
            String code = api.call("GET", "/v1/merchants/" + seller.getKey(), seller.getValue(), null, null)
                    .body().get("merchantCode").asString();
            api.call("POST", "/v1/merchants/" + marketplace + "/sellers", mpOwner, null, Map.of("merchantCode", code));
            api.call("POST", "/v1/merchants/" + seller.getKey() + "/marketplaces/" + marketplace + "/accept",
                    seller.getValue(), null, null);
        }
        // Commission grid: fashion 20% + GH₵2 processing, phones 5% (min GH₵10), everything else 10% (min GH₵5).
        var grid = api.call("PUT", "/v1/merchants/" + marketplace + "/fee-schedule", mpOwner, null, Map.of(
                "deliveryConfirmDays", 7, "rules", List.of(
                        Map.of("category", "*", "commissionBps", 1000, "commissionMin", 500, "processingFee", 0),
                        Map.of("category", "fashion", "commissionBps", 2000, "commissionMin", 0, "processingFee", 200),
                        Map.of("category", "phones", "commissionBps", 500, "commissionMin", 1000, "processingFee", 0))));
        assertThat(grid.status()).as(grid.body().toString()).isEqualTo(200);
        assertThat(grid.body().get("rules")).hasSize(3);

        String subject = "customer-" + s;
        customerToken = api.token(subject);
        customer = api.enroll(ops, subject, TestApi.uniquePhone(), "Ama Mensah");
    }

    UUID merchant(String owner, String name, String kind, int feeBps) {
        var created = api.call("POST", "/v1/merchants", owner, null, Map.of("legalName", name + " Ltd",
                "tradingName", name, "kind", kind, "contactPhone", "0241234567"));
        UUID id = UUID.fromString(created.body().get("id").asString());
        api.call("POST", "/v1/ops/merchants/" + id + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", feeBps, "feeFixed", 0, "settlementDelayHours", 24));
        return id;
    }

    long bal(UUID id, String bucket) { return ledger.balance(Ledger.code("merchant", id, bucket, "GHS")); }

    /** ELEPAY fee on this order still deferred, and already recognised as revenue. */
    long feeDeferred(String ref) { return feeSum(ref, "fee_deferred"); }

    long feeRecognized(String ref) { return feeSum(ref, "fee_recognized"); }

    long feeSum(String ref, String column) {
        return db.sql("SELECT coalesce(sum(a." + column + "), 0) FROM checkout_allocation a "
                + "JOIN checkout c ON c.id = a.checkout_id WHERE c.reference = :r")
                .param("r", ref).query(Long.class).single();
    }

    void fund(long amount) {
        api.call("POST", "/v1/wallet/topups", customerToken, "fund-" + UUID.randomUUID(),
                Map.of("amount", amount, "method", "momo", "phone", "0245550005", "network", "mtn"));
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);
    }

    String cart(List<Map<String, Object>> lines) {
        var created = api.call("POST", "/v1/merchants/" + marketplace + "/checkouts", mpOwner,
                UUID.randomUUID().toString(), Map.of("description", "Cart", "allocations", lines));
        assertThat(created.status()).as(created.body().toString()).isEqualTo(200);
        return created.body().get("reference").asString();
    }

    JsonNode lines(String reference) {
        return api.call("GET", "/v1/merchants/" + marketplace + "/checkouts/" + reference, mpOwner, null, null)
                .body().get("lines");
    }

    void settleForced() {
        api.call("POST", "/v1/ops/settlements/run", ops, null, Map.of("ignoreDelay", true));
    }

    @Test
    void commissionGridDeliveryHoldAndFeesEarnedOnlyOnDeliveredItems() {
        fund(65000);
        String ref = cart(List.of(
                Map.of("merchantId", sellerA, "kind", "sale", "amount", 10000, "description", "Dress", "category", "Fashion"),
                Map.of("merchantId", sellerB, "kind", "sale", "amount", 50000, "description", "Phone", "category", "phones"),
                Map.of("merchantId", sellerA, "kind", "sale", "amount", 3000, "description", "Book", "category", "books"),
                Map.of("merchantId", rider, "kind", "delivery", "amount", 2000, "description", "Delivery")));

        JsonNode l = lines(ref);
        // Dress: 20% commission + GH₵2 processing + 1.5% ELEPAY fee.
        assertThat(l.get(0).get("category").asString()).isEqualTo("fashion");
        assertThat(l.get(0).get("commission").asLong()).isEqualTo(2000);
        assertThat(l.get(0).get("processingFee").asLong()).isEqualTo(200);
        assertThat(l.get(0).get("fee").asLong()).isEqualTo(150);
        assertThat(l.get(0).get("net").asLong()).isEqualTo(7650);
        // Phone: 5% = GH₵25, above the GH₵10 minimum.
        assertThat(l.get(1).get("commission").asLong()).isEqualTo(2500);
        assertThat(l.get(1).get("net").asLong()).isEqualTo(47000);
        // Book: no rule, so the default 10% applies; GH₵3 is below the GH₵5 minimum.
        assertThat(l.get(2).get("commission").asLong()).isEqualTo(500);
        assertThat(l.get(2).get("net").asLong()).isEqualTo(2455);
        // Delivery: no commission.
        assertThat(l.get(3).get("commission").asLong()).isZero();
        assertThat(l.get(3).get("net").asLong()).isEqualTo(2000);

        // One payment for the whole cart.
        var paid = api.call("POST", "/v1/checkouts/" + ref + "/pay/wallet", customerToken, UUID.randomUUID().toString(), null);
        assertThat(paid.body().get("total").asLong()).isEqualTo(65000);
        assertThat(bal(sellerA, "pending")).isEqualTo(7650 + 2455);
        assertThat(bal(sellerB, "pending")).isEqualTo(47000);
        assertThat(bal(rider, "pending")).isEqualTo(2000);
        assertThat(bal(marketplace, "pending")).isEqualTo(2200 + 2500 + 500);
        // ELEPAY's fee is held, not earned (Notice 43).
        assertThat(feeDeferred(ref)).isEqualTo(150 + 500 + 45);
        assertThat(feeRecognized(ref)).isZero();
        assertThat(lines(ref).get(0).get("status").asString()).isEqualTo("awaiting_delivery");

        // Nothing releases before delivery, even when ops forces a run.
        settleForced();
        assertThat(bal(sellerA, "available")).isZero();
        assertThat(bal(marketplace, "available")).isZero();

        // Phone is out of stock: full refund before delivery. Commission and fee go back, so neither
        // the marketplace nor ELEPAY earns anything on it.
        var refund = api.call("POST", "/v1/merchants/" + marketplace + "/checkouts/" + ref + "/allocations/1/refunds",
                mpOwner, UUID.randomUUID().toString(), Map.of("amount", 50000, "reason", "Out of stock"));
        assertThat(refund.status()).as(refund.body().toString()).isEqualTo(200);
        assertThat(refund.body().get("feeReturned").asLong()).isEqualTo(500);
        assertThat(refund.body().get("platformFeeReturned").asLong()).isEqualTo(2500);
        assertThat(bal(sellerB, "pending")).isZero();
        assertThat(bal(marketplace, "pending")).isEqualTo(2700);
        assertThat(feeDeferred(ref)).isEqualTo(195);

        // The dress and the delivery arrive. Settlement releases them; the book is still on its way.
        var delivered = api.call("POST", "/v1/merchants/" + marketplace + "/checkouts/" + ref + "/deliveries",
                mpOwner, null, Map.of("lines", List.of(0, 3)));
        assertThat(delivered.status()).as(delivered.body().toString()).isEqualTo(200);
        assertThat(delivered.body().get("lines").get(0).get("deliveredAt").isNull()).isFalse();
        // Sellers and the marketplace cannot confirm someone else's order.
        assertThat(api.call("POST", "/v1/merchants/" + sellerA + "/checkouts/" + ref + "/deliveries",
                aOwner, null, Map.of()).status()).isEqualTo(404);
        settleForced();
        assertThat(bal(sellerA, "available")).isEqualTo(7650);
        assertThat(bal(rider, "available")).isEqualTo(2000);
        assertThat(bal(marketplace, "available")).isEqualTo(2200);
        assertThat(bal(marketplace, "pending")).isEqualTo(500);
        assertThat(feeRecognized(ref)).isEqualTo(150);
        assertThat(feeDeferred(ref)).isEqualTo(45);
        l = lines(ref);
        assertThat(l.get(0).get("status").asString()).isEqualTo("settled");
        assertThat(l.get(1).get("status").asString()).isEqualTo("refunded");
        assertThat(l.get(2).get("status").asString()).isEqualTo("awaiting_delivery");

        // The buyer disputes the book and wins: the hold takes back the seller's net, the marketplace's
        // commission and ELEPAY's fee, and the money returns to the buyer's wallet automatically.
        var dispute = api.call("POST", "/v1/ops/disputes", ops, null,
                Map.of("checkout", ref, "line", 2, "amount", 3000, "reason", "Never arrived"));
        assertThat(dispute.status()).as(dispute.body().toString()).isEqualTo(200);
        assertThat(bal(sellerA, "pending")).isZero();
        assertThat(bal(sellerA, "reserved")).isEqualTo(3000);
        var won = api.call("POST", "/v1/ops/disputes/" + dispute.body().get("id").asString() + "/resolve", ops, null,
                Map.of("outcome", "customer_won", "note", "Courier lost it"));
        assertThat(won.body().get("refund").isNull()).isFalse();
        assertThat(lines(ref).get(2).get("status").asString()).isEqualTo("refunded");

        // Every pesewa: the buyer has the two refunds, and the dress, the delivery, the dress commission and
        // ELEPAY's fee on the dress are the only money that left the buyer.
        long customerBalance = ledger.balance(Ledger.code("customer", customer, "available", "GHS"));
        assertThat(customerBalance).isEqualTo(50000 + 3000);
        assertThat(customerBalance + bal(sellerA, "available") + bal(sellerB, "available") + bal(rider, "available")
                + bal(marketplace, "available") + feeRecognized(ref)).isEqualTo(65000);
        assertThat(bal(sellerA, "pending") + bal(sellerB, "pending") + bal(rider, "pending")
                + bal(marketplace, "pending") + bal(sellerA, "reserved")).isZero();
        assertThat(feeDeferred(ref)).isZero();
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void sellerWinsDisputeBeforeDeliveryAndUnconfirmedOrdersStayHeldAtTheDeadline() {
        fund(10000);
        String ref = cart(List.of(Map.of("merchantId", sellerA, "kind", "sale", "amount", 10000,
                "description", "Dress", "category", "fashion")));
        api.call("POST", "/v1/checkouts/" + ref + "/pay/wallet", customerToken, UUID.randomUUID().toString(), null);

        // A partial dispute takes a proportional share from each party, and a seller win puts it all back
        // into pending, because the item has not been delivered yet.
        var dispute = api.call("POST", "/v1/ops/disputes", ops, null,
                Map.of("checkout", ref, "line", 0, "amount", 5000, "reason", "Wrong size"));
        assertThat(bal(sellerA, "pending")).isEqualTo(7650 - 3825);
        assertThat(bal(marketplace, "pending")).isEqualTo(2200 - 1100);
        api.call("POST", "/v1/ops/disputes/" + dispute.body().get("id").asString() + "/resolve", ops, null,
                Map.of("outcome", "merchant_won", "note", "Size as listed"));
        assertThat(bal(sellerA, "pending")).isEqualTo(7650);
        assertThat(bal(marketplace, "pending")).isEqualTo(2200);
        assertThat(bal(sellerA, "reserved")).isZero();

        // A deadline alone is not evidence of fulfillment; neither funds nor fees may release.
        settleForced();
        assertThat(bal(sellerA, "available")).isZero();
        db.sql("UPDATE checkout SET delivery_deadline = now() - interval '1 minute' WHERE reference = :r")
                .param("r", ref).update();
        api.call("POST", "/v1/ops/settlements/run", ops, null, Map.of());
        assertThat(bal(sellerA, "available")).isZero();
        assertThat(bal(marketplace, "available")).isZero();
        assertThat(bal(sellerA, "pending")).isEqualTo(7650);
        assertThat(bal(marketplace, "pending")).isEqualTo(2200);
        assertThat(feeRecognized(ref)).isZero();
        assertThat(feeDeferred(ref)).isEqualTo(150);
        assertThat(db.sql("""
                SELECT a.delivery_confirmed_by FROM checkout_allocation a JOIN checkout c ON c.id = a.checkout_id
                WHERE c.reference = :r""").param("r", ref).query(String.class).optional().orElse(null))
                .isNull();
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void customerWonDisputeOnMomoIsReturnedThroughTheRail() {
        String ref = cart(List.of(Map.of("merchantId", sellerB, "kind", "sale", "amount", 20000,
                "description", "Phone", "category", "phones")));
        api.call("POST", "/v1/public/checkouts/" + ref + "/pay", null, UUID.randomUUID().toString(),
                Map.of("method", "momo", "phone", "0245550003", "network", "mtn"));
        String charge = db.sql("""
                SELECT t.reference FROM rail_transaction t JOIN payment_attempt p ON p.rail_tx = t.id
                JOIN checkout c ON c.id = p.checkout_id WHERE c.reference = :r""").param("r", ref)
                .query(String.class).single();
        simulated.settle(charge, true);
        callback(charge, "succeeded", 20000L);
        assertThat(bal(sellerB, "pending")).isEqualTo(20000 - 1000 - 200);

        long inFlight0 = ledger.balance(Ledger.REFUNDS_IN_FLIGHT);
        var dispute = api.call("POST", "/v1/ops/disputes", ops, null,
                Map.of("checkout", ref, "line", 0, "amount", 20000, "reason", "Counterfeit"));
        api.call("POST", "/v1/ops/disputes/" + dispute.body().get("id").asString() + "/resolve", ops, null,
                Map.of("outcome", "customer_won", "note", "Counterfeit confirmed"));
        assertThat(ledger.balance(Ledger.REFUNDS_IN_FLIGHT) - inFlight0).isEqualTo(20000);
        String refundRail = db.sql("""
                SELECT t.reference FROM refund r JOIN rail_transaction t ON t.id = r.rail_tx
                JOIN checkout c ON c.id = r.checkout_id WHERE c.reference = :r""").param("r", ref)
                .query(String.class).single();
        simulated.settle(refundRail, true);
        callback(refundRail, "succeeded", 20000L);
        assertThat(db.sql("""
                SELECT r.status FROM refund r JOIN checkout c ON c.id = r.checkout_id WHERE c.reference = :r""")
                .param("r", ref).query(String.class).single()).isEqualTo("completed");
        assertThat(ledger.balance(Ledger.REFUNDS_IN_FLIGHT)).isEqualTo(inFlight0);
        assertThat(bal(marketplace, "pending")).isZero();
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void gridValidation() {
        // A GH₵4 book cannot carry the GH₵5 minimum commission.
        var tooCheap = api.call("POST", "/v1/merchants/" + marketplace + "/checkouts", mpOwner,
                UUID.randomUUID().toString(), Map.of("allocations", List.of(
                        Map.of("merchantId", sellerA, "kind", "sale", "amount", 400, "category", "books"))));
        assertThat(tooCheap.status()).isEqualTo(400);
        assertThat(tooCheap.body().toString()).contains("fees_exceed_amount");
        // Only marketplaces have a grid, and only their own staff can change it.
        assertThat(api.call("PUT", "/v1/merchants/" + sellerA + "/fee-schedule", aOwner, null,
                Map.of("rules", List.of())).status()).isEqualTo(409);
        assertThat(api.call("PUT", "/v1/merchants/" + marketplace + "/fee-schedule", aOwner, null,
                Map.of("rules", List.of())).status()).isEqualTo(404);
        assertThat(api.call("PUT", "/v1/merchants/" + marketplace + "/fee-schedule", mpOwner, null,
                Map.of("rules", List.of(Map.of("category", "fashion", "commissionBps", 9000)))).status()).isEqualTo(400);
        assertThat(api.call("GET", "/v1/merchants/" + marketplace + "/fee-schedule", mpOwner, null, null)
                .body().get("deliveryConfirmDays").asInt()).isEqualTo(7);
    }

    void callback(String railReference, String status, Long amount) {
        var signed = simulated.callback(railReference, status, amount);
        int code = api.http.post().uri("/v1/rails/sandbox/callbacks")
                .header("x-sandbox-signature", signed.getKey()).header("Content-Type", "application/json")
                .body(signed.getValue()).exchange((req, res) -> res.getStatusCode().value());
        assertThat(code).isEqualTo(200);
    }
}
