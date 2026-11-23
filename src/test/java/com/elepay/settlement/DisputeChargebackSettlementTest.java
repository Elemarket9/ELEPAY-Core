package com.elepay.settlement;

import com.elepay.PostgresTestBase;
import com.elepay.ledger.Ledger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DisputeChargebackSettlementTest extends PostgresTestBase {

    @Autowired
    private DisputeChargebackSettlementService disputeService;

    @Autowired
    private Ledger ledger;

    @Autowired
    private JdbcClient db;

    @Test
    @DisplayName("Pillar 2: Dispute chargeback defensive hold, fee assessment, evidence linkage, and win/loss resolution")
    void testDisputeLifecycle() {
        UUID merchantId = UUID.randomUUID();
        String merchantCode = String.format("%06d", (int) (Math.random() * 900000) + 100000);

        // 1. Seed merchant profile
        db.sql("""
            INSERT INTO merchant (
                id, legal_name, trading_name, kind, status, merchant_code, contact_phone, created_by
            ) VALUES (
                :id, 'Dispute Test Merchant Ltd', 'DisputeMerch', 'merchant', 'active', :code, '+233240003344', 'test'
            )
        """)
        .param("id", merchantId)
        .param("code", merchantCode)
        .update();

        // 2. Fund merchant available balance with GHS 5,000.00 (500,000 pesewas)
        String merchantAvailable = Ledger.code("merchant", merchantId, "available", Ledger.GHS);
        String suspenseAccount = "system:clearing:chargeback_suspense:GHS";
        String disputeFeeAccount = "system:fee:dispute_admin:GHS";
        String settlementAccount = "system:clearing:chargeback_settlement:GHS";

        ledger.open("merchant", merchantId, "available", Ledger.GHS);
        ledger.openSystem("system:clearing:test_funding", "clearing", Ledger.GHS, true);

        ledger.post(Ledger.Posting.of(
                "fund_merchant",
                "FUND-" + merchantId,
                "MERCHANT_SEED",
                List.of(
                        Ledger.Leg.debit("system:clearing:test_funding", 500_000L),
                        Ledger.Leg.credit(merchantAvailable, 500_000L)
                )
        ));

        long baseSuspense = ledger.balance(suspenseAccount);
        long baseFee = ledger.balance(disputeFeeAccount);
        long baseSettlement = ledger.balance(settlementAccount);

        // 3. Scenario A: Initiate Dispute #1 (Lost by merchant)
        String disputeRef1 = "DSP-" + UUID.randomUUID().toString().substring(0, 8);
        String txRef1 = "TX-" + UUID.randomUUID().toString().substring(0, 8);
        var record1 = disputeService.initiateDispute(
                disputeRef1,
                txRef1,
                merchantId,
                "CUST-001",
                Ledger.GHS,
                100_000L, // 1,000 GHS disputed
                5_000L,   // 50 GHS administrative fee
                "FRAUD_UNRECOGNIZED",
                null
        );

        assertThat(record1.status()).isEqualTo("INITIATED");
        // Merchant balance: 500,000 - 105,000 = 395,000
        assertThat(ledger.balance(merchantAvailable)).isEqualTo(395_000L);
        assertThat(ledger.balance(suspenseAccount)).isEqualTo(baseSuspense + 100_000L);
        assertThat(ledger.balance(disputeFeeAccount)).isEqualTo(baseFee + 5_000L);

        // Submit evidence
        String evidenceHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        var evidenceRecord = disputeService.submitEvidence(disputeRef1, evidenceHash);
        assertThat(evidenceRecord.status()).isEqualTo("EVIDENCE_SUBMITTED");
        assertThat(evidenceRecord.evidenceHash()).isEqualTo(evidenceHash);

        // Resolve: Merchant Lost -> Funds sweep from suspense to settlement account
        var resolvedLoss = disputeService.resolveDispute(disputeRef1, false, "Customer confirmed card stolen");
        assertThat(resolvedLoss.status()).isEqualTo("MERCHANT_LOST");
        assertThat(ledger.balance(suspenseAccount)).isEqualTo(baseSuspense);
        assertThat(ledger.balance(settlementAccount)).isEqualTo(baseSettlement + 100_000L);
        assertThat(ledger.balance(merchantAvailable)).isEqualTo(395_000L);

        // 4. Scenario B: Initiate Dispute #2 (Won by merchant)
        String disputeRef2 = "DSP-" + UUID.randomUUID().toString().substring(0, 8);
        String txRef2 = "TX-" + UUID.randomUUID().toString().substring(0, 8);
        disputeService.initiateDispute(
                disputeRef2,
                txRef2,
                merchantId,
                "CUST-002",
                Ledger.GHS,
                50_000L, // 500 GHS disputed
                2_500L,  // 25 GHS fee
                "GOODS_NOT_RECEIVED",
                "proof-of-delivery-hash-12345"
        );

        // Merchant available: 395,000 - 52,500 = 342,500
        assertThat(ledger.balance(merchantAvailable)).isEqualTo(342_500L);
        assertThat(ledger.balance(suspenseAccount)).isEqualTo(baseSuspense + 50_000L);

        // Resolve: Merchant Won -> Disputed funds returned to merchant available balance
        var resolvedWin = disputeService.resolveDispute(disputeRef2, true, "Valid signed delivery receipt provided");
        assertThat(resolvedWin.status()).isEqualTo("MERCHANT_WON");
        assertThat(ledger.balance(suspenseAccount)).isEqualTo(baseSuspense);
        // Merchant available: 342,500 + 50,000 = 392,500
        assertThat(ledger.balance(merchantAvailable)).isEqualTo(392_500L);
    }
}
