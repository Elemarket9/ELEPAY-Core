package com.elepay.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.identity.KillSwitchService;
import com.elepay.ledger.Ledger;
import com.elepay.payout.PayoutVelocityService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The attacks against cooling-off, API key tiers, kill switches, encoded paths,
 * risk writes, financial metrics and wallet top-ups must not succeed.
 */
@TestPropertySource(properties = {
        "elepay.security.destination-cooling-off-hours=24",
        "elepay.security.destination-oob-required=true",
        "elepay.security.destination-name-match-required=true",
        "elepay.security.dual-control.enforced=true",
        "elepay.security.trusted-proxies=",
        "elepay.wallet.require-recipient-confirmation=true",
        "elepay.wallet.topup-own-msisdn-only=true",
        "elepay.rate-limits.multiplier=1"
})
class SecurityRegressionIT extends PostgresTestBase {
    static final ObjectMapper JSON = new ObjectMapper();
    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired KillSwitchService killSwitch;
    @Autowired PayoutVelocityService velocity;
    TestApi api;
    String ops;
    String owner;
    UUID merchant;
    final HttpClient raw = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        killSwitch.resetForTesting();
        velocity.resetWindowsForTesting();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ops = api.token("ops-" + suffix, "ops", "ops_admin");
        owner = api.token("owner-" + suffix);
        var created = api.call("POST", "/v1/merchants", owner, null, Map.of(
                "legalName", "Regression " + suffix + " Ltd", "tradingName", "Regression " + suffix,
                "kind", "merchant", "contactPhone", "0241234567"));
        merchant = UUID.fromString(created.body().get("id").asString());
        assertThat(api.call("POST", "/v1/ops/merchants/" + merchant + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", 0, "feeFixed", 0, "settlementDelayHours", 0)).status()).isEqualTo(200);
    }

    @AfterEach
    void tearDown() {
        killSwitch.resetForTesting();
        velocity.resetWindowsForTesting();
    }

    void fundMerchant(long pesewas) {
        ledger.post(Ledger.Posting.of("security-regression", merchant + ":" + UUID.randomUUID(), "funding", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), pesewas),
                Ledger.Leg.credit(Ledger.code("merchant", merchant, "available", "GHS"), pesewas))));
    }

    TestApi.Response keyCall(String path, String key, String forwardedFor, String idempotency, Object body) {
        return api.http.post().uri(path).body(body == null ? Map.of() : body)
                .headers(h -> {
                    h.set("X-Elepay-Api-Key", key);
                    if (forwardedFor != null) h.set("X-Forwarded-For", forwardedFor);
                    if (idempotency != null) h.set("Idempotency-Key", idempotency);
                })
                .exchange((q, r) -> {
                    byte[] bytes = r.getBody().readAllBytes();
                    return new TestApi.Response(r.getStatusCode().value(),
                            bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes));
                });
    }

    TestApi.Response rawCall(String method, String path, String bearer, String idempotency, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (idempotency != null) builder.header("Idempotency-Key", idempotency);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        var response = raw.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode node = response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
        return new TestApi.Response(response.statusCode(), node);
    }

    UUID addDestination(Map<String, Object> extra, String msisdn) {
        var body = new HashMap<String, Object>(Map.of(
                "type", "momo", "network", "mtn", "accountNumber", msisdn, "accountName", "Attacker"));
        body.putAll(extra);
        var response = api.call("POST", "/v1/merchants/" + merchant + "/settlement-destinations", owner, null, body);
        assertThat(response.status()).isEqualTo(200);
        return UUID.fromString(response.body().get("id").asString());
    }

    @Test
    void clientCannotClearTheDestinationCoolingOffHold() {
        fundMerchant(100_000);
        UUID destination = addDestination(Map.of("coolingOffHours", 0), "0245551002");
        assertThat(destination).isNotNull();
        var withdrawal = api.call("POST", "/v1/merchants/" + merchant + "/withdrawals", owner, UUID.randomUUID().toString(),
                Map.of("amount", 5000, "destinationId", destination));
        assertThat(withdrawal.status()).isNotEqualTo(200);
    }

    @Test
    void emptyConfirmationTokenDoesNotConfirmADestination() {
        UUID destination = addDestination(Map.of("requireOob", true), "0245551003");
        var response = api.call("POST", "/v1/merchants/" + merchant + "/settlement-destinations/" + destination + "/confirm-oob",
                owner, null, Map.of());
        assertThat(response.status()).isNotEqualTo(200);
    }

    @Test
    void dashboardKeyWithoutATypeCannotPayOut() {
        fundMerchant(100_000);
        var created = api.call("POST", "/v1/developer/merchants/" + merchant + "/keys", owner, null,
                Map.of("label", "ui-key", "environment", "live", "scopes", List.of("view", "withdraw", "payouts")));
        if (created.status() == 200) {
            String key = created.body().get("apiKey").asString();
            assertThat(key).doesNotStartWith("ek_");
            var batch = keyCall("/v1/merchants/" + merchant + "/payout-batches", key, "198.51.100.99", UUID.randomUUID().toString(),
                    Map.of("items", List.of(Map.of("reference", "reg-" + UUID.randomUUID().toString().substring(0, 6),
                            "amount", 5000, "type", "momo", "network", "mtn", "accountNumber", "0245559999",
                            "accountName", "Any Attacker"))));
            assertThat(batch.status()).isNotEqualTo(200);
        } else {
            assertThat(created.status()).isGreaterThanOrEqualTo(400);
        }
    }

    @Test
    void rotatingAPayoutKeyKeepsItsTypeAndAllowlist() {
        var created = api.call("POST", "/v1/developer/merchants/" + merchant + "/keys", owner, null,
                Map.of("label", "payout", "environment", "live", "keyType", "payout", "allowedIps", List.of("203.0.113.10/32")));
        assertThat(created.status()).isEqualTo(200);
        String id = created.body().get("id").asString();
        var rotated = api.call("POST", "/v1/developer/merchants/" + merchant + "/keys/" + id + "/rotate", owner, null, Map.of());
        assertThat(rotated.status()).isEqualTo(200);
        String replacement = rotated.body().get("apiKey").asString();
        assertThat(replacement).startsWith("payout_live_");
        fundMerchant(100_000);
        var batch = keyCall("/v1/merchants/" + merchant + "/payout-batches", replacement, "198.51.100.99",
                UUID.randomUUID().toString(), Map.of("items", List.of(Map.of(
                        "reference", "rot-" + UUID.randomUUID().toString().substring(0, 6), "amount", 5000,
                        "type", "momo", "network", "mtn", "accountNumber", "0245558888", "accountName", "Any Attacker"))));
        assertThat(batch.status()).isNotEqualTo(200);
    }

    @Test
    void percentEncodedPathsDoNotBypassTheKillSwitch() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String senderToken = api.token("cust-a-" + suffix);
        api.enroll(ops, "cust-a-" + suffix, TestApi.uniquePhone(), "Alice");
        UUID recipient = api.enroll(ops, "cust-b-" + suffix, TestApi.uniquePhone(), "Bob");
        api.call("POST", "/admin/kill-switch/modules/consumer-w2w", ops, null, Map.of("enabled", false, "reason", "off"));
        var encoded = rawCall("POST", "/v1/%77allet/transfers", senderToken, UUID.randomUUID().toString(),
                Map.of("recipient", recipient, "amount", 100));
        assertThat(encoded.status()).isNotEqualTo(200);
    }

    @Test
    void percentEncodedPathsDoNotBypassRateLimits() throws Exception {
        int limited = 0;
        int encodedOk = 0;
        for (int i = 0; i < 150; i++) {
            if (rawCall("GET", "/v1/public/checkouts/EP-NOPE" + i, null, null, null).status() == 429) limited++;
        }
        for (int i = 0; i < 20; i++) {
            int status = rawCall("GET", "/v1/%70ublic/checkouts/EP-NOPE" + i, null, null, null).status();
            if (status != 400 && status != 429) encodedOk++;
        }
        assertThat(limited).isGreaterThan(0);
        assertThat(encodedOk).isZero();
    }

    @Test
    void customersCannotWriteRiskDataOrReadFinancialMetrics() {
        String customer = api.token("random-user-" + UUID.randomUUID());
        var mule = api.call("POST", "/api/v1/risk/mule/flow", customer, null, Map.of(
                "sourceAccountId", "victim-acct", "destinationAccountId", "x", "amountMinorUnits", 1,
                "currency", "GHS", "transferredAt", "2026-11-23T10:00:00Z"));
        var metrics = api.call("GET", "/v1/ops/metrics/financial", customer, null, null);
        assertThat(mule.status()).isNotEqualTo(200);
        assertThat(metrics.status()).isNotEqualTo(200);
    }

    @Test
    void oneOperatorCannotLiftAGlobalFreeze() {
        api.call("POST", "/admin/kill-switch/global", ops, null, Map.of("frozen", true, "reason", "incident"));
        assertThat(killSwitch.isGlobalFrozen()).isTrue();
        api.call("POST", "/admin/kill-switch/global", ops, null, Map.of("frozen", false));
        assertThat(killSwitch.isGlobalFrozen()).isTrue();
    }

    @Test
    void topUpCannotChargeAnotherPhoneAboveTheTierCap() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String customer = api.token("cust-t-" + suffix);
        api.enroll(ops, "cust-t-" + suffix, TestApi.uniquePhone(), "Tier1");
        var response = api.call("POST", "/v1/wallet/topups", customer, UUID.randomUUID().toString(),
                Map.of("amount", 50_000_000, "method", "momo", "phone", "0249998887", "network", "mtn"));
        assertThat(response.status()).isNotEqualTo(200);
    }
}
