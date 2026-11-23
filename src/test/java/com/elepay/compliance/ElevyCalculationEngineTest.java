package com.elepay.compliance;

import com.elepay.PostgresTestBase;
import com.elepay.ledger.Ledger;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class ElevyCalculationEngineTest extends PostgresTestBase {
    @Autowired private ElevyCalculationEngine engine;
    @Autowired private ElevyStatutoryReportingService reports;
    @Autowired private Ledger ledger;
    @Autowired private JdbcClient db;

    @Test
    void repeatedAssessmentAboveFormerThresholdHasNoFinancialEffect() {
        UUID customer = UUID.randomUUID();
        var transfer = new ElevyCalculationEngine.TransferContext(
                customer.toString(), customer, "recipient", null, Long.MAX_VALUE, false);
        var result = engine.assessAndChargeElevy(transfer);
        assertThat(engine.assessAndChargeElevy(transfer)).isEqualTo(result);
        assertThat(result.levyAmountPesewas()).isZero();
        assertThat(result.taxablePortionPesewas()).isZero();
        assertThat(result.exemptPortionPesewas()).isEqualTo(Long.MAX_VALUE);
        assertThat(result.ledgerTxId()).isNull();
        assertThat(result.graClearanceCode()).isNull();
        assertThat(db.sql("SELECT count(*) FROM elevy_transaction_log WHERE customer_id = :id")
                .param("id", customer).query(Long.class).single()).isZero();
    }

    @Test
    void historicalChargeCanBeRefundedOnlyOnce() {
        UUID customer = UUID.randomUUID();
        String reference = customer.toString();
        ledger.open("customer", customer, "available", Ledger.GHS);
        ledger.openSystem(ElevyCalculationEngine.ELEVY_SYSTEM_ACCOUNT, "clearing", Ledger.GHS, true);
        db.sql("""
                INSERT INTO elevy_transaction_log
                    (id, transaction_reference, customer_id, recipient_identifier,
                     transfer_amount_pesewas, exempt_portion_pesewas, taxable_portion_pesewas,
                     levy_amount_pesewas, created_at)
                VALUES (:id, :ref, :id, 'recipient', 13000, 10000, 3000, 30, '2025-03-01')
                """).param("ref", reference).param("id", customer).update();
        var refund = engine.refundElevy(reference, "Historic correction");
        assertThat(refund.refundedLevyPesewas()).isEqualTo(30);
        assertThat(refund.reversalLedgerTxId()).isNotNull();
        assertThat(engine.refundElevy(reference, "Retry").refundedLevyPesewas()).isZero();
    }

    @Test
    void locallyPreparedBatchHasNoExternalAcknowledgment() {
        var batch = reports.generateDailyBatch(LocalDate.of(2025, 3, 1));
        assertThat(batch.status()).isEqualTo("PREPARED");
        assertThat(batch.submittedAt()).isNull();
        assertThat(batch.graAckCode()).isNull();
        assertThat(db.sql("""
                SELECT count(*) FROM elevy_statutory_batch WHERE id = :id
                AND status = 'PREPARED' AND gra_ack_code IS NULL AND submitted_at IS NULL
                """).param("id", batch.batchId()).query(Long.class).single()).isOne();
    }
}
