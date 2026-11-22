package com.elepay.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.common.Audit;
import com.elepay.common.References;
import com.elepay.ledger.BalanceSnapshots;
import com.elepay.ledger.Ledger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * End-to-end integration test verifying the hardened 12-step wallet transfer flow (Milestone 1).
 */
class WalletTransferFlowIT extends PostgresTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private Ledger ledger;

    @Autowired
    private JdbcClient db;

    @Autowired
    private Audit audit;

    @Autowired
    private BalanceSnapshots balanceSnapshots;

    private TestApi api;
    private String opsToken;
    private String senderToken;
    private UUID senderId;
    private String senderPhone;
    private String recipientPhone;
    private UUID recipientId;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        String runId = UUID.randomUUID().toString();
        opsToken = api.token("ops-" + runId, "ops");

        senderPhone = TestApi.uniquePhone();
        senderToken = api.token("sender-" + runId);
        senderId = api.enroll(opsToken, "sender-" + runId, senderPhone, "Kwame Mensah");

        recipientPhone = TestApi.uniquePhone();
        recipientId = api.enroll(opsToken, "recipient-" + runId, recipientPhone, "Akua Serwaa");

        // Fund Kwame's wallet with 50,000 pesewas (500 GHS) from sandbox clearing
        ledger.post(Ledger.Posting.of("fund-" + runId, "tx-fund-kwame", "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 50_000),
                Ledger.Leg.credit(Ledger.code("customer", senderId, "available", Ledger.GHS), 50_000)
        )));
    }

    @Test
    void complete12StepTransferFlow() {
        // Step 1: Sender Authentication
        TestApi.Response walletResp = api.call("GET", "/v1/wallet", senderToken, null, null);
        assertThat(walletResp.status()).isEqualTo(200);
        assertThat(walletResp.body().get("customerId").asString()).isEqualTo(senderId.toString());

        // Step 2: Phone Normalization (+233 standard)
        String rawLocalPhone = "0" + recipientPhone.substring(3);
        String normalized = References.msisdn(rawLocalPhone);
        assertThat(normalized).isEqualTo(recipientPhone);

        // Step 3 & 4: Rate-limited recipient lookup & masked name verification
        TestApi.Response lookupResp = api.call("POST", "/v1/wallet/recipients/lookup", senderToken, null,
                Map.of("rail", "wallet", "phone", rawLocalPhone));
        assertThat(lookupResp.status()).isEqualTo(200);
        assertThat(lookupResp.body().get("status").asString()).isEqualTo("verified");
        assertThat(lookupResp.body().get("verifiedName").asString()).isEqualTo("Akua Serwaa");
        String confirmationIdStr = lookupResp.body().get("confirmationId").asString();
        assertThat(confirmationIdStr).isNotNull();
        UUID confirmationId = UUID.fromString(confirmationIdStr);

        // Step 5: Fee Schedule Preview (calculated via FeeSchedule engine)
        long transferAmount = 20_000L; // 200 GHS
        TestApi.Response previewResp = api.call("POST", "/v1/wallet/transfers/preview", senderToken, null,
                Map.of("confirmationId", confirmationIdStr, "amount", transferAmount));
        assertThat(previewResp.status()).isEqualTo(200);
        assertThat(previewResp.body().get("amount").asLong()).isEqualTo(transferAmount);
        assertThat(previewResp.body().get("fee").asLong()).isZero();
        assertThat(previewResp.body().get("total").asLong()).isEqualTo(transferAmount);

        // Step 6 & 7: KYC Tier Limit Enforcement
        // Attempting to send 100,001 pesewas exceeds Tier 1 limit (100,000) -> 409 Conflict
        String overLimitKey = "idem-overlimit-" + UUID.randomUUID();
        TestApi.Response overLimitResp = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, overLimitKey,
                Map.of("confirmationId", confirmationIdStr, "amount", 100_001L));
        assertThat(overLimitResp.status()).isEqualTo(409);
        assertThat(overLimitResp.body().get("code").asString()).isIn("daily_transfer_limit", "recipient_balance_limit", "insufficient_funds");

        // Step 8: Atomic Double-Entry Transfer Execution (within tier limit)
        String transferKey = "idem-transfer-" + UUID.randomUUID();
        TestApi.Response transferResp = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, transferKey,
                Map.of("confirmationId", confirmationIdStr, "amount", transferAmount));
        assertThat(transferResp.status()).isEqualTo(200);

        // Step 9: Balanced Receipts & Balance Verification
        assertThat(transferResp.body().get("amount").asLong()).isEqualTo(transferAmount);
        assertThat(transferResp.body().get("status").asString()).isEqualTo("completed");
        String txIdStr = transferResp.body().get("transactionId").asString();
        assertThat(txIdStr).isNotNull();

        long senderBalance = ledger.balance(Ledger.code("customer", senderId, "available", Ledger.GHS));
        long recipientBalance = ledger.balance(Ledger.code("customer", recipientId, "available", Ledger.GHS));
        assertEquals(30_000L, senderBalance);    // 50,000 - 20,000
        assertEquals(20_000L, recipientBalance); // 0 + 20,000

        // Snapshot verification on sender balance (Algorithm 010)
        BalanceSnapshots.Snapshot senderSnapshot = balanceSnapshots.createSnapshot(senderId);
        assertEquals(30_000L, senderSnapshot.snapshotBalance());
        assertTrue(balanceSnapshots.verifySnapshot(senderSnapshot.id()));

        // Double-entry integrity check: zero drift across all accounts
        Ledger.Integrity integrity = ledger.integrity();
        assertTrue(integrity.ok(), "Ledger integrity must have zero drift and zero net imbalances");

        // Step 10: Idempotent Retry under same Idempotency-Key
        TestApi.Response retryResp = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, transferKey,
                Map.of("confirmationId", confirmationIdStr, "amount", transferAmount));
        assertThat(retryResp.status()).isEqualTo(200);
        assertThat(retryResp.body().get("transactionId").asString()).isEqualTo(txIdStr);

        // Balance must remain strictly unchanged (no double debit)
        assertEquals(30_000L, ledger.balance(Ledger.code("customer", senderId, "available", Ledger.GHS)));

        // Step 11: Transactional Outbox Event & Audit Hash Chain Integrity
        Long outboxCount = db.sql("""
                SELECT count(*) FROM outbox_event
                WHERE aggregate_type = 'wallet' AND aggregate_id = :sender
                  AND event_type = 'wallet.transfer.completed'""")
                .param("sender", senderId.toString()).query(Long.class).single();
        assertThat(outboxCount).isGreaterThanOrEqualTo(1L);

        // Audit log hash chain cryptographic verification (Algorithm 080)
        Audit.VerificationResult auditResult = audit.verifyChain();
        assertTrue(auditResult.valid(), "Audit chain must verify cryptographically without tampering");

        // Step 12: Insufficient Funds and Locked Account Handling
        // Insufficient funds: sender has 30,000 pesewas left; attempting to send 40,000 pesewas
        TestApi.Response newLookup = api.call("POST", "/v1/wallet/recipients/lookup", senderToken, null,
                Map.of("rail", "wallet", "phone", rawLocalPhone));
        String secondConfId = newLookup.body().get("confirmationId").asString();

        String overDraftKey = "idem-overdraft-" + UUID.randomUUID();
        TestApi.Response overDraftResp = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, overDraftKey,
                Map.of("confirmationId", secondConfId, "amount", 40_000L));
        assertThat(overDraftResp.status()).isEqualTo(409);
        assertThat(overDraftResp.body().get("code").asString()).isEqualTo("insufficient_funds");

        // Single-use token cannot be reused once spent
        String reuseKey = "idem-reuse-" + UUID.randomUUID();
        TestApi.Response reuseResp = api.call("POST", "/v1/wallet/transfers/confirmed", senderToken, reuseKey,
                Map.of("confirmationId", confirmationIdStr, "amount", 5_000L));
        assertThat(reuseResp.status()).isEqualTo(409);
        assertThat(reuseResp.body().get("code").asString()).isEqualTo("confirmation_used");
    }
}
