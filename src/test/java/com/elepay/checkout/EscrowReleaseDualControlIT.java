package com.elepay.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.identity.KillSwitchService;
import com.elepay.ledger.Ledger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

@TestPropertySource(properties = "elepay.security.dual-control.enforced=true")
class EscrowReleaseDualControlIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired KillSwitchService killSwitchService;
    @Autowired CheckoutService checkoutService;

    TestApi api;
    String makerSubject, checkerSubject;
    String opsMaker, opsChecker, mpOwner, sellerOwner;
    UUID marketplace, seller;

    @BeforeEach
    void setUp() {
        killSwitchService.resetForTesting();
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        makerSubject = "ops-maker-" + s;
        checkerSubject = "ops-checker-" + s;
        opsMaker = api.token(makerSubject, "ops", "ops_admin");
        opsChecker = api.token(checkerSubject, "ops", "ops_admin");
        mpOwner = api.token("mp-dc-" + s);
        sellerOwner = api.token("seller-dc-" + s);

        marketplace = createMerchant(mpOwner, "DC MP " + s, "marketplace", 0);
        seller = createMerchant(sellerOwner, "DC Seller " + s, "merchant", 0);

        String code = api.call("GET", "/v1/merchants/" + seller, sellerOwner, null, null)
                .body().get("merchantCode").asString();
        assertThat(api.call("POST", "/v1/merchants/" + marketplace + "/sellers", mpOwner, null,
                Map.of("merchantCode", code)).status()).isEqualTo(200);
        assertThat(api.call("POST", "/v1/merchants/" + seller + "/marketplaces/" + marketplace + "/accept",
                sellerOwner, null, null).status()).isEqualTo(200);
    }

    @AfterEach
    void tearDown() {
        killSwitchService.resetForTesting();
    }

    private UUID createMerchant(String owner, String name, String kind, int feeBps) {
        var created = api.call("POST", "/v1/merchants", owner, null, Map.of(
                "legalName", name + " Ltd", "tradingName", name, "kind", kind, "contactPhone", "0241234567"));
        assertThat(created.status()).isEqualTo(200);
        UUID id = UUID.fromString(created.body().get("id").asString());
        assertThat(api.call("POST", "/v1/ops/merchants/" + id + "/review", opsMaker, null,
                Map.of("decision", "approve", "feeBps", feeBps, "feeFixed", 0, "settlementDelayHours", 0))
                .body().get("status").asString()).isEqualTo("active");
        return id;
    }

    private String createPaidReserveCheckout(long amount, int reserveBps) {
        var created = api.call("POST", "/v1/merchants/" + marketplace + "/checkouts", mpOwner,
                UUID.randomUUID().toString(),
                Map.of("description", "DualControl Reserve Order", "allocations", List.of(
                        Map.of("merchantId", seller, "kind", "sale", "amount", amount,
                                "description", "Item", "reserveBps", reserveBps))));
        assertThat(created.status()).isEqualTo(200);
        String ref = created.body().get("reference").asString();
        var pay = api.call("POST", "/v1/public/checkouts/" + ref + "/pay", null,
                UUID.randomUUID().toString(),
                Map.of("method", "momo", "phone", "0245550005", "network", "mtn"));
        assertThat(pay.status()).as("public pay: %s", pay.body()).isEqualTo(200);
        if (!"paid".equals(pay.body().get("status").asString())) {
            api.call("POST", "/v1/ops/rails/recover", opsMaker, null, null);
        }
        var status = api.call("GET", "/v1/public/checkouts/" + ref + "/status", null, null, null);
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.body().get("status").asString()).isEqualTo("paid");
        return ref;
    }

    @Test
    void escrowReleaseRequiresDualControlApprovalEndToEnd() {
        String ref = createPaidReserveCheckout(5_000L, 2000); // 4,000 pending, 1,000 reserved
        assertThat(ledger.balance(Ledger.code("merchant", seller, "reserved", Ledger.GHS))).isEqualTo(1_000L);
        assertThat(ledger.balance(Ledger.code("merchant", seller, "pending", Ledger.GHS))).isEqualTo(4_000L);

        // 1. Maker calls release-reserve -> 202 pending_approval; reserve balance remains 1,000L
        var holdRes = api.call("POST",
                "/v1/merchants/" + marketplace + "/checkouts/" + ref + "/allocations/0/release-reserve",
                opsMaker, UUID.randomUUID().toString(),
                Map.of("amount", 1_000L, "reason", "Verified escrow release"));
        assertThat(holdRes.status()).as("release-reserve hold: %s", holdRes.body()).isEqualTo(202);
        assertThat(holdRes.body().get("code").asString()).isEqualTo("pending_approval");
        assertThat(ledger.balance(Ledger.code("merchant", seller, "reserved", Ledger.GHS))).isEqualTo(1_000L);

        // 2. Verify maker_checker_proposal row is PENDING
        UUID proposalId = db.sql("""
                SELECT id FROM maker_checker_proposal
                WHERE action_type = 'ESCROW_RELEASE'
                  AND resource_id = :rid
                  AND merchant_id = :mid
                  AND proposed_by = :by
                  AND status = 'PENDING'""")
                .param("rid", ref + ":0")
                .param("mid", marketplace)
                .param("by", makerSubject)
                .query(UUID.class)
                .single();
        assertThat(proposalId).isNotNull();

        // 3. Self-approval attempt -> 403 maker_checker_self_approval; balance unchanged
        var selfApprove = api.call("POST", "/v1/ops/maker-checker/proposals/" + proposalId + "/approve",
                opsMaker, null, Map.of("note", "Self approve attempt"));
        assertThat(selfApprove.status()).isEqualTo(403);
        assertThat(selfApprove.body().get("code").asString()).isEqualTo("maker_checker_self_approval");
        assertThat(ledger.balance(Ledger.code("merchant", seller, "reserved", Ledger.GHS))).isEqualTo(1_000L);

        // 4. Distinct checker approves -> 200 APPROVED
        var approveRes = api.call("POST", "/v1/ops/maker-checker/proposals/" + proposalId + "/approve",
                opsChecker, null, Map.of("note", "Checked delivery proof"));
        assertThat(approveRes.status()).as("checker approve: %s", approveRes.body()).isEqualTo(200);
        JsonNode approvedBody = approveRes.body();
        assertThat(approvedBody.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approvedBody.get("reviewedBy").asString()).isEqualTo(checkerSubject);

        // 5. Verify EscrowReleaseApprovalHandler -> CheckoutService.releaseReserve side effects
        assertThat(ledger.balance(Ledger.code("merchant", seller, "reserved", Ledger.GHS))).isEqualTo(0L);
        assertThat(ledger.balance(Ledger.code("merchant", seller, "pending", Ledger.GHS))).isEqualTo(5_000L);

        var allocRow = db.sql("""
                SELECT a.reserve_released, a.pending_amount, a.release_status
                FROM checkout_allocation a
                JOIN checkout c ON c.id = a.checkout_id
                WHERE c.reference = :ref AND a.line = 0""")
                .param("ref", ref)
                .query((rs, i) -> Map.of(
                        "reserveReleased", rs.getLong(1),
                        "pendingAmount", rs.getLong(2),
                        "releaseStatus", rs.getString(3)))
                .single();
        assertThat(allocRow.get("reserveReleased")).isEqualTo(1_000L);
        assertThat(allocRow.get("pendingAmount")).isEqualTo(5_000L);
        assertThat(allocRow.get("releaseStatus")).isEqualTo("RELEASED");

        var releaseRows = db.sql("""
                SELECT srr.amount, srr.released_by
                FROM settlement_reserve_release srr
                JOIN checkout c ON c.id = srr.checkout_id
                WHERE c.reference = :ref AND srr.line = 0""")
                .param("ref", ref)
                .query((rs, i) -> Map.of(
                        "amount", rs.getLong(1),
                        "releasedBy", rs.getString(2)))
                .list();
        assertThat(releaseRows).hasSize(1);
        assertThat(releaseRows.get(0).get("amount")).isEqualTo(1_000L);
        assertThat(releaseRows.get(0).get("releasedBy")).isEqualTo(checkerSubject);

        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void manualMalformedEscrowReleaseProposalReturns400() {
        var missingMerchant = api.call("POST", "/v1/ops/maker-checker/proposals", opsMaker, null,
                Map.of(
                        "actionType", "ESCROW_RELEASE",
                        "resourceType", "checkout_allocation",
                        "resourceId", "CHK-BAD:0",
                        "payload", Map.of("reference", "CHK-BAD", "line", 0, "amount", 1000L),
                        "reason", "Missing merchantId"
                ));
        assertThat(missingMerchant.status()).isEqualTo(400);
        assertThat(missingMerchant.body().get("code").asString()).isEqualTo("invalid_proposal_payload");

        var badAmount = api.call("POST", "/v1/ops/maker-checker/proposals", opsMaker, null,
                Map.of(
                        "actionType", "ESCROW_RELEASE",
                        "resourceType", "checkout_allocation",
                        "resourceId", "CHK-BAD:0",
                        "merchantId", marketplace,
                        "payload", Map.of("reference", "CHK-BAD", "line", 0, "amount", 12.5),
                        "reason", "Fractional amount"
                ));
        assertThat(badAmount.status()).isEqualTo(400);
        assertThat(badAmount.body().get("code").asString()).isEqualTo("invalid_proposal_payload");

        var missingFields = api.call("POST", "/v1/ops/maker-checker/proposals", opsMaker, null,
                Map.of(
                        "actionType", "ESCROW_RELEASE",
                        "resourceType", "checkout_allocation",
                        "resourceId", "CHK-BAD:0",
                        "merchantId", marketplace,
                        "payload", Map.of("reference", "CHK-BAD"),
                        "reason", "Missing line and amount"
                ));
        assertThat(missingFields.status()).isEqualTo(400);
        assertThat(missingFields.body().get("code").asString()).isEqualTo("invalid_proposal_payload");
    }
}
