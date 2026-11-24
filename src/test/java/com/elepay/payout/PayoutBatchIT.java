package com.elepay.payout;

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

/** Acceptance test 4: batch payouts with successful, failed and returned items; retries never pay twice. */
class PayoutBatchIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired SimulatedRail simulated;

    TestApi api;
    String ops, owner;
    UUID platform;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        ops = api.token("ops-" + s, "ops");
        owner = api.token("platform-" + s);
        platform = UUID.fromString(api.call("POST", "/v1/merchants", owner, null, Map.of("legalName", "Ride Co " + s,
                "tradingName", "Ride Co " + s, "kind", "merchant", "contactPhone", "0241234567")).body().get("id").asString());
        api.call("POST", "/v1/ops/merchants/" + platform + "/review", ops, null, Map.of("decision", "approve"));
        // Test funding of the platform's ELEPAY balance (earnings it will pay out).
        ledger.post(Ledger.Posting.of("test-funding", s, "platform_funding", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 100_000),
                Ledger.Leg.credit(Ledger.code("merchant", platform, "available", "GHS"), 100_000))));
        // Register pre-approved, verified settlement destinations past cooling-off for batch allowlist enforcement.
        for (String[] dest : List.of(
                new String[]{"momo", "mtn", "233245550005", "Driver drv-1"},
                new String[]{"momo", "telecel", "233205550001", "Driver drv-2"},
                new String[]{"momo", "mtn", "233245550006", "Driver drv-3"},
                new String[]{"bank", "GCB", "1234567890", "Driver drv-4"},
                new String[]{"momo", "mtn", "233245550002", "Driver drv-5"})) {
            db.sql("""
                    INSERT INTO settlement_destination
                        (id, owner_type, owner_id, type, network, account_number, account_name, verification, verified_name, status, created_by, cooling_off_until)
                    VALUES (:id, 'merchant', :m, :t, :n, :a, :name, 'verified', :name, 'active', 'test', now() - interval '1 hour')""")
                    .param("id", UUID.randomUUID()).param("m", platform).param("t", dest[0])
                    .param("n", dest[1]).param("a", dest[2]).param("name", dest[3]).update();
        }
    }

    long available() { return ledger.balance(Ledger.code("merchant", platform, "available", "GHS")); }
    long reserved() { return ledger.balance(Ledger.code("merchant", platform, "reserved", "GHS")); }

    Map<String, Object> item(String ref, long amount, String type, String network, String account, String platformName) {
        return Map.of("reference", ref, "amount", amount, "type", type, "network", network,
                "accountNumber", account, "accountName", "Driver " + ref, "platform", platformName);
    }

    JsonNode find(JsonNode batch, String clientRef) {
        for (JsonNode item : batch.get("items")) if (item.get("clientReference").asString().equals(clientRef)) return item;
        throw new AssertionError(clientRef);
    }

    void callback(String railRef, String status) {
        var signed = simulated.callback(railRef, status, null);
        api.http.post().uri("/v1/rails/sandbox/callbacks").header("x-sandbox-signature", signed.getKey())
                .header("Content-Type", "application/json").body(signed.getValue())
                .exchange((req, res) -> res.getStatusCode().value());
    }

    String railRef(String payoutRef) {
        return db.sql("SELECT t.reference FROM payout p JOIN rail_transaction t ON t.id = p.rail_tx WHERE p.reference = :r")
                .param("r", payoutRef).query(String.class).single();
    }

    @Test
    void batchWithSuccessfulFailedReturnedRejectedAndUnknownItems() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("description", "Weekly driver earnings", "items", List.of(
                item("drv-1", 10_000, "momo", "mtn", "0245550005", "bolt"),      // succeeds
                item("drv-2", 20_000, "momo", "telecel", "0205550001", "bolt"),  // declined
                item("drv-3", 15_000, "momo", "mtn", "0245550006", "yango"),     // succeeds, later returned
                item("drv-4", 5_000, "bank", "GCB", "1234567890", "x"),          // account not found: rejected
                item("drv-5", 7_000, "momo", "mtn", "0245550002", "tiktok")));   // response times out
        var created = api.call("POST", "/v1/merchants/" + platform + "/payout-batches", owner, key, body);
        assertThat(created.status()).isEqualTo(200);
        JsonNode batch = created.body();
        assertThat(find(batch, "drv-2").get("status").asString()).isEqualTo("failed");
        assertThat(find(batch, "drv-4").get("status").asString()).isEqualTo("rejected");
        assertThat(find(batch, "drv-4").get("verification").asString()).isEqualTo("not_found");
        assertThat(find(batch, "drv-1").get("verification").asString()).isEqualTo("verified");
        // Reserved: drv-1, drv-3, drv-5. Released: drv-2. Never reserved: drv-4.
        assertThat(reserved()).isEqualTo(32_000);
        assertThat(available()).isEqualTo(68_000);

        // Replaying the same request returns the same batch without new reservations.
        var replay = api.call("POST", "/v1/merchants/" + platform + "/payout-batches", owner, key, body);
        assertThat(replay.body().get("reference").asString()).isEqualTo(batch.get("reference").asString());
        assertThat(db.sql("SELECT count(*) FROM payout WHERE owner_id = :o").param("o", platform).query(Long.class).single())
                .isEqualTo(5);

        // Delayed confirmations resolve by status query, including the timed-out item.
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);
        JsonNode after = api.call("GET", "/v1/merchants/" + platform + "/payout-batches/" + batch.get("reference").asString(),
                owner, null, null).body();
        assertThat(find(after, "drv-1").get("status").asString()).isEqualTo("succeeded");
        assertThat(find(after, "drv-3").get("status").asString()).isEqualTo("succeeded");
        assertThat(find(after, "drv-5").get("status").asString()).isEqualTo("succeeded");
        assertThat(reserved()).isZero();
        assertThat(available()).isEqualTo(68_000);
        String drv5 = find(after, "drv-5").get("reference").asString();
        assertThat(db.sql("SELECT count(*) FROM rail_transaction WHERE purpose = 'payout' AND purpose_id = (SELECT id FROM payout WHERE reference = :r)")
                .param("r", drv5).query(Long.class).single()).isEqualTo(1);

        // Duplicate success callback changes nothing; the receiving network returns drv-3.
        String drv1 = find(after, "drv-1").get("reference").asString();
        callback(railRef(drv1), "succeeded");
        String drv3 = find(after, "drv-3").get("reference").asString();
        simulated.returnPayout(railRef(drv3));
        callback(railRef(drv3), "returned");
        callback(railRef(drv3), "returned");
        assertThat(available()).isEqualTo(83_000);
        JsonNode returned = api.call("GET", "/v1/merchants/" + platform + "/payout-batches/" + batch.get("reference").asString(),
                owner, null, null).body();
        assertThat(find(returned, "drv-3").get("status").asString()).isEqualTo("returned");
        assertThat(returned.get("statusCounts").get("succeeded").asLong()).isEqualTo(2);

        // Retries: never for paid items; once for failed or returned items.
        assertThat(api.call("POST", "/v1/merchants/" + platform + "/payouts/" + drv1 + "/retry", owner,
                UUID.randomUUID().toString(), null).body().get("code").asString()).isEqualTo("payout_not_retryable");
        String drv2 = find(after, "drv-2").get("reference").asString();
        String retryKey = UUID.randomUUID().toString();
        var retry = api.call("POST", "/v1/merchants/" + platform + "/payouts/" + drv2 + "/retry", owner, retryKey, null);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(api.call("POST", "/v1/merchants/" + platform + "/payouts/" + drv2 + "/retry", owner, retryKey, null)
                .body().get("reference").asString()).isEqualTo(retry.body().get("reference").asString());
        assertThat(api.call("POST", "/v1/merchants/" + platform + "/payouts/" + drv2 + "/retry", owner,
                UUID.randomUUID().toString(), null).body().get("code").asString()).isEqualTo("already_retried");
        // The retry is again declined by the simulated network (same account), so funds come back.
        assertThat(retry.body().get("items").get(0).get("status").asString()).isEqualTo("failed");
        var retry3 = api.call("POST", "/v1/merchants/" + platform + "/payouts/" + drv3 + "/retry", owner,
                UUID.randomUUID().toString(), null);
        assertThat(retry3.status()).isEqualTo(200);
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);

        // Each recipient was paid at most once per successful item.
        long paidDrv3 = db.sql("SELECT count(*) FROM payout WHERE owner_id = :o AND client_reference = 'drv-3' AND status = 'succeeded'")
                .param("o", platform).query(Long.class).single();
        assertThat(paidDrv3).isEqualTo(1);
        assertThat(available()).isEqualTo(100_000 - 10_000 - 7_000 - 15_000);
        assertThat(reserved()).isZero();
        assertThat(ledger.integrity().ok()).isTrue();

        // Reconciliation: completed payouts equal what left through the rail.
        long sent = db.sql("SELECT coalesce(sum(amount),0) FROM payout WHERE owner_id = :o AND status = 'succeeded'")
                .param("o", platform).query(Long.class).single();
        assertThat(sent).isEqualTo(32_000);

        // Platform routes are reported, none claimed as integrated without evidence.
        JsonNode routes = api.call("GET", "/v1/ops/platform-payout-routes", ops, null, null).body();
        assertThat(routes.findValues("status").stream().map(JsonNode::asString).distinct().toList())
                .containsExactly("not_verified");
    }

    @Test
    void merchantWithdrawalToSettlementDestinationAndRoleChecks() {
        UUID destination = UUID.fromString(api.call("POST", "/v1/merchants/" + platform + "/settlement-destinations",
                owner, null, Map.of("type", "momo", "network", "mtn", "accountNumber", "0245550005",
                        "accountName", "Ride Co")).body().get("id").asString());
        String cashier = "cashier-" + UUID.randomUUID();
        UUID branch = UUID.fromString(api.call("POST", "/v1/merchants/" + platform + "/branches", owner, null,
                Map.of("name", "Main")).body().get("id").asString());
        api.call("PUT", "/v1/merchants/" + platform + "/members/" + cashier, owner, null,
                Map.of("role", "cashier", "branchId", branch));
        assertThat(api.call("POST", "/v1/merchants/" + platform + "/withdrawals", api.token(cashier),
                UUID.randomUUID().toString(), Map.of("amount", 1000, "destinationId", destination)).status()).isEqualTo(403);
        var w = api.call("POST", "/v1/merchants/" + platform + "/withdrawals", owner, UUID.randomUUID().toString(),
                Map.of("amount", 40_000, "destinationId", destination));
        assertThat(w.status()).isEqualTo(200);
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);
        assertThat(available()).isEqualTo(60_000);
        // Overdraw is refused before anything is sent.
        assertThat(api.call("POST", "/v1/merchants/" + platform + "/withdrawals", owner, UUID.randomUUID().toString(),
                Map.of("amount", 60_001, "destinationId", destination)).body().get("code").asString())
                .isEqualTo("insufficient_funds");
        assertThat(db.sql("SELECT count(*) FROM audit_log WHERE action = 'merchant.destination_added' AND subject = :s")
                .param("s", "merchant:" + platform).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void confirmedMomoTransferFromWalletPaysOutToTheVerifiedAccount() {
        String subject = "cust-" + UUID.randomUUID();
        UUID customer = api.enroll(ops, subject, TestApi.uniquePhone(), "Abena");
        ledger.post(Ledger.Posting.of("test-funding", subject, "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 5_000),
                Ledger.Leg.credit(Ledger.code("customer", customer, "available", "GHS"), 5_000))));
        String token = api.token(subject);
        JsonNode lookup = api.call("POST", "/v1/wallet/recipients/lookup", token, null,
                Map.of("rail", "momo", "phone", "0245550005", "network", "mtn")).body();
        assertThat(lookup.get("verifiedName").asString()).isEqualTo("SIMULATED ACCOUNT HOLDER 0005");
        var sent = api.call("POST", "/v1/wallet/transfers/confirmed", token, UUID.randomUUID().toString(),
                Map.of("confirmationId", lookup.get("confirmationId").asString(), "amount", 3_000));
        assertThat(sent.status()).isEqualTo(200);
        assertThat(sent.body().get("verifiedName").asString()).isEqualTo("SIMULATED ACCOUNT HOLDER 0005");
        assertThat(ledger.balance(Ledger.code("customer", customer, "reserved", "GHS"))).isEqualTo(3_000);
        api.call("POST", "/v1/ops/rails/recover", ops, null, null);
        assertThat(ledger.balance(Ledger.code("customer", customer, "reserved", "GHS"))).isZero();
        assertThat(ledger.balance(Ledger.code("customer", customer, "available", "GHS"))).isEqualTo(2_000);
    }
}
