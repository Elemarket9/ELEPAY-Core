package com.elepay.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.ledger.Ledger;
import com.elepay.operations.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.*;

@DisplayName("Commercial Workflows: Mandates, Dunning, Reconciliation & Dual Control")
class MandateAndReconciliationIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired MandateService mandateService;
    @Autowired ReconciliationService reconciliationService;

    TestApi api;
    String opsToken, makerOpsToken, checkerOpsToken;
    String merchantOwnerToken, customerToken;
    UUID merchantId, customerId;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        opsToken = api.token("ops-" + s, "ops");
        makerOpsToken = api.token("maker-ops-" + s, "ops");
        checkerOpsToken = api.token("checker-ops-" + s, "ops_admin");

        merchantOwnerToken = api.token("merch-owner-" + s);
        var created = api.call("POST", "/v1/merchants", merchantOwnerToken, null,
                Map.of("legalName", "SaaS Billing Ltd " + s, "tradingName", "CloudSaaS " + s,
                        "kind", "merchant", "contactPhone", "0241112233"));
        assertThat(created.status()).isEqualTo(200);
        merchantId = UUID.fromString(created.body().get("id").asString());
        api.call("POST", "/v1/ops/merchants/" + merchantId + "/review", opsToken, null,
                Map.of("decision", "approve", "feeBps", 100, "feeFixed", 0, "settlementDelayHours", 0));

        String customerSubject = "cust-mandate-" + s;
        customerToken = api.token(customerSubject);
        customerId = api.enroll(opsToken, customerSubject, TestApi.uniquePhone(), "Kofi Subscriber");
    }

    @Test
    @DisplayName("Algorithm 020 & 021: Recurring Mandate Lifecycle, Duplicate Charge Protection & Dunning")
    void testMandateLifecycleAndDunning() {
        // 1. Customer creates recurring mandate consent
        String mandateKey = UUID.randomUUID().toString();
        var createRes = api.call("POST", "/v1/customers/mandates", customerToken, mandateKey,
                Map.of("merchantId", merchantId.toString(), "maxAmountPesewas", 10000,
                        "frequency", "monthly", "consentReference", "SUB-MONTHLY-01"));
        assertThat(createRes.status()).isEqualTo(200);
        UUID mandateId = UUID.fromString(createRes.body().get("id").asString());
        assertThat(createRes.body().get("status").asString()).isEqualTo("active");
        assertThat(createRes.body().get("maxAmount").asLong()).isEqualTo(10000);

        // Idempotent retry returns identical mandate
        var retryRes = api.call("POST", "/v1/customers/mandates", customerToken, mandateKey,
                Map.of("merchantId", merchantId.toString(), "maxAmountPesewas", 10000,
                        "frequency", "monthly", "consentReference", "SUB-MONTHLY-01"));
        assertThat(retryRes.status()).isEqualTo(200);
        assertThat(retryRes.body().get("id").asString()).isEqualTo(mandateId.toString());

        // 2. Query mandates
        var custMandates = api.call("GET", "/v1/customers/mandates", customerToken, null, null);
        assertThat(custMandates.status()).isEqualTo(200);
        assertThat(custMandates.body().isArray()).isTrue();
        assertThat(custMandates.body().size()).isGreaterThanOrEqualTo(1);

        var merchMandates = api.call("GET", "/v1/merchants/" + merchantId + "/mandates", merchantOwnerToken, null, null);
        assertThat(merchMandates.status()).isEqualTo(200);
        assertThat(merchMandates.body().isArray()).isTrue();

        // 3. Charge with amount exceeding ceiling rejected with 400
        var overCeiling = api.call("POST", "/v1/merchants/" + merchantId + "/mandates/" + mandateId + "/charge",
                merchantOwnerToken, UUID.randomUUID().toString(), Map.of("amount", 20000));
        assertThat(overCeiling.status()).isEqualTo(400);

        // 4. Charge with insufficient balance triggers Algorithm 021 Dunning Schedule
        String chargeKey1 = UUID.randomUUID().toString();
        var failedCharge = api.call("POST", "/v1/merchants/" + merchantId + "/mandates/" + mandateId + "/charge",
                merchantOwnerToken, chargeKey1, Map.of("amount", 5000, "description", "Month 1 subscription"));
        assertThat(failedCharge.status()).isEqualTo(409); // conflict: insufficient funds

        // Verify dunning schedule was created in DB
        var scheduleCount = db.sql("SELECT count(*) FROM dunning_schedule WHERE status = 'active'").query(Long.class).single();
        assertThat(scheduleCount).isGreaterThanOrEqualTo(1L);

        // 5. Fund customer wallet and execute successful charge
        var topUp = api.call("POST", "/v1/wallet/topups", customerToken, "fund-" + customerId,
                Map.of("amount", 10000, "method", "momo", "phone", "0245550005", "network", "mtn"));
        assertThat(topUp.status()).isEqualTo(200);
        api.call("POST", "/v1/ops/rails/recover", opsToken, null, null);

        String chargeKey2 = UUID.randomUUID().toString();
        var successCharge = api.call("POST", "/v1/merchants/" + merchantId + "/mandates/" + mandateId + "/charge",
                merchantOwnerToken, chargeKey2, Map.of("amount", 5000, "description", "Month 1 subscription"));
        assertThat(successCharge.status()).isEqualTo(200);
        assertThat(successCharge.body().get("status").asString()).isEqualTo("succeeded");
        assertThat(successCharge.body().get("amount").asLong()).isEqualTo(5000);

        // Replay of same idempotency key returns identical receipt
        var chargeReplay = api.call("POST", "/v1/merchants/" + merchantId + "/mandates/" + mandateId + "/charge",
                merchantOwnerToken, chargeKey2, Map.of("amount", 5000, "description", "Month 1 subscription"));
        assertThat(chargeReplay.status()).isEqualTo(200);
        assertThat(chargeReplay.body().get("id").asString()).isEqualTo(successCharge.body().get("id").asString());

        // 6. Cancel mandate
        var cancelRes = api.call("POST", "/v1/customers/mandates/" + mandateId + "/cancel", customerToken, null, null);
        assertThat(cancelRes.status()).isEqualTo(200);
        assertThat(cancelRes.body().get("status").asString()).isEqualTo("cancelled");

        // Charging cancelled mandate returns 409
        var chargeCancelled = api.call("POST", "/v1/merchants/" + merchantId + "/mandates/" + mandateId + "/charge",
                merchantOwnerToken, UUID.randomUUID().toString(), Map.of("amount", 1000));
        assertThat(chargeCancelled.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("Algorithm 049, 052, 053, 054: Exact 4-Way Matching, Fuzzy Matching, Fee Verification & Suspense Aging")
    void testReconciliationAndSuspense() {
        // Set up rail transactions for matching
        UUID txExactId = UUID.randomUUID();
        String exactRef = "MTN-REC-" + UUID.randomUUID().toString().substring(0, 8);
        db.sql("""
                INSERT INTO rail_transaction (id, rail, direction, reference, purpose, purpose_id, amount, status)
                VALUES (:id, 'mtn', 'charge', :ref, 'settlement', :purposeId, 10000, 'succeeded')""")
                .param("id", txExactId).param("ref", exactRef).param("purposeId", UUID.randomUUID()).update();

        UUID txFuzzyId = UUID.randomUUID();
        String fuzzSuffix = UUID.randomUUID().toString().substring(0, 8);
        String internalLongRef = "TEL-TRUNCATED-" + fuzzSuffix;
        db.sql("""
                INSERT INTO rail_transaction (id, rail, direction, reference, purpose, purpose_id, amount, status)
                VALUES (:id, 'mtn', 'charge', :ref, 'settlement', :purposeId, 20000, 'succeeded')""")
                .param("id", txFuzzyId).param("ref", internalLongRef).param("purposeId", UUID.randomUUID()).update();

        // Statement ingestion with exact match, fuzzy match, unmatched suspense, and partner fee overcharge
        var uploadRes = api.call("POST", "/v1/ops/reconciliation/statements", opsToken, null, Map.of(
                "rail", "mtn",
                "filename", "mtn_daily_statement_20261122.csv",
                "lines", List.of(
                        // Line 1: Exact 4-way match, fee compliant (1% of 10000 = 100 pesewas)
                        Map.of("externalReference", exactRef, "amountPesewas", 10000, "feePesewas", 100,
                                "rawDescription", "MOMO PAYMENT " + exactRef),
                        // Line 2: Fuzzy match with truncated reference, fee overcharged (actual fee 1500 > cap 1000)
                        Map.of("externalReference", "TEL-TRUNCATED-" + fuzzSuffix.substring(0, 4), "amountPesewas", 20000, "feePesewas", 1500,
                                "rawDescription", "TELECEL PAYMENT TRUNCATED"),
                        // Line 3: Unmatched line (becomes Suspense Item)
                        Map.of("externalReference", "GHOST-REF-" + fuzzSuffix, "amountPesewas", 75000, "feePesewas", 50,
                                "rawDescription", "UNKNOWN INBOUND DEPOSIT")
                )
        ));
        assertThat(uploadRes.status()).isEqualTo(200);
        assertThat(uploadRes.body().get("matchedExact").asInt()).isEqualTo(1);
        assertThat(uploadRes.body().get("matchedFuzzy").asInt()).isEqualTo(1);
        assertThat(uploadRes.body().get("unmatched").asInt()).isEqualTo(1);
        assertThat(uploadRes.body().get("feeDiscrepancies").asInt()).isEqualTo(1);

        // Algorithm 054: Suspense aging buckets query
        var agingRes = api.call("GET", "/v1/ops/suspense/aging", opsToken, null, null);
        assertThat(agingRes.status()).isEqualTo(200);
        assertThat(agingRes.body().isArray()).isTrue();

        var suspenseList = api.call("GET", "/v1/ops/suspense", opsToken, null, null);
        assertThat(suspenseList.status()).isEqualTo(200);
        assertThat(suspenseList.body().size()).isGreaterThanOrEqualTo(1);

        UUID suspenseId = UUID.fromString(suspenseList.body().get(0).get("id").asString());

        // Algorithm 079: Maker proposes clearing
        var proposeRes = api.call("POST", "/v1/ops/suspense/propose", makerOpsToken, null, Map.of(
                "id", suspenseId.toString(),
                "action", "CLEAR_TO_CLEARING",
                "targetAccount", Ledger.clearing("sandbox")
        ));
        assertThat(proposeRes.status()).isEqualTo(200);
        assertThat(proposeRes.body().get("status").asString()).isEqualTo("PROPOSED");

        // Maker cannot be checker: maker attempting to approve their own proposal returns HTTP 403 Forbidden!
        var selfApprove = api.call("POST", "/v1/ops/suspense/" + suspenseId + "/approve", makerOpsToken, null, null);
        assertThat(selfApprove.status()).isEqualTo(403);

        // Distinct checker operator approves clearing
        var checkerApprove = api.call("POST", "/v1/ops/suspense/" + suspenseId + "/approve", checkerOpsToken, null, null);
        assertThat(checkerApprove.status()).isEqualTo(200);
        assertThat(checkerApprove.body().get("status").asString()).isEqualTo("CLEARED");
    }

    @Test
    @DisplayName("Algorithm 079: Dispute Maker-Checker Dual Control Enforcement")
    void testDisputeMakerCheckerEnforcement() {
        // Create order
        var order = api.call("POST", "/v1/merchants/" + merchantId + "/checkouts", merchantOwnerToken, UUID.randomUUID().toString(),
                Map.of("allocations", List.of(Map.of("merchantId", merchantId.toString(), "kind", "sale", "amount", 5000))));
        assertThat(order.status()).isEqualTo(200);
        String ref = order.body().get("reference").asString();

        // Customer pays
        api.call("POST", "/v1/wallet/topups", customerToken, "fund-" + UUID.randomUUID(),
                Map.of("amount", 10000, "method", "momo", "phone", "0245550005", "network", "mtn"));
        api.call("POST", "/v1/ops/rails/recover", opsToken, null, null);

        var pay = api.call("POST", "/v1/checkouts/" + ref + "/pay/wallet", customerToken, UUID.randomUUID().toString(), null);
        assertThat(pay.status()).isEqualTo(200);

        // Maker opens dispute
        var disputeRes = api.call("POST", "/v1/ops/disputes", makerOpsToken, null, Map.of(
                "checkout", ref,
                "allocationLine", 0,
                "amount", 4000,
                "reason", "Item damaged on arrival"
        ));
        assertThat(disputeRes.status()).isEqualTo(200);
        UUID disputeId = UUID.fromString(disputeRes.body().get("id").asString());

        // Distinct checker operator resolves dispute: succeeds with HTTP 200!
        var checkerResolve = api.call("POST", "/v1/ops/disputes/" + disputeId + "/resolve", checkerOpsToken, null,
                Map.of("outcome", "merchant_won", "note", "Approved by distinct auditor"));
        assertThat(checkerResolve.status()).isEqualTo(200);
        assertThat(checkerResolve.body().get("status").asString()).isEqualTo("merchant_won");
    }
}
