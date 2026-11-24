package com.elepay.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.common.ApiException;
import com.elepay.common.OpsQueue;
import com.elepay.identity.Actor;
import com.elepay.ledger.Ledger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@DisplayName("Algorithm 052, 053, 054: Reconciliation, Fuzzy Matching & Suspense Clearing")
class ReconciliationServiceTest extends PostgresTestBase {

    @LocalServerPort int port;
    @Autowired JdbcClient db;
    @Autowired Ledger ledger;
    @Autowired OpsQueue ops;
    @Autowired ReconciliationService reconciliationService;

    TestApi api;
    String opsToken, makerToken, checkerToken;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        opsToken = api.token("ops-recon-" + s, "ops");
        makerToken = api.token("maker-recon-" + s, "ops");
        checkerToken = api.token("checker-recon-" + s, "ops_admin");
    }

    @Test
    @DisplayName("Algorithm 052: Fuzzy Statement Matching requires refMatch AND amountMatch AND timeMatch")
    void testFuzzyStatementMatchingBooleanLogic() {
        UUID txId = UUID.randomUUID();
        String s = UUID.randomUUID().toString().substring(0, 8);
        String internalRef = "MTN-" + s + "-FUZZ";

        // Transaction in DB: amount 1,000,000 pesewas (10,000 GHS)
        db.sql("""
                INSERT INTO rail_transaction (id, rail, direction, reference, purpose, purpose_id, amount, status, created_at)
                VALUES (:id, 'mtn', 'charge', :ref, 'settlement', :purposeId, 1000000, 'succeeded', now())""")
                .param("id", txId).param("ref", internalRef).param("purposeId", UUID.randomUUID()).update();

        // Statement line has matching prefix reference and time within 24h, BUT amount is 500 pesewas (5 GHS).
        // Under the old bug (refMatch && (amountMatch || timeMatch)), this falsely matched!
        // With the fix (refMatch && amountMatch && timeMatch), amount mismatch (> 200 pesewas difference) causes it to be UNMATCHED.
        var uploadRes = api.call("POST", "/v1/ops/reconciliation/statements", opsToken, null, Map.of(
                "rail", "mtn",
                "filename", "mtn_fuzzy_test_" + s + ".csv",
                "lines", List.of(
                        Map.of("externalReference", internalRef.substring(0, 12),
                                "amountPesewas", 500, // Drastically different amount!
                                "feePesewas", 5,
                                "rawDescription", "MOMO PAYMENT MISMATCH AMOUNT")
                )
        ));
        assertThat(uploadRes.status()).isEqualTo(200);
        assertThat(uploadRes.body().get("matchedExact").asInt()).isEqualTo(0);
        assertThat(uploadRes.body().get("matchedFuzzy").asInt()).isEqualTo(0); // MUST NOT fuzzy match!
        assertThat(uploadRes.body().get("unmatched").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Algorithm 052: Valid fuzzy match sets PROPOSED_MATCH and requires maker-checker approval")
    void testFuzzyMatchProposedStatusAndMakerCheckerApproval() {
        UUID txId = UUID.randomUUID();
        String s = UUID.randomUUID().toString().substring(0, 8);
        String internalRef = "TEL-" + s + "-TRUNC";

        db.sql("""
                INSERT INTO rail_transaction (id, rail, direction, reference, purpose, purpose_id, amount, status, created_at)
                VALUES (:id, 'telecel', 'charge', :ref, 'settlement', :purposeId, 50000, 'succeeded', now())""")
                .param("id", txId).param("ref", internalRef).param("purposeId", UUID.randomUUID()).update();

        // Statement line with truncated reference, matching amount within tolerance, and matching time
        var uploadRes = api.call("POST", "/v1/ops/reconciliation/statements", makerToken, null, Map.of(
                "rail", "telecel",
                "filename", "telecel_statement_" + s + ".csv",
                "lines", List.of(
                        Map.of("externalReference", internalRef.substring(0, 12),
                                "amountPesewas", 50050, // within 200 pesewas tolerance
                                "feePesewas", 375,       // 0.75% of 50,000 = 375 pesewas
                                "rawDescription", "TELECEL PAYMENT")
                )
        ));
        assertThat(uploadRes.status()).isEqualTo(200);
        assertThat(uploadRes.body().get("matchedFuzzy").asInt()).isEqualTo(1);

        UUID uploadId = UUID.fromString(uploadRes.body().get("uploadId").asString());

        // Verify statement line match_status is PROPOSED_MATCH (not MATCHED directly without review)
        var line = db.sql("SELECT match_status, id FROM statement_line WHERE upload_id = :uid")
                .param("uid", uploadId)
                .query((rs, i) -> Map.of("status", rs.getString(1), "id", rs.getObject(2, UUID.class)))
                .single();
        assertThat(line.get("status")).isEqualTo("PROPOSED_MATCH");

        // Verify reconciliation_match entry exists
        var match = db.sql("SELECT id FROM reconciliation_match WHERE statement_line_id = :lid")
                .param("lid", line.get("id"))
                .query((rs, i) -> rs.getObject(1, UUID.class))
                .single();

        // Maker cannot approve their own proposed fuzzy match
        var makerApprove = api.call("POST", "/v1/ops/reconciliation/matches/" + match + "/approve", makerToken, null, null);
        assertThat(makerApprove.status()).isEqualTo(403);

        // Distinct checker approves the fuzzy match
        var checkerApprove = api.call("POST", "/v1/ops/reconciliation/matches/" + match + "/approve", checkerToken, null, null);
        assertThat(checkerApprove.status()).isEqualTo(200);
        assertThat(checkerApprove.body().get("status").asString()).isEqualTo("MATCHED");

        // Verify line status updated to MATCHED in database
        String updatedStatus = db.sql("SELECT match_status FROM statement_line WHERE id = :id")
                .param("id", line.get("id")).query(String.class).single();
        assertThat(updatedStatus).isEqualTo("MATCHED");
    }

    @Test
    @DisplayName("Algorithm 053: Partner fee verification detects both overcharges and undercharges with banker's rounding")
    void testPartnerFeeVerificationOverchargeAndUndercharge() {
        String refOver = "MTN-OVER-" + UUID.randomUUID().toString().substring(0, 8);
        String refUnder = "MTN-UNDER-" + UUID.randomUUID().toString().substring(0, 8);

        // Insert matched rail transactions
        db.sql("""
                INSERT INTO rail_transaction (id, rail, direction, reference, purpose, purpose_id, amount, status)
                VALUES (:id1, 'mtn', 'charge', :r1, 'settlement', :p1, 200000, 'succeeded'),
                       (:id2, 'mtn', 'charge', :r2, 'settlement', :p2, 10000, 'succeeded')""")
                .param("id1", UUID.randomUUID()).param("r1", refOver).param("p1", UUID.randomUUID())
                .param("id2", UUID.randomUUID()).param("r2", refUnder).param("p2", UUID.randomUUID()).update();

        // Line 1: MTN overcharge (fee charged 1500 pesewas > 1000 cap)
        // Line 2: Telecel undercharge (fee charged 50 pesewas < expected 100 pesewas)
        var uploadRes = api.call("POST", "/v1/ops/reconciliation/statements", opsToken, null, Map.of(
                "rail", "mtn",
                "filename", "fee_audit_statement.csv",
                "lines", List.of(
                        Map.of("externalReference", refOver, "amountPesewas", 200000, "feePesewas", 1500),
                        Map.of("externalReference", refUnder, "amountPesewas", 10000, "feePesewas", 50)
                )
        ));
        assertThat(uploadRes.status()).isEqualTo(200);
        // Both overcharge and undercharge are detected as fee discrepancies
        assertThat(uploadRes.body().get("feeDiscrepancies").asInt()).isEqualTo(2);

        UUID uid = UUID.fromString(uploadRes.body().get("uploadId").asString());

        // Verify ops queue received items for both overcharge and undercharge
        UUID lineOverId = db.sql("SELECT id FROM statement_line WHERE upload_id = :uid AND external_reference = :ref")
                .param("uid", uid).param("ref", refOver).query(UUID.class).single();
        UUID lineUnderId = db.sql("SELECT id FROM statement_line WHERE upload_id = :uid AND external_reference = :ref")
                .param("uid", uid).param("ref", refUnder).query(UUID.class).single();

        long overCount = ops.openCount("partner_fee_overcharge", "reconciliation:" + lineOverId);
        long underCount = ops.openCount("partner_fee_undercharge", "reconciliation:" + lineUnderId);
        assertThat(overCount).isEqualTo(1L);
        assertThat(underCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("Algorithm 054: Suspense aging and clearing routes through currency-specific suspense accounts")
    void testSuspenseClearingCurrencyRouting() {
        UUID suspenseId = UUID.randomUUID();
        // Insert a USD suspense item
        db.sql("""
                INSERT INTO suspense_item (id, source, amount_pesewas, currency, reason, status, created_at)
                VALUES (:id, 'reconciliation:card', 5000, 'USD', 'Unmatched foreign currency deposit', 'OPEN', now())""")
                .param("id", suspenseId).update();

        // Maker proposes clearing to a destination account
        String targetAccount = ledger.openSystem("system:clearing:card:USD", "clearing", "USD", true);
        var proposeRes = api.call("POST", "/v1/ops/suspense/propose", makerToken, null, Map.of(
                "id", suspenseId.toString(),
                "action", "CLEAR_TO_CLEARING",
                "targetAccount", targetAccount
        ));
        assertThat(proposeRes.status()).isEqualTo(200);
        assertThat(proposeRes.body().get("status").asString()).isEqualTo("PROPOSED");

        // Checker approves clearing
        var approveRes = api.call("POST", "/v1/ops/suspense/" + suspenseId + "/approve", checkerToken, null, null);
        assertThat(approveRes.status()).as("approveRes body=%s", approveRes.body()).isEqualTo(200);
        assertThat(approveRes.body().get("status").asString()).isEqualTo("CLEARED");

        // Verify double-entry posting debited platform:suspense:usd (not platform:suspense:ghs or system:suspense:GHS)
        var debitLeg = db.sql("""
                SELECT la.code, je.amount FROM journal_entry je
                JOIN ledger_account la ON je.account_id = la.id
                WHERE je.transaction_id = (SELECT id FROM journal_transaction WHERE idempotency_key = :key)
                  AND je.amount < 0""")
                .param("key", suspenseId.toString())
                .query((rs, i) -> Map.of("code", rs.getString(1), "amount", rs.getLong(2)))
                .single();

        assertThat(debitLeg.get("code")).isEqualTo("platform:suspense:usd");
    }

    @Test
    @DisplayName("Regression: Bulk mode (>100 lines) checks currency, commercial records, raises fee ops_item alerts, and runs fuzzy matching")
    void testBulkReconciliationChecksCurrencyCommercialRecordsFeesAndFuzzy() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        String overchargeRef = "MTN-BULK-OVER-" + s;
        String usdMismatchRef = "MTN-BULK-USD-" + s;
        String fuzzyTargetRef = "MTN-BULK-FUZZ-" + s;

        db.sql("""
                INSERT INTO rail_transaction (id, rail, direction, reference, purpose, purpose_id, amount, currency, status, created_at)
                VALUES (:id1, 'mtn', 'charge', :r1, 'settlement', :p1, 200000, 'GHS', 'succeeded', now()),
                       (:id2, 'mtn', 'charge', :r2, 'settlement', :p2, 50000, 'GHS', 'succeeded', now()),
                       (:id3, 'mtn', 'charge', :r3, 'settlement', :p3, 10000, 'GHS', 'succeeded', now())""")
                .param("id1", UUID.randomUUID()).param("r1", overchargeRef).param("p1", UUID.randomUUID())
                .param("id2", UUID.randomUUID()).param("r2", usdMismatchRef).param("p2", UUID.randomUUID())
                .param("id3", UUID.randomUUID()).param("r3", fuzzyTargetRef).param("p3", UUID.randomUUID())
                .update();

        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        // 1. Exact GHS match with overcharged fee (1500 > 1000 cap)
        lines.add(Map.of("externalReference", overchargeRef, "amountPesewas", 200000, "currency", "GHS", "feePesewas", 1500));
        // 2. Currency mismatch (USD line vs GHS rail_transaction) -> must be UNMATCHED
        lines.add(Map.of("externalReference", usdMismatchRef, "amountPesewas", 50000, "currency", "USD", "feePesewas", 375));
        // 3. Truncated ref -> must fuzzy match
        lines.add(Map.of("externalReference", fuzzyTargetRef.substring(0, 15), "amountPesewas", 10000, "currency", "GHS", "feePesewas", 100));
        // Pad to 105 lines
        for (int i = 0; i < 102; i++) {
            lines.add(Map.of("externalReference", "UNMATCHED-" + s + "-" + i, "amountPesewas", 1000, "currency", "GHS", "feePesewas", 10));
        }

        var res = api.call("POST", "/v1/ops/reconciliation/statements", opsToken, null, Map.of(
                "rail", "mtn",
                "filename", "bulk_regression_" + s + ".csv",
                "lines", lines
        ));
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("matchedExact").asInt()).isEqualTo(1);
        assertThat(res.body().get("matchedFuzzy").asInt()).isEqualTo(1);
        assertThat(res.body().get("unmatched").asInt()).isEqualTo(103);
        assertThat(res.body().get("feeDiscrepancies").asInt()).isEqualTo(1);

        UUID uid = UUID.fromString(res.body().get("uploadId").asString());
        UUID overLineId = db.sql("SELECT id FROM statement_line WHERE upload_id = :uid AND external_reference = :ref")
                .param("uid", uid).param("ref", overchargeRef).query(UUID.class).single();
        assertThat(ops.openCount("partner_fee_overcharge", "reconciliation:" + overLineId)).isEqualTo(1L);

        String usdStatus = db.sql("SELECT match_status FROM statement_line WHERE upload_id = :uid AND external_reference = :ref")
                .param("uid", uid).param("ref", usdMismatchRef).query(String.class).single();
        assertThat(usdStatus).isEqualTo("UNMATCHED");
    }

    @Test
    @DisplayName("Regression: approveClearing requires ops_admin and blocks non-allowlisted merchant available accounts")
    void testApproveClearingRequiresOpsAdminAndAllowlist() {
        UUID suspenseId = UUID.randomUUID();
        db.sql("""
                INSERT INTO suspense_item (id, source, amount_pesewas, currency, reason, status, created_at)
                VALUES (:id, 'reconciliation:mtn', 25000, 'GHS', 'Test suspense', 'OPEN', now())""")
                .param("id", suspenseId).update();

        // Proposing a direct merchant available account must be rejected (403)
        var badPropose = api.call("POST", "/v1/ops/suspense/propose", makerToken, null, Map.of(
                "id", suspenseId.toString(),
                "action", "CLEAR_TO_MERCHANT",
                "targetAccount", "merchant:" + UUID.randomUUID() + ":available:GHS"
        ));
        assertThat(badPropose.status()).isEqualTo(403);

        // Proposing a valid system account succeeds
        String sysAcc = ledger.openSystem("system:clearing:mtn:GHS", "clearing", "GHS", true);
        var okPropose = api.call("POST", "/v1/ops/suspense/propose", makerToken, null, Map.of(
                "id", suspenseId.toString(),
                "action", "CLEAR_TO_SYSTEM",
                "targetAccount", sysAcc
        ));
        assertThat(okPropose.status()).isEqualTo(200);

        // Plain ops user cannot approveClearing (403)
        var opsApprove = api.call("POST", "/v1/ops/suspense/" + suspenseId + "/approve", opsToken, null, null);
        assertThat(opsApprove.status()).isEqualTo(403);

        // ops_admin user can approveClearing (200)
        var adminApprove = api.call("POST", "/v1/ops/suspense/" + suspenseId + "/approve", checkerToken, null, null);
        assertThat(adminApprove.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Regression: Duplicate statement upload is rejected with 409 Conflict")
    void testDuplicateStatementUploadRejected() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        var payload = Map.of(
                "rail", "mtn",
                "filename", "dup_stmt_" + s + ".csv",
                "lines", List.of(Map.of("externalReference", "DUP-" + s, "amountPesewas", 1000, "feePesewas", 10))
        );
        var first = api.call("POST", "/v1/ops/reconciliation/statements", opsToken, null, payload);
        assertThat(first.status()).isEqualTo(200);

        var second = api.call("POST", "/v1/ops/reconciliation/statements", opsToken, null, payload);
        assertThat(second.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("Regression: GET /v1/ops/suspense enforces bounded limit capped at 500")
    void testListSuspenseBoundedLimit() {
        for (int i = 0; i < 3; i++) {
            db.sql("""
                    INSERT INTO suspense_item (id, source, amount_pesewas, currency, reason, status, created_at)
                    VALUES (:id, 'reconciliation:mtn', 1000, 'GHS', 'Limit test', 'OPEN', now())""")
                    .param("id", UUID.randomUUID()).update();
        }
        var limited = api.call("GET", "/v1/ops/suspense?limit=2", opsToken, null, null);
        assertThat(limited.status()).isEqualTo(200);
        assertThat(limited.body().size()).isEqualTo(2);

        var capped = api.call("GET", "/v1/ops/suspense?limit=9999", opsToken, null, null);
        assertThat(capped.status()).isEqualTo(200);
        assertThat(capped.body().size()).isLessThanOrEqualTo(500);
    }
}
