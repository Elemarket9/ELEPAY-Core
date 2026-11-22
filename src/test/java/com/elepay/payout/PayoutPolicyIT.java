package com.elepay.payout;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.ledger.Ledger;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Marketplaces choose on-request or automatic withdrawal for their sellers; independent merchants choose. */
class PayoutPolicyIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;

    TestApi api;
    String ops, mpOwner, aOwner, bOwner, cOwner, customerToken;
    UUID marketplace, sellerA, sellerB, independent, customer;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        ops = api.token("ops-" + s, "ops");
        mpOwner = api.token("mp-" + s);
        aOwner = api.token("a-" + s);
        bOwner = api.token("b-" + s);
        cOwner = api.token("c-" + s);
        marketplace = merchant(mpOwner, "Platform " + s, "marketplace");
        sellerA = merchant(aOwner, "Seller A " + s, "merchant");
        sellerB = merchant(bOwner, "Seller B " + s, "merchant");
        independent = merchant(cOwner, "Corner Shop " + s, "merchant");
        for (var seller : List.of(Map.entry(sellerA, aOwner), Map.entry(sellerB, bOwner))) {
            String code = api.call("GET", "/v1/merchants/" + seller.getKey(), seller.getValue(), null, null)
                    .body().get("merchantCode").asString();
            api.call("POST", "/v1/merchants/" + marketplace + "/sellers", mpOwner, null, Map.of("merchantCode", code));
            api.call("POST", "/v1/merchants/" + seller.getKey() + "/marketplaces/" + marketplace + "/accept",
                    seller.getValue(), null, null);
        }
        String subject = "customer-" + s;
        customerToken = api.token(subject);
        customer = api.enroll(ops, subject, TestApi.uniquePhone(), "Ama Buyer");
        api.call("POST", "/v1/wallet/topups", customerToken, "fund-" + customer,
                Map.of("amount", 100000, "method", "momo", "phone", "0245550005", "network", "mtn"));
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);
    }

    UUID merchant(String owner, String name, String kind) {
        var created = api.call("POST", "/v1/merchants", owner, null, Map.of("legalName", name + " Ltd",
                "tradingName", name, "kind", kind, "contactPhone", "0241234567"));
        UUID id = UUID.fromString(created.body().get("id").asString());
        api.call("POST", "/v1/ops/merchants/" + id + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", 0, "feeFixed", 0, "settlementDelayHours", 0));
        return id;
    }

    String destination(UUID merchant, String owner, String phone) {
        var d = api.call("POST", "/v1/merchants/" + merchant + "/settlement-destinations", owner, null, Map.of(
                "type", "momo", "network", "mtn", "accountNumber", phone, "accountName", "Account " + phone));
        assertThat(d.status()).as(d.body().toString()).isEqualTo(200);
        String id = d.body().get("id").asString();
        api.call("POST", "/v1/ops/merchants/" + merchant + "/settlement-destinations/" + id + "/verify", ops, null, null);
        return id;
    }

    long available(UUID merchant) { return ledger.balance(Ledger.code("merchant", merchant, "available", "GHS")); }

    /** Marketplace sale to A and B, paid, delivered and settled into their available balances. */
    void sell(long toA, long toB) {
        var created = api.call("POST", "/v1/merchants/" + marketplace + "/checkouts", mpOwner, UUID.randomUUID().toString(),
                Map.of("allocations", List.of(Map.of("merchantId", sellerA, "kind", "sale", "amount", toA),
                        Map.of("merchantId", sellerB, "kind", "sale", "amount", toB))));
        String ref = created.body().get("reference").asString();
        api.call("POST", "/v1/checkouts/" + ref + "/pay/wallet", customerToken, UUID.randomUUID().toString(), null);
        // Delivery releases the lines at once: these sellers have no settlement delay.
        api.call("POST", "/v1/merchants/" + marketplace + "/checkouts/" + ref + "/deliveries", mpOwner, null, Map.of());
    }

    JsonNode runAutomatic() {
        var r = api.call("POST", "/v1/ops/payouts/automatic/run", ops, null, null);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.body();
    }

    @Test
    void marketplaceChoosesAutomaticForAllAndOnRequestForOneSeller() {
        assertThat(api.call("GET", "/v1/merchants/" + sellerA + "/payout-policy", aOwner, null, null)
                .body().get("mode").asString()).isEqualTo("on_request");

        // The platform puts its whole network on automatic withdrawal above GH₵10, except seller B.
        var def = api.call("PUT", "/v1/merchants/" + marketplace + "/seller-payout-policy", mpOwner, null,
                Map.of("mode", "automatic", "minAmount", 1000));
        assertThat(def.status()).as(def.body().toString()).isEqualTo(200);
        assertThat(api.call("PUT", "/v1/merchants/" + marketplace + "/sellers/" + sellerB + "/payout-policy", mpOwner,
                null, Map.of("mode", "on_request")).status()).isEqualTo(200);

        JsonNode a = api.call("GET", "/v1/merchants/" + sellerA + "/payout-policy", aOwner, null, null).body();
        assertThat(a.get("mode").asString()).isEqualTo("automatic");
        assertThat(a.get("source").asString()).isEqualTo("platform");
        assertThat(a.get("minAmount").asLong()).isEqualTo(1000);
        assertThat(api.call("GET", "/v1/merchants/" + marketplace + "/sellers/" + sellerB + "/payout-policy", mpOwner,
                null, null).body().get("mode").asString()).isEqualTo("on_request");

        // The seller cannot overrule the platform, but chooses where the money goes.
        assertThat(api.call("PUT", "/v1/merchants/" + sellerA + "/payout-policy", aOwner, null,
                Map.of("mode", "on_request")).status()).isEqualTo(409);
        String aWallet = destination(sellerA, aOwner, "0245550016");
        destination(sellerA, aOwner, "0245550017");
        var chosen = api.call("PUT", "/v1/merchants/" + sellerA + "/payout-policy", aOwner, null,
                Map.of("destinationId", aWallet));
        assertThat(chosen.status()).as(chosen.body().toString()).isEqualTo(200);
        String bWallet = destination(sellerB, bOwner, "0245550026");

        sell(30000, 20000);
        assertThat(available(sellerA)).isEqualTo(30000);
        assertThat(available(sellerB)).isEqualTo(20000);

        runAutomatic();
        // A is paid out in full to the chosen wallet; B waits for a request.
        assertThat(available(sellerA)).isZero();
        assertThat(available(sellerB)).isEqualTo(20000);
        JsonNode aWithdrawals = api.call("GET", "/v1/merchants/" + sellerA + "/withdrawals", aOwner, null, null).body();
        assertThat(aWithdrawals).hasSize(1);
        assertThat(aWithdrawals.get(0).get("amount").asLong()).isEqualTo(30000);
        assertThat(aWithdrawals.get(0).get("destination").get("accountNumber").asString()).isEqualTo("233245550016");
        // Running again pays nothing twice.
        runAutomatic();
        assertThat(api.call("GET", "/v1/merchants/" + sellerA + "/withdrawals", aOwner, null, null).body()).hasSize(1);

        // B withdraws on request whenever it wants.
        var manual = api.call("POST", "/v1/merchants/" + sellerB + "/withdrawals", bOwner, UUID.randomUUID().toString(),
                Map.of("amount", 15000, "destinationId", bWallet));
        assertThat(manual.status()).as(manual.body().toString()).isEqualTo(200);
        assertThat(available(sellerB)).isEqualTo(5000);

        // Below the GH₵10 minimum nothing moves; once it is reached, it does.
        sell(500, 100);
        runAutomatic();
        assertThat(available(sellerA)).isEqualTo(500);
        sell(700, 100);
        runAutomatic();
        assertThat(available(sellerA)).isZero();

        // The platform moves B to automatic by clearing the override.
        api.call("DELETE", "/v1/merchants/" + marketplace + "/sellers/" + sellerB + "/payout-policy", mpOwner, null, null);
        runAutomatic();
        assertThat(available(sellerB)).isZero();
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void independentMerchantChoosesAndAutomaticWithdrawalNeedsAVerifiedDestination() {
        var own = api.call("PUT", "/v1/merchants/" + independent + "/payout-policy", cOwner, null, Map.of("mode", "automatic"));
        assertThat(own.status()).as(own.body().toString()).isEqualTo(200);
        assertThat(own.body().get("source").asString()).isEqualTo("merchant");

        String ref = api.call("POST", "/v1/merchants/" + independent + "/checkouts", cOwner, UUID.randomUUID().toString(),
                Map.of("amount", 4000)).body().get("reference").asString();
        api.call("POST", "/v1/checkouts/" + ref + "/pay/wallet", customerToken, UUID.randomUUID().toString(), null);
        api.call("POST", "/v1/ops/settlements/run", ops, null, Map.of("merchantId", independent));
        assertThat(available(independent)).isEqualTo(4000);

        // No verified destination: the money stays and operations are told.
        runAutomatic();
        assertThat(available(independent)).isEqualTo(4000);
        assertThat(db.sql("SELECT count(*) FROM ops_item WHERE kind = 'auto_withdrawal_no_destination' AND subject = :s")
                .param("s", "merchant:" + independent).query(Long.class).single()).isEqualTo(1);

        destination(independent, cOwner, "0245550036");
        runAutomatic();
        assertThat(available(independent)).isZero();
    }

    @Test
    void onlyTheMarketplaceDecidesForItsSellers() {
        assertThat(api.call("PUT", "/v1/merchants/" + independent + "/seller-payout-policy", cOwner, null,
                Map.of("mode", "automatic")).status()).isEqualTo(409);
        assertThat(api.call("PUT", "/v1/merchants/" + marketplace + "/seller-payout-policy", aOwner, null,
                Map.of("mode", "automatic")).status()).isEqualTo(404);
        assertThat(api.call("PUT", "/v1/merchants/" + marketplace + "/sellers/" + independent + "/payout-policy",
                mpOwner, null, Map.of("mode", "automatic")).status()).isEqualTo(404);
        assertThat(api.call("PUT", "/v1/merchants/" + marketplace + "/seller-payout-policy", mpOwner, null,
                Map.of("mode", "weekly")).status()).isEqualTo(400);
        // A seller's own destination is never shown to the marketplace.
        api.call("PUT", "/v1/merchants/" + marketplace + "/seller-payout-policy", mpOwner, null, Map.of("mode", "automatic"));
        String wallet = destination(sellerA, aOwner, "0245550046");
        api.call("PUT", "/v1/merchants/" + sellerA + "/payout-policy", aOwner, null, Map.of("destinationId", wallet));
        assertThat(api.call("GET", "/v1/merchants/" + marketplace + "/sellers/" + sellerA + "/payout-policy", mpOwner,
                null, null).body().get("destinationId").isNull()).isTrue();
    }
}
