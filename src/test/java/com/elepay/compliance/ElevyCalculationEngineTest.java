package com.elepay.compliance;

import com.elepay.PostgresTestBase;
import com.elepay.ledger.Ledger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ElevyCalculationEngineTest extends PostgresTestBase {

    @Autowired
    private ElevyCalculationEngine elevyEngine;

    @Autowired
    private ElevyStatutoryReportingService reportingService;

    @Autowired
    private Ledger ledger;

    @Autowired
    private JdbcClient db;

    @Test
    @DisplayName("Pillar 1: GRA Act 1075 e-Levy statutory calculation, 100 GHS daily threshold, self-transfers, and reversals")
    void testElevyCalculationLifecycle() {
        UUID customerId = UUID.randomUUID();
        String ghanaCard = "GHA-712345678-9";

        // Setup customer exemption profile with Ghana Card
        db.sql("""
            INSERT INTO elevy_exemption_profile (
                id, customer_id, tin, ghana_card_number, primary_phone, is_tax_exempt
            ) VALUES (
                :id, :cid, 'P0001234567', :ghCard, '+233241112233', false
            )
        """)
                .param("id", UUID.randomUUID())
                .param("cid", customerId)
                .param("ghCard", ghanaCard)
                .update();

        // Ensure customer wallet has initial balance
        ledger.open("customer", customerId, "available", Ledger.GHS);
        ledger.openSystem("system:clearing:test", "clearing", Ledger.GHS, true);
        ledger.post(Ledger.Posting.of(
                "fund_test",
                "FUND-" + customerId,
                "FUNDING",
                java.util.List.of(
                        Ledger.Leg.debit("system:clearing:test", 100_000L),
                        Ledger.Leg.credit(Ledger.code("customer", customerId, "available", Ledger.GHS), 100_000L)
                )
        ));

        // 1. Transfer 1: 60.00 GHS (6,000 pesewas) -> Fully under 100 GHS threshold -> EXEMPT
        var assess1 = elevyEngine.assessAndChargeElevy(new ElevyCalculationEngine.TransferContext(
                "TX-ELEV-001-" + UUID.randomUUID().toString().substring(0, 6),
                customerId,
                "+233201112233",
                null,
                6_000L,
                false
        ));

        assertThat(assess1.exemptPortionPesewas()).isEqualTo(6_000L);
        assertThat(assess1.taxablePortionPesewas()).isZero();
        assertThat(assess1.levyAmountPesewas()).isZero();
        assertThat(assess1.isExempt()).isTrue();

        // 2. Transfer 2: 70.00 GHS (7,000 pesewas) -> Exceeds remaining 40 GHS exemption -> Partial Tax
        // Exempt = 4,000 pesewas, Taxable = 3,000 pesewas, Levy = 1% of 3,000 = 30 pesewas
        String ref2 = "TX-ELEV-002-" + UUID.randomUUID().toString().substring(0, 6);
        var assess2 = elevyEngine.assessAndChargeElevy(new ElevyCalculationEngine.TransferContext(
                ref2,
                customerId,
                "+233201112233",
                null,
                7_000L,
                false
        ));

        assertThat(assess2.exemptPortionPesewas()).isEqualTo(4_000L);
        assertThat(assess2.taxablePortionPesewas()).isEqualTo(3_000L);
        assertThat(assess2.levyAmountPesewas()).isEqualTo(30L); // 30 pesewas (0.30 GHS)
        assertThat(assess2.isExempt()).isFalse();
        assertThat(assess2.ledgerTxId()).isNotNull();

        // 3. Transfer 3: 50.00 GHS (5,000 pesewas) -> Daily exemption exhausted -> Fully Taxable
        // Taxable = 5,000 pesewas, Levy = 1% of 5,000 = 50 pesewas
        var assess3 = elevyEngine.assessAndChargeElevy(new ElevyCalculationEngine.TransferContext(
                "TX-ELEV-003-" + UUID.randomUUID().toString().substring(0, 6),
                customerId,
                "+233201112233",
                null,
                5_000L,
                false
        ));

        assertThat(assess3.exemptPortionPesewas()).isZero();
        assertThat(assess3.taxablePortionPesewas()).isEqualTo(5_000L);
        assertThat(assess3.levyAmountPesewas()).isEqualTo(50L);

        // 4. Self-transfer: transferring to another account with matching Ghana Card -> EXEMPT
        var assessSelf = elevyEngine.assessAndChargeElevy(new ElevyCalculationEngine.TransferContext(
                "TX-ELEV-SELF-" + UUID.randomUUID().toString().substring(0, 6),
                customerId,
                "+233249998877",
                ghanaCard,
                20_000L,
                false
        ));
        assertThat(assessSelf.isExempt()).isTrue();
        assertThat(assessSelf.levyAmountPesewas()).isZero();
        assertThat(assessSelf.exemptionReason()).isEqualTo("SELF_TRANSFER_EXEMPT");

        // 5. Commercial merchant payment -> EXEMPT
        var assessMerchant = elevyEngine.assessAndChargeElevy(new ElevyCalculationEngine.TransferContext(
                "TX-ELEV-MCH-" + UUID.randomUUID().toString().substring(0, 6),
                customerId,
                "MERCHANT-STORE-01",
                null,
                15_000L,
                true
        ));
        assertThat(assessMerchant.isExempt()).isTrue();
        assertThat(assessMerchant.levyAmountPesewas()).isZero();
        assertThat(assessMerchant.exemptionReason()).isEqualTo("COMMERCIAL_MERCHANT_EXEMPT");

        // 6. Reversal & Refund: Reverse Transfer 2
        var refundResult = elevyEngine.refundElevy(ref2, "Customer transfer cancelled by bank switch");
        assertThat(refundResult.status()).isEqualTo("REVERSED");
        assertThat(refundResult.refundedLevyPesewas()).isEqualTo(30L);
        assertThat(refundResult.reversalLedgerTxId()).isNotNull();

        // 7. Statutory Batch Compilation
        var batch = reportingService.generateDailyBatch(LocalDate.now());
        assertThat(batch).isNotNull();
        assertThat(batch.totalTransactions()).isGreaterThanOrEqualTo(5);
        assertThat(batch.batchSha256()).isNotBlank();
        assertThat(batch.graAckCode()).startsWith("GRA-ACK-");
    }
}
