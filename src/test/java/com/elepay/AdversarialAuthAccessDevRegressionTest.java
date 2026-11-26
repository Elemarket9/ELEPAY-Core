package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.ApiException;
import com.elepay.developer.DeveloperController;
import com.elepay.developer.DeveloperService;
import com.elepay.identity.ApiKeyAuthentication;
import com.elepay.identity.ApiKeys;
import com.elepay.identity.KillSwitchController;
import com.elepay.identity.KillSwitchService;
import com.elepay.payout.MerchantPayouts;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

class AdversarialAuthAccessDevRegressionTest extends PostgresTestBase {

    @LocalServerPort int port;
    @Autowired JdbcClient db;
    @Autowired ObjectMapper json;
    @Autowired DeveloperController developerController;
    @Autowired KillSwitchService killSwitchService;
    @Autowired ApiKeys apiKeys;
    @Autowired MerchantPayouts merchantPayouts;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateUser(String subject, String... roles) {
        Jwt jwt = Jwt.withTokenValue("test-token-" + subject)
                .header("alg", "none")
                .subject(subject)
                .build();
        List<GrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }

    private static void authenticateApiKey(UUID merchantId, Set<String> scopes) {
        SecurityContextHolder.getContext().setAuthentication(
                new ApiKeyAuthentication(UUID.randomUUID(), merchantId, scopes));
    }

    private UUID createMerchant(String ownerSubject) {
        UUID merchantId = UUID.randomUUID();
        String suffix = merchantId.toString().substring(0, 8);
        String code = String.format("%06d", Math.abs(merchantId.hashCode()) % 900000 + 100000);
        db.sql("""
                INSERT INTO merchant (id, legal_name, trading_name, kind, status, contact_phone, contact_email, merchant_code, created_by)
                VALUES (:id, :name, :name, 'merchant', 'active', '0241234567', :email, :code, :owner)""")
                .param("id", merchantId)
                .param("name", "AdvDev Merchant " + suffix)
                .param("email", "merchant-" + suffix + "@example.com")
                .param("code", code)
                .param("owner", ownerSubject)
                .update();
        db.sql("INSERT INTO merchant_member (merchant_id, subject, role) VALUES (:m, :sub, 'owner')")
                .param("m", merchantId)
                .param("sub", ownerSubject)
                .update();
        return merchantId;
    }

    @Test
    @DisplayName("Issue 1: Active pingWebhook succeeds with NULL event_id, supports replayDelivery, and inactive webhook still returns 409 webhook_inactive")
    void pingWebhookSucceedsForActiveEndpointAndRejectsInactive() {
        String owner = "dev-owner-" + UUID.randomUUID().toString().substring(0, 8);
        UUID merchantId = createMerchant(owner);
        authenticateUser(owner, "merchant");

        DeveloperService.WebhookEndpointCreated created = developerController.registerWebhook(
                merchantId,
                new DeveloperController.WebhookRegisterRequest(
                        "http://127.0.0.1:9/webhooks/ping",
                        "test",
                        "Ping regression test",
                        List.of("checkout.paid")));

        // Active webhook ping must succeed without FK 409 state_conflict
        DeveloperService.ReplayResult pingResult = developerController.pingWebhook(merchantId, created.id());
        assertThat(pingResult).isNotNull();
        assertThat(pingResult.newLogId()).isNotNull();

        DeveloperService.WebhookDeliveryLogView logEntry =
                developerController.getDeliveryLog(merchantId, pingResult.newLogId());
        assertThat(logEntry.eventId()).isNull();
        assertThat(logEntry.eventType()).isEqualTo("ping");
        assertThat(logEntry.requestHeaders()).contains("Elepay-Event-Id").contains("Elepay-Signature");
        assertThat(logEntry.isManualRetry()).isFalse();

        // Replaying a synthetic ping delivery log must also succeed cleanly
        DeveloperService.ReplayResult replayResult =
                developerController.replayDelivery(merchantId, pingResult.newLogId());
        assertThat(replayResult).isNotNull();
        assertThat(replayResult.newLogId()).isNotNull();
        assertThat(replayResult.newLogId()).isNotEqualTo(pingResult.newLogId());

        DeveloperService.WebhookDeliveryLogView replayEntry =
                developerController.getDeliveryLog(merchantId, replayResult.newLogId());
        assertThat(replayEntry.eventId()).isNull();
        assertThat(replayEntry.isManualRetry()).isTrue();

        // Deactivated webhook ping must still return 409 webhook_inactive
        developerController.updateWebhook(
                merchantId,
                created.id(),
                new DeveloperController.WebhookUpdateRequest(null, null, false, null));

        assertThatThrownBy(() -> developerController.pingWebhook(merchantId, created.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.code()).isEqualTo("webhook_inactive");
                });
    }

    @Test
    @DisplayName("Issue 2: DeveloperController enforces explicit requireUser() on all 7 previously missing endpoints")
    void developerControllerEnforcesRequireUserOnAllRemainingEndpoints() {
        String owner = "dev-req-user-" + UUID.randomUUID().toString().substring(0, 8);
        UUID merchantId = createMerchant(owner);

        // Authenticate as an API key (not a signed-in user)
        authenticateApiKey(merchantId, Set.of("view", "collect", "refund", "withdraw", "payouts"));

        List<Runnable> endpoints = List.of(
                () -> developerController.listKeys(merchantId, null),
                () -> developerController.listWebhooks(merchantId, null),
                () -> developerController.listDeliveryLogs(merchantId, null, null, 20),
                () -> developerController.getDeliveryLog(merchantId, UUID.randomUUID()),
                () -> developerController.replayDelivery(merchantId, UUID.randomUUID()),
                () -> developerController.listCertifications(merchantId),
                () -> developerController.runCertificationTests(merchantId, UUID.randomUUID())
        );

        for (Runnable call : endpoints) {
            assertThatThrownBy(call::run)
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.status()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(apiEx.getMessage()).isEqualTo("This operation requires a signed-in user");
                    });
        }
    }

    @Test
    @DisplayName("Issue 3: KillSwitchController.toggleModule rejects same-actor re-enable with 403 regardless of dualControl flag")
    void killSwitchToggleModuleBlocksSelfApprovalAndAllowsDistinctOpsAdmin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String opsA = "ops-mod-a-" + suffix;
        String opsB = "ops-mod-b-" + suffix;

        KillSwitchController dualOffController = new KillSwitchController(killSwitchService, apiKeys, db, json, false);
        KillSwitchController dualOnController = new KillSwitchController(killSwitchService, apiKeys, db, json, true);
        boolean wasPosEnabled = killSwitchService.isModuleEnabled("pos");

        try {
            // opsA disables pos
            authenticateUser(opsA, "ops", "ops_admin");
            Map<String, Object> disabled = dualOffController.toggleModule(
                    "pos", new KillSwitchController.ModuleKillSwitchRequest(false, null, "incident"));
            assertThat(disabled.get("enabled")).isEqualTo(false);
            assertThat(killSwitchService.isModuleEnabled("pos")).isFalse();
            assertThat(killSwitchService.moduleDisabledBy("pos")).isEqualTo(opsA);

            // Same actor opsA cannot re-enable when dualControl=false
            assertThatThrownBy(() -> dualOffController.toggleModule(
                    "pos", new KillSwitchController.ModuleKillSwitchRequest(true, null, "self restore")))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.status()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(apiEx.code()).isEqualTo("maker_checker_self_approval");
                    });
            assertThat(killSwitchService.isModuleEnabled("pos")).isFalse();

            // Same actor opsA also gets 403 when dualControl=true
            assertThatThrownBy(() -> dualOnController.toggleModule(
                    "pos", new KillSwitchController.ModuleKillSwitchRequest(true, null, "self restore")))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.status()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(apiEx.code()).isEqualTo("maker_checker_self_approval");
                    });
            assertThat(killSwitchService.isModuleEnabled("pos")).isFalse();

            // Distinct ops_admin opsB can re-enable when dualControl=false
            authenticateUser(opsB, "ops", "ops_admin");
            Map<String, Object> enabled = dualOffController.toggleModule(
                    "pos", new KillSwitchController.ModuleKillSwitchRequest(true, null, "verified recovery"));
            assertThat(enabled.get("enabled")).isEqualTo(true);
            assertThat(killSwitchService.isModuleEnabled("pos")).isTrue();
            assertThat(killSwitchService.moduleDisabledBy("pos")).isNull();
        } finally {
            killSwitchService.setModuleEnabled("pos", wasPosEnabled, "test cleanup", "test-cleanup");
        }
    }

    @Test
    @DisplayName("Issue 4: MerchantPayouts.retry returns uniform 403 to intra-tenant VIEW-only roles and 404 to cross-tenant callers")
    void merchantPayoutsRetryClosesIntraTenantExistenceOracle() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String owner = "payout-owner-" + suffix;
        String cashier = "payout-cashier-" + suffix;
        String waiter = "payout-waiter-" + suffix;
        String outsider = "payout-outsider-" + suffix;

        UUID merchantId = createMerchant(owner);
        db.sql("INSERT INTO merchant_member (merchant_id, subject, role) VALUES (:m, :sub, 'cashier')")
                .param("m", merchantId).param("sub", cashier).update();
        db.sql("INSERT INTO merchant_member (merchant_id, subject, role) VALUES (:m, :sub, 'waiter')")
                .param("m", merchantId).param("sub", waiter).update();

        String existingRef = "PO-ORACLE-" + suffix;
        String missingRef = "PO-MISSING-" + suffix;
        db.sql("""
                INSERT INTO payout (id, reference, owner_type, owner_id, destination, amount, rail, status, requested_by, client_reference)
                VALUES (:id, :ref, 'merchant', :m,
                        '{"type":"momo","network":"mtn","accountNumber":"233241234567","accountName":"Test"}'::jsonb,
                        5000, 'mtn', 'rejected', :owner, :cref)""")
                .param("id", UUID.randomUUID())
                .param("ref", existingRef)
                .param("m", merchantId)
                .param("owner", owner)
                .param("cref", "batch-item-" + suffix)
                .update();

        // Intra-tenant cashier receives 403 for BOTH existing and nonexistent payout references
        authenticateUser(cashier, "merchant");
        assertThatThrownBy(() -> merchantPayouts.retry(merchantId, existingRef, "idem-c1-" + suffix))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> merchantPayouts.retry(merchantId, missingRef, "idem-c2-" + suffix))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.FORBIDDEN));

        // Intra-tenant waiter receives 403 for BOTH existing and nonexistent payout references
        authenticateUser(waiter, "merchant");
        assertThatThrownBy(() -> merchantPayouts.retry(merchantId, existingRef, "idem-w1-" + suffix))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> merchantPayouts.retry(merchantId, missingRef, "idem-w2-" + suffix))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.FORBIDDEN));

        // Cross-tenant outsider still receives 404 uniformly
        authenticateUser(outsider, "merchant");
        assertThatThrownBy(() -> merchantPayouts.retry(merchantId, existingRef, "idem-o1-" + suffix))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> merchantPayouts.retry(merchantId, missingRef, "idem-o2-" + suffix))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("Issue 5: Merchants.resendOob enforces max 5 resends while preserving 30s cooldown and 5-attempt lock")
    void merchantsResendOobCapsResendsAtFive() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String owner = "oob-owner-" + suffix;
        UUID merchantId = createMerchant(owner);
        UUID destId = UUID.randomUUID();

        db.sql("""
                INSERT INTO settlement_destination
                    (id, owner_type, owner_id, type, network, account_number, account_name,
                     status, created_by, verification, oob_verification_status, oob_attempts, oob_resend_count, updated_at)
                VALUES (:id, 'merchant', :m, 'momo', 'mtn', '233241234567', 'OOB Test Owner',
                        'active', :owner, 'verified', 'pending_oob', 0, 0, now())""")
                .param("id", destId)
                .param("m", merchantId)
                .param("owner", owner)
                .update();

        TestApi api = new TestApi(port);
        String ownerToken = api.token(owner, "merchant");
        String path = "/v1/merchants/" + merchantId + "/settlement-destinations/" + destId + "/resend-oob";

        // 1. Immediate resend within 30s cooldown returns 429
        var tooEarly = api.call("POST", path, ownerToken, null, Map.of());
        assertThat(tooEarly.status()).isEqualTo(429);

        // 2. Five resends after cooldown succeed and increment oob_resend_count to 5
        for (int i = 1; i <= 5; i++) {
            db.sql("UPDATE settlement_destination SET updated_at = now() - interval '60 seconds' WHERE id = :id")
                    .param("id", destId).update();
            var res = api.call("POST", path, ownerToken, null, Map.of());
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.body().get("resent").asBoolean()).isTrue();
            Integer count = db.sql("SELECT oob_resend_count FROM settlement_destination WHERE id = :id")
                    .param("id", destId).query(Integer.class).single();
            assertThat(count).isEqualTo(i);
        }

        // 3. Sixth resend is locked (403) even immediately (before cooldown check)
        var lockedImmediate = api.call("POST", path, ownerToken, null, Map.of());
        assertThat(lockedImmediate.status()).isEqualTo(403);
        assertThat(lockedImmediate.body().get("detail").asString()).contains("locked");

        // 4. Sixth resend remains locked (403) even after cooldown expires
        db.sql("UPDATE settlement_destination SET updated_at = now() - interval '60 seconds' WHERE id = :id")
                .param("id", destId).update();
        var lockedAfterCooldown = api.call("POST", path, ownerToken, null, Map.of());
        assertThat(lockedAfterCooldown.status()).isEqualTo(403);
        assertThat(lockedAfterCooldown.body().get("detail").asString()).contains("locked");
    }
}
