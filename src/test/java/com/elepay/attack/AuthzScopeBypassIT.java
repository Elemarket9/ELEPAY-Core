package com.elepay.attack;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Attack class A — authorization / key-scope bypass. Every test drives the real HTTP filter chain
 * (ApiKeyFilter + controllers) and asserts the attack is repelled with the fail-closed status.
 */
class AuthzScopeBypassIT extends AttackSupport {

    private static final String ALLOWED_IP = "203.0.113.10";

    private String secretKey() {
        return createKey(merchant, owner, Map.of("label", "sk", "keyType", "secret",
                "scopes", List.of("view", "collect", "refund", "withdraw", "payouts")));
    }

    private String publishableKey() {
        return createKey(merchant, owner, Map.of("label", "pk", "keyType", "publishable"));
    }

    private String payoutKey() {
        return createKey(merchant, owner, Map.of("label", "payout", "keyType", "payout",
                "allowedIps", List.of(ALLOWED_IP + "/32")));
    }

    @Test
    @DisplayName("A1: sk_live secret key is refused on every payout route (withdrawals, payout-batches, retry) -> 403 payout_key_required")
    void secretKeyCannotHitPayoutRoutes() {
        String sk = secretKey();
        UUID dest = addReadyDestination(merchant, owner, "0245550005");

        var withdraw = callWithKeyAndIp("POST", "/v1/merchants/" + merchant + "/withdrawals", sk, null,
                UUID.randomUUID().toString(), Map.of("amount", 5000, "destinationId", dest));
        assertThat(withdraw.status()).isEqualTo(403);
        assertThat(withdraw.body().get("code").asString()).isEqualTo("payout_key_required");

        var batch = callWithKeyAndIp("POST", "/v1/merchants/" + merchant + "/payout-batches", sk, null,
                UUID.randomUUID().toString(), Map.of("items", List.of(Map.of(
                        "reference", "atk-1", "amount", 5000, "type", "momo", "network", "mtn",
                        "accountNumber", "0245550005", "accountName", "Verified Recipient"))));
        assertThat(batch.status()).isEqualTo(403);
        assertThat(batch.body().get("code").asString()).isEqualTo("payout_key_required");

        // Retry route is guarded at the filter for sk_live even though the referenced payout does not exist.
        var retry = callWithKeyAndIp("POST", "/v1/merchants/" + merchant + "/payouts/PO-DOES-NOT-EXIST/retry", sk,
                null, UUID.randomUUID().toString(), Map.of());
        assertThat(retry.status()).isEqualTo(403);
        assertThat(retry.body().get("code").asString()).isEqualTo("payout_key_required");
    }

    @Test
    @DisplayName("A2: pk_live publishable key is refused on any non-public route -> 403 publishable_key_restricted")
    void publishableKeyCannotHitNonPublicRoutes() {
        String pk = publishableKey();

        var balances = callWithKeyAndIp("GET", "/v1/merchants/" + merchant + "/balances", pk, null, null, null);
        assertThat(balances.status()).isEqualTo(403);
        assertThat(balances.body().get("code").asString()).isEqualTo("publishable_key_restricted");

        var keys = callWithKeyAndIp("GET", "/v1/merchants/" + merchant + "/api-keys", pk, null, null, null);
        assertThat(keys.status()).isEqualTo(403);
        assertThat(keys.body().get("code").asString()).isEqualTo("publishable_key_restricted");
    }

    @Test
    @DisplayName("A3: no credential on an authenticated route -> 401")
    void noCredentialIsUnauthorized() {
        var res = api.call("GET", "/v1/merchants/" + merchant + "/balances", null, null, null);
        assertThat(res.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("A4: an API key on an ops or admin route -> 403 (API keys carry no roles)")
    void apiKeyCannotReachOpsOrAdminRoutes() {
        String sk = secretKey();

        var ops = callWithKeyAndIp("GET", "/v1/ops/kill-switches", sk, null, null, null);
        assertThat(ops.status()).isEqualTo(403);

        var admin = callWithKeyAndIp("GET", "/admin/kill-switch/status", sk, null, null, null);
        assertThat(admin.status()).isEqualTo(403);

        // A payout key (full server scopes) likewise carries no ops role.
        var payoutOnOps = callWithKeyAndIp("GET", "/v1/ops/maker-checker/proposals", payoutKey(), ALLOWED_IP, null, null);
        assertThat(payoutOnOps.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("A5: IDOR — merchant A's credential operating on merchant B's resources -> 404 (no membership leak)")
    void crossMerchantIdorIsRefused() {
        String skA = secretKey(); // scoped to merchant A
        UUID merchantB = createActiveMerchant(api.token("attack-ownerB-" + UUID.randomUUID()),
                "Attack Merchant B " + UUID.randomUUID().toString().substring(0, 6));

        var readB = callWithKeyAndIp("GET", "/v1/merchants/" + merchantB + "/balances", skA, null, null, null);
        assertThat(readB.status()).isEqualTo(404);

        // Payout key of A cannot pay out from B either (refused before any money moves).
        var payB = callWithKeyAndIp("POST", "/v1/merchants/" + merchantB + "/payout-batches", payoutKey(), ALLOWED_IP,
                UUID.randomUUID().toString(), Map.of("items", List.of(Map.of(
                        "reference", "idor-1", "amount", 5000, "type", "momo", "network", "mtn",
                        "accountNumber", "0245550005", "accountName", "Verified Recipient"))));
        assertThat(payB.status()).isIn(403, 404);
    }
}
