package com.elepay.attack;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.identity.KillSwitchService;
import com.elepay.ledger.Ledger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared harness for the offensive-security attack suite. Each concrete subclass encodes attacks
 * that SHOULD be repelled and asserts the secure (fail-closed) outcome, so CI goes red if a hole
 * opens. Mirrors the key/merchant/intent minting conventions of
 * {@code com.elepay.stage1.Stage1BenchmarkAndPentestIT}; no new tables or schema are introduced.
 */
abstract class AttackSupport extends PostgresTestBase {
    protected static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired KillSwitchService killSwitchService;

    TestApi api;
    String ops1, ops2, owner;
    UUID merchant;

    @BeforeEach
    void baseSetUp() {
        api = new TestApi(port);
        killSwitchService.resetForTesting();
        String s = UUID.randomUUID().toString().substring(0, 8);
        ops1 = api.token("attack-ops-maker-" + s, "ops", "ops_admin");
        ops2 = api.token("attack-ops-checker-" + s, "ops", "ops_admin");
        owner = api.token("attack-owner-" + s);
        merchant = createActiveMerchant(owner, "Attack Merchant " + s);
    }

    protected UUID createActiveMerchant(String ownerToken, String name) {
        var created = api.call("POST", "/v1/merchants", ownerToken, null, Map.of(
                "legalName", name + " Ltd", "tradingName", name, "kind", "merchant",
                "contactPhone", "0241234567"));
        assertThat(created.status()).as(created.body().toString()).isEqualTo(200);
        UUID id = UUID.fromString(created.body().get("id").asString());
        var rev = api.call("POST", "/v1/ops/merchants/" + id + "/review", ops1, null, Map.of(
                "decision", "approve", "feeBps", 0, "feeFixed", 0, "settlementDelayHours", 0));
        assertThat(rev.status()).as(rev.body().toString()).isEqualTo(200);
        return id;
    }

    /** Credits the merchant's available GHS balance directly through the ledger (test funding). */
    protected void fundMerchantAvailable(UUID merchantId, long amountPesewas) {
        ledger.post(Ledger.Posting.of("attack-test-fund", merchantId + ":" + UUID.randomUUID(), "funding", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), amountPesewas),
                Ledger.Leg.credit(Ledger.code("merchant", merchantId, "available", "GHS"), amountPesewas))));
    }

    /** Adds a verified, non-cooling-off settlement destination and returns its id. */
    protected UUID addReadyDestination(UUID merchantId, String ownerToken, String msisdn) {
        var res = api.call("POST", "/v1/merchants/" + merchantId + "/settlement-destinations", ownerToken, null, Map.of(
                "type", "momo", "network", "mtn", "accountNumber", msisdn,
                "accountName", "Verified Recipient", "coolingOffHours", 0));
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        return UUID.fromString(res.body().get("id").asString());
    }

    protected String createKey(UUID merchantId, String ownerToken, Map<String, Object> request) {
        var res = api.call("POST", "/v1/merchants/" + merchantId + "/api-keys", ownerToken, null, request);
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        return res.body().get("secret").asString();
    }

    /** HTTP call carrying an API key and an attacker-controlled X-Forwarded-For chain. */
    protected TestApi.Response callWithKeyAndIp(String method, String path, String apiKey, String xff,
                                                String idempotencyKey, Object body) {
        var spec = switch (method) {
            case "GET" -> api.http.get().uri(path);
            case "PUT" -> api.http.put().uri(path).body(body == null ? Map.of() : body);
            case "DELETE" -> api.http.delete().uri(path);
            default -> api.http.post().uri(path).body(body == null ? Map.of() : body);
        };
        return spec.headers(h -> {
            if (apiKey != null) h.set("X-Elepay-Api-Key", apiKey);
            if (xff != null) h.set("X-Forwarded-For", xff);
            if (idempotencyKey != null) h.set("Idempotency-Key", idempotencyKey);
        }).exchange((req, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            JsonNode node = bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes);
            return new TestApi.Response(res.getStatusCode().value(), node);
        });
    }
}
