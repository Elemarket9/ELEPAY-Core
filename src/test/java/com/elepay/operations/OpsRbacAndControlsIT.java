package com.elepay.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.identity.ApiKeys;
import com.elepay.identity.KillSwitchService;
import com.elepay.merchanttools.pos.PosCashierService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

@DisplayName("Ops Granular RBAC, Search Escaping, Audit, and POS Cashier Auth Integration Tests")
class OpsRbacAndControlsIT extends PostgresTestBase {

    @LocalServerPort int port;
    @Autowired JdbcClient db;
    @Autowired ApiKeys apiKeys;
    @Autowired KillSwitchService killSwitchService;
    @Autowired PosCashierService cashierService;

    TestApi api;
    String supportToken;
    String riskToken;
    String financeToken;
    String onboardingToken;
    String opsAdminToken;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        supportToken = api.token("support-" + s, "support_agent");
        riskToken = api.token("risk-" + s, "risk_compliance");
        financeToken = api.token("finance-" + s, "finance_ops");
        onboardingToken = api.token("onboard-" + s, "onboarding_officer");
        opsAdminToken = api.token("admin-" + s, "ops_admin");
    }

    private UUID createMerchant(String tradingName, String createdBy) {
        UUID id = UUID.randomUUID();
        String code = String.format("%06d", Math.abs(id.hashCode()) % 900000 + 100000);
        db.sql("""
                INSERT INTO merchant (id, legal_name, trading_name, kind, status, contact_phone, merchant_code, created_by)
                VALUES (:id, :name, :name, 'merchant', 'pending_review', :phone, :code, :createdBy)""")
                .param("id", id)
                .param("name", tradingName)
                .param("phone", TestApi.uniquePhone())
                .param("code", code)
                .param("createdBy", createdBy)
                .update();
        return id;
    }

    @Test
    @DisplayName("support_agent receives 403 on risk, finance, and onboarding endpoints; authorized roles receive 200")
    void granularOpsRbacEnforcedOnBackend() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        UUID merchantId = createMerchant("RBAC Merchant " + s, "merchant-owner-" + s);
        ApiKeys.Created createdKey = apiKeys.create(merchantId, "rbac-key-" + s, List.of("view"), "secret");

        // 1. support_agent -> 403 Forbidden on all 4 restricted endpoints
        assertThat(api.call("POST", "/v1/ops/kill-switch/global", supportToken, null,
                Map.of("frozen", true, "reason", "test")).status()).isEqualTo(403);
        assertThat(api.call("POST", "/v1/ops/kill-switch/api-keys/" + createdKey.id(), supportToken, null, Map.of())
                .status()).isEqualTo(403);
        assertThat(api.call("POST", "/v1/ops/settlements/run", supportToken, null, Map.of())
                .status()).isEqualTo(403);
        assertThat(api.call("POST", "/v1/ops/merchants/" + merchantId + "/review", supportToken, null,
                Map.of("decision", "approve", "note", "test")).status()).isEqualTo(403);

        // 2. risk_compliance -> 200 on global freeze and emergency key revoke (+ audit log), 404 on unknown key
        boolean wasGlobalFrozen = killSwitchService.isGlobalFrozen();
        try {
            var freezeRes = api.call("POST", "/v1/ops/kill-switch/global", riskToken, null,
                    Map.of("frozen", true, "reason", "rbac test freeze"));
            assertThat(freezeRes.status()).isEqualTo(200);
            assertThat(freezeRes.body().get("globalFrozen").asBoolean()).isTrue();
        } finally {
            killSwitchService.setGlobalFrozen(wasGlobalFrozen, "restore after rbac test", "test-cleanup");
        }

        var revokeRes = api.call("POST", "/v1/ops/kill-switch/api-keys/" + createdKey.id(), riskToken, null, Map.of());
        assertThat(revokeRes.status()).isEqualTo(200);
        assertThat(revokeRes.body().get("revoked").asBoolean()).isTrue();
        assertThat(revokeRes.body().get("status").asString()).isEqualTo("REVOKED");

        long auditCount = db.sql("""
                SELECT count(*) FROM audit_log
                WHERE action = 'api_key.emergency_revoked' AND subject = :subj""")
                .param("subj", "api_key:" + createdKey.id())
                .query(Long.class).single();
        assertThat(auditCount).isEqualTo(1L);

        var controlsRes = api.call("GET", "/v1/ops/merchants/" + merchantId + "/controls", supportToken, null, null);
        assertThat(controlsRes.status()).isEqualTo(200);
        assertThat(controlsRes.body().get("apiKeys").size()).isEqualTo(1);
        assertThat(controlsRes.body().get("apiKeys").get(0).get("status").asString()).isEqualTo("revoked");

        var notFoundRevoke = api.call("POST", "/v1/ops/kill-switch/api-keys/" + UUID.randomUUID(),
                opsAdminToken, null, Map.of());
        assertThat(notFoundRevoke.status()).isEqualTo(404);

        // 3. finance_ops -> 200 on settlements/run
        var settleRes = api.call("POST", "/v1/ops/settlements/run", financeToken, null, Map.of());
        assertThat(settleRes.status()).isEqualTo(200);

        // 4. onboarding_officer -> 200 on merchants/{id}/review
        var reviewRes = api.call("POST", "/v1/ops/merchants/" + merchantId + "/review", onboardingToken, null,
                Map.of("decision", "approve", "note", "approved by onboarding"));
        assertThat(reviewRes.status()).isEqualTo(200);
        assertThat(reviewRes.body().get("status").asString()).isEqualTo("active");
    }

    @Test
    @DisplayName("searchMerchants escapes LIKE wildcards and audit rejects blank subject")
    void searchEscapesWildcardsAndAuditRejectsBlankSubject() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        createMerchant("LiteralPlain " + s, "owner-" + s);

        var underscoreRes = api.call("GET", "/v1/ops/merchants/search?q=_", supportToken, null, null);
        assertThat(underscoreRes.status()).isEqualTo(200);
        for (var row : underscoreRes.body()) {
            String combined = (row.path("trading_name").asString("") + " "
                    + row.path("legal_name").asString("") + " "
                    + row.path("merchant_code").asString("") + " "
                    + row.path("contact_phone").asString("")).toLowerCase();
            assertThat(combined).contains("_");
        }

        var percentRes = api.call("GET", "/v1/ops/merchants/search?q=%25", supportToken, null, null);
        assertThat(percentRes.status()).isEqualTo(200);
        for (var row : percentRes.body()) {
            String combined = (row.path("trading_name").asString("") + " "
                    + row.path("legal_name").asString("") + " "
                    + row.path("merchant_code").asString("") + " "
                    + row.path("contact_phone").asString("")).toLowerCase();
            assertThat(combined).contains("%");
        }

        var supportAuditRes = api.call("GET", "/v1/ops/audit?subject=merchant:1", supportToken, null, null);
        assertThat(supportAuditRes.status()).isEqualTo(403);

        String riskToken = api.token("risk-audit-" + UUID.randomUUID().toString().substring(0, 6), "risk_compliance");
        var blankAuditRes = api.call("GET", "/v1/ops/audit?subject=   ", riskToken, null, null);
        assertThat(blankAuditRes.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("POST /v1/pos/cashiers/auth returns cashier JSON without pinHash on valid PIN and 401 on invalid PIN")
    void posCashierAuthReturnsCashierWithoutSecret() {
        boolean wasPosEnabled = killSwitchService.isModuleEnabled("pos");
        killSwitchService.setModuleEnabled("pos", true, "ensure pos enabled for cashier auth test", "test-setup");
        try {
            String s = UUID.randomUUID().toString().substring(0, 8);
            String ownerSub = "pos-owner-" + s;
            String ownerToken = api.token(ownerSub);
            UUID merchantId = createMerchant("POS Auth Shop " + s, ownerSub);
            db.sql("UPDATE merchant SET status = 'active' WHERE id = :id").param("id", merchantId).update();
            db.sql("INSERT INTO merchant_member (merchant_id, subject, role) VALUES (:m, :s, 'owner')")
                    .param("m", merchantId).param("s", ownerSub).update();

            String badge = "B-" + s;
            var registered = cashierService.registerCashier(merchantId, "Ama Cashier", "2468", badge);

            var okRes = api.call("POST", "/v1/pos/cashiers/auth", ownerToken, null,
                    Map.of("merchantId", merchantId, "badgeNumber", badge, "pin", "2468"));
            assertThat(okRes.status()).isEqualTo(200);
            assertThat(okRes.body().get("id").asString()).isEqualTo(registered.id().toString());
            assertThat(okRes.body().get("badgeNumber").asString()).isEqualTo(badge);
            assertThat(okRes.body().get("name").asString()).isEqualTo("Ama Cashier");
            assertThat(okRes.body().has("pinHash")).isFalse();

            var badRes = api.call("POST", "/v1/pos/cashiers/auth", ownerToken, null,
                    Map.of("merchantId", merchantId, "badgeNumber", badge, "pin", "0000"));
            assertThat(badRes.status()).isEqualTo(401);
        } finally {
            killSwitchService.setModuleEnabled("pos", wasPosEnabled, "restore after cashier auth test", "test-cleanup");
        }
    }
}
