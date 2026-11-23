package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.ledger.Ledger;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/** Platform API orders, scoped credentials, delegated withdrawals and event recovery. */
class PlatformApiIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired Ledger ledger;

    TestApi api;
    String ops, mpOwner, sellerOwner;
    UUID marketplace, seller;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        ops = api.token("ops-" + s, "ops");
        mpOwner = api.token("mp-" + s);
        sellerOwner = api.token("seller-" + s);
        marketplace = merchant(mpOwner, "Sample Marketplace " + s, "marketplace");
        seller = merchant(sellerOwner, "Seller " + s, "merchant");
        String code = api.call("GET", "/v1/merchants/" + seller, sellerOwner, null, null).body().get("merchantCode").asString();
        api.call("POST", "/v1/merchants/" + marketplace + "/sellers", mpOwner, null, Map.of("merchantCode", code));
        api.call("POST", "/v1/merchants/" + seller + "/marketplaces/" + marketplace + "/accept", sellerOwner, null, null);
    }

    UUID merchant(String owner, String name, String kind) {
        UUID id = UUID.fromString(api.call("POST", "/v1/merchants", owner, null, Map.of("legalName", name, "tradingName", name,
                "kind", kind, "contactPhone", "0241234567")).body().get("id").asString());
        api.call("POST", "/v1/ops/merchants/" + id + "/review", ops, null, Map.of("decision", "approve"));
        return id;
    }

    String key(UUID merchant, String owner, String... scopes) {
        return api.call("POST", "/v1/merchants/" + merchant + "/api-keys", owner, null,
                Map.of("label", "server", "scopes", List.of(scopes))).body().get("secret").asString();
    }

    TestApi.Response withKey(String method, String path, String key, String idem, Object body) {
        return switch (method) {
            case "GET" -> api.http.get().uri(path).header("X-Elepay-Api-Key", key)
                    .exchange((rq, rs) -> new TestApi.Response(rs.getStatusCode().value(), read(rs)));
            default -> api.http.post().uri(path).header("X-Elepay-Api-Key", key)
                    .headers(h -> { if (idem != null) h.set("Idempotency-Key", idem); })
                    .body(body == null ? Map.of() : body)
                    .exchange((rq, rs) -> new TestApi.Response(rs.getStatusCode().value(), read(rs)));
        };
    }

    static JsonNode read(org.springframework.http.client.ClientHttpResponse rs) throws java.io.IOException {
        byte[] b = rs.getBody().readAllBytes();
        return b.length == 0 ? tools.jackson.databind.node.JsonNodeFactory.instance.nullNode()
                : new tools.jackson.databind.ObjectMapper().readTree(b);
    }

    @Test
    void platformCreatesQueriesAndCancelsOrdersAndRecoversEvents() {
        String collect = key(marketplace, mpOwner, "view", "collect", "refund");
        Map<String, Object> order = Map.of("orderReference", "ELM-1001", "description", "Sample Marketplace order 1001",
                "allocations", List.of(Map.of("merchantId", seller, "kind", "sale", "amount", 2500)));
        String idem = UUID.randomUUID().toString();
        var created = withKey("POST", "/v1/merchants/" + marketplace + "/checkouts", collect, idem, order);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(200);
        assertThat(withKey("POST", "/v1/merchants/" + marketplace + "/checkouts", collect, idem, order).body())
                .isEqualTo(created.body());
        assertThat(withKey("POST", "/v1/merchants/" + marketplace + "/checkouts", collect, UUID.randomUUID().toString(), order)
                .body().get("code").asString()).isEqualTo("order_exists");
        JsonNode byOrder = withKey("GET", "/v1/merchants/" + marketplace + "/orders/ELM-1001", collect, null, null).body();
        assertThat(byOrder.get("reference").asString()).isEqualTo(created.body().get("reference").asString());
        assertThat(byOrder.get("status").asString()).isEqualTo("open");

        // Cancel the unpaid order; a cancelled order cannot be paid.
        String reference = created.body().get("reference").asString();
        assertThat(withKey("POST", "/v1/merchants/" + marketplace + "/checkouts/" + reference + "/cancel", collect, null, null)
                .body().get("status").asString()).isEqualTo("cancelled");
        assertThat(api.call("POST", "/v1/public/checkouts/" + reference + "/pay", null, UUID.randomUUID().toString(),
                Map.of("method", "momo", "phone", "0245550005", "network", "mtn")).status()).isEqualTo(409);

        // Event recovery API: stable ids in sequence order, scoped to the merchant.
        JsonNode events = withKey("GET", "/v1/merchants/" + marketplace + "/events?after=0", collect, null, null).body();
        assertThat(events.findValues("type").stream().map(JsonNode::asString).toList()).contains("checkout.cancelled");
        long last = events.get(events.size() - 1).get("sequence").asLong();
        assertThat(withKey("GET", "/v1/merchants/" + marketplace + "/events?after=" + last, collect, null, null).body()).isEmpty();
        assertThat(withKey("GET", "/v1/merchants/" + seller + "/events", collect, null, null).status()).isEqualTo(404);

        // The collect-scoped key cannot withdraw or submit payouts.
        assertThat(withKey("POST", "/v1/merchants/" + marketplace + "/payout-batches", collect, UUID.randomUUID().toString(),
                Map.of("items", List.of(Map.of("reference", "x", "amount", 1, "type", "momo", "network", "mtn",
                        "accountNumber", "0245550005", "accountName", "X")))).status()).isEqualTo(403);
    }

    @Test
    void withdrawalScopeVerifiedDestinationAndDelegatedWithdrawal() {
        ledger.post(Ledger.Posting.of("test-funding", seller.toString(), "funding", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 50_000),
                Ledger.Leg.credit(Ledger.code("merchant", seller, "available", "GHS"), 50_000))));
        JsonNode destination = api.call("POST", "/v1/merchants/" + seller + "/settlement-destinations", sellerOwner, null,
                Map.of("type", "momo", "network", "mtn", "accountNumber", "0245550005", "accountName", "Seller")).body();
        assertThat(destination.get("verification").asString()).isEqualTo("verified");
        JsonNode unverified = api.call("POST", "/v1/merchants/" + seller + "/settlement-destinations", sellerOwner, null,
                Map.of("type", "bank", "network", "GCB", "accountNumber", "1234567899", "accountName", "Seller")).body();
        assertThat(unverified.get("verification").asString()).isEqualTo("unverified");
        assertThat(api.call("POST", "/v1/merchants/" + seller + "/settlement-destinations", sellerOwner, null,
                Map.of("type", "bank", "network", "GCB", "accountNumber", "1234567890", "accountName", "Seller"))
                .body().get("code").asString()).isEqualTo("account_not_found");

        String withdrawKey = key(seller, sellerOwner, "view", "withdraw");
        String path = "/v1/merchants/" + seller + "/withdrawals";
        assertThat(withKey("POST", path, withdrawKey, UUID.randomUUID().toString(),
                Map.of("amount", 1000, "destinationId", unverified.get("id").asString())).body().get("code").asString())
                .isEqualTo("destination_unverified");
        String idem = UUID.randomUUID().toString();
        var w = withKey("POST", path, withdrawKey, idem, Map.of("amount", 10_000, "destinationId", destination.get("id").asString()));
        assertThat(w.status()).as(w.body().toString()).isEqualTo(200);
        String withdrawal = w.body().get("items").get(0).get("reference").asString();
        assertThat(withKey("POST", path, withdrawKey, idem, Map.of("amount", 10_000,
                "destinationId", destination.get("id").asString())).body().get("reference").asString())
                .isEqualTo(w.body().get("reference").asString());
        assertThat(ledger.balance(Ledger.code("merchant", seller, "reserved", "GHS"))).isEqualTo(10_000);
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);
        assertThat(withKey("GET", path + "/" + withdrawal, withdrawKey, null, null).body().get("status").asString())
                .isEqualTo("succeeded");
        assertThat(withKey("GET", path, withdrawKey, null, null).body()).hasSize(1);

        // A platform key acts for the seller only with delegated withdrawal permission.
        String platformKey = key(marketplace, mpOwner, "view", "withdraw");
        Map<String, Object> body = Map.of("amount", 5_000, "destinationId", destination.get("id").asString());
        assertThat(withKey("POST", path, platformKey, UUID.randomUUID().toString(), body).status()).isEqualTo(404);
        api.call("PUT", "/v1/merchants/" + seller + "/delegations/" + marketplace, sellerOwner, null,
                Map.of("permissions", List.of("withdraw")));
        assertThat(withKey("POST", path, platformKey, UUID.randomUUID().toString(), body).status()).isEqualTo(200);
        // Delegation covers withdrawals only, not viewing balances.
        assertThat(withKey("GET", "/v1/merchants/" + seller + "/balances", platformKey, null, null).status()).isEqualTo(404);
        api.call("DELETE", "/v1/merchants/" + seller + "/delegations/" + marketplace, sellerOwner, null, null);
        assertThat(withKey("POST", path, platformKey, UUID.randomUUID().toString(), body).status()).isEqualTo(404);
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);
        assertThat(ledger.balance(Ledger.code("merchant", seller, "available", "GHS"))).isEqualTo(35_000);
        assertThat(ledger.integrity().ok()).isTrue();
    }
}
