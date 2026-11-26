package com.elepay.attack;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Attack class D — perimeter and control bypass: X-Forwarded-For spoofing, kill-switch path
 * evasion, maker-checker self-approval and suspended-merchant payouts. Each asserts the control
 * holds (fail-closed).
 */
class PerimeterControlBypassIT extends AttackSupport {

    private static final String ALLOWED_IP = "203.0.113.10";
    private static final String ATTACKER_IP = "198.51.100.99";

    private String payoutKey() {
        return createKey(merchant, owner, Map.of("label", "payout", "keyType", "payout",
                "allowedIps", List.of(ALLOWED_IP + "/32")));
    }

    /** Sends a request with the exact raw path (no client-side normalization) and returns status + body. */
    private int[] rawStatus(String method, String rawPath) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath));
            builder = "POST".equals(method)
                    ? builder.POST(HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type", "application/json")
                    : builder.GET();
            HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new int[]{res.statusCode(), res.body().contains("FEATURE_NOT_ENABLED") ? 1 : 0};
        }
    }

    @Test
    @DisplayName("D1: XFF spoof — a payout_live key whose real peer is not allowlisted cannot pass the IP gate by prepending an allowlisted address to X-Forwarded-For")
    void leftmostXffSpoofIsRejected() {
        String key = payoutKey();
        fundMerchantAvailable(merchant, 100_000L);
        UUID dest = addReadyDestination(merchant, owner, "0245550005");

        // The edge appends the real client hop on the RIGHT; an attacker only controls the LEFTMOST token.
        // Here the real (rightmost) hop is the attacker IP; the allowlisted IP is the spoofed leftmost token.
        var spoof = callWithKeyAndIp("POST", "/v1/merchants/" + merchant + "/withdrawals", key,
                ALLOWED_IP + ", " + ATTACKER_IP, UUID.randomUUID().toString(),
                Map.of("amount", 5000, "destinationId", dest));
        assertThat(spoof.status()).isEqualTo(403);
        assertThat(spoof.body().get("code").asString()).isEqualTo("ip_not_allowlisted");
    }

    @Test
    @DisplayName("D2: kill-switch path evasion — trailing slash, mixed case, extra slashes and percent-encoding cannot reach a frozen module")
    void killSwitchPathVariationsStayBlocked() throws Exception {
        killSwitchService.setModuleEnabled("consumer-wallet", false, "Stage 1 OFF", "attack-test");

        // Canonical path is blocked server-side with 404 FEATURE_NOT_ENABLED.
        int[] base = rawStatus("POST", "/v1/wallet/transfers");
        assertThat(base[0]).isEqualTo(404);
        assertThat(base[1]).as("canonical path must be FEATURE_NOT_ENABLED").isEqualTo(1);

        // Trailing slash still resolves to the frozen module prefix -> 404 FEATURE_NOT_ENABLED.
        int[] trailing = rawStatus("POST", "/v1/wallet/transfers/");
        assertThat(trailing[0]).isEqualTo(404);
        assertThat(trailing[1]).isEqualTo(1);

        // Mixed case does not match the live controller mapping and never reaches the module.
        assertThat(rawStatus("POST", "/v1/WALLET/transfers")[0]).isGreaterThanOrEqualTo(400);

        // Extra slashes and dot/slash percent-encoding are rejected (non-normalized / encoded path) -> never 2xx.
        assertThat(rawStatus("POST", "/v1//wallet/transfers")[0]).isGreaterThanOrEqualTo(400);
        assertThat(rawStatus("POST", "/v1/wallet/%2e%2e/wallet/transfers")[0]).isGreaterThanOrEqualTo(400);
        assertThat(rawStatus("POST", "/v1/wa%6clet/transfers")[0]).isGreaterThanOrEqualTo(400);
    }

    @Test
    @DisplayName("D3: maker-checker self-approval — the same operator proposing and approving a dual-control action is rejected 403")
    void makerCheckerSelfApprovalRejected() {
        var proposal = api.call("POST", "/v1/ops/maker-checker/proposals", ops1, null, Map.of(
                "actionType", "LARGE_PAYOUT",
                "resourceType", "payout_batch",
                "resourceId", "PB-ATTACK-001",
                "merchantId", merchant.toString(),
                "payload", Map.of("amountPesewas", 50_000_000L),
                "reason", "attack self-approval attempt"));
        assertThat(proposal.status()).as(proposal.body().toString()).isEqualTo(200);
        UUID id = UUID.fromString(proposal.body().get("id").asString());

        var selfApprove = api.call("POST", "/v1/ops/maker-checker/proposals/" + id + "/approve", ops1, null,
                Map.of("note", "approving my own proposal"));
        assertThat(selfApprove.status()).isEqualTo(403);
        assertThat(selfApprove.body().get("code").asString()).isEqualTo("maker_checker_self_approval");

        // A distinct second operator can approve — proving the block is on self-approval, not approval itself.
        var checkerApprove = api.call("POST", "/v1/ops/maker-checker/proposals/" + id + "/approve", ops2, null,
                Map.of("note", "approved by second operator"));
        assertThat(checkerApprove.status()).isEqualTo(200);
        assertThat(checkerApprove.body().get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("D4: suspended-merchant payout — a merchant with status != active cannot pay out via /payout-batches -> 409 merchant_not_active")
    void suspendedMerchantCannotPayout() {
        db.sql("UPDATE merchant SET status = 'suspended' WHERE id = :id").param("id", merchant).update();

        var res = api.call("POST", "/v1/merchants/" + merchant + "/payout-batches", owner,
                UUID.randomUUID().toString(), Map.of("items", List.of(Map.of(
                        "reference", "susp-1", "amount", 5000, "type", "momo", "network", "mtn",
                        "accountNumber", "0245550005", "accountName", "Verified Recipient"))));
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.body().get("code").asString()).isEqualTo("merchant_not_active");
    }
}
