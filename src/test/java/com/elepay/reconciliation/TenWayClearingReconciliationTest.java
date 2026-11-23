package com.elepay.reconciliation;

import com.elepay.PostgresTestBase;
import com.elepay.ledger.Ledger;
import com.elepay.rails.DirectCarrierSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenWayClearingReconciliationTest extends PostgresTestBase {

    @Autowired
    private Mt940StatementParser parser;

    @Autowired
    private TenWayClearingReconciliationEngine reconEngine;

    @Autowired
    private DirectCarrierSessionService carrierService;

    @Autowired
    private Ledger ledger;

    @Test
    @DisplayName("10-Way clearing reconciliation: exact match and missing-in-ledger classification")
    void testReconciliationMatching() {
        String matchRef = "RECON-TX-" + UUID.randomUUID().toString().substring(0, 8);
        long matchedAmountPesewas = 200_000L; // 2,000.00 GHS

        // 1. Post to double-entry ledger
        String testAcc1 = "test:customer:" + UUID.randomUUID() + ":GHS";
        String testAcc2 = "test:merchant:" + UUID.randomUUID() + ":GHS";
        ledger.openSystem(testAcc1, "clearing", "GHS", true);
        ledger.openSystem(testAcc2, "clearing", "GHS", true);

        ledger.post(Ledger.Posting.of(
                "recon_test",
                matchRef,
                "PAYMENT",
                List.of(Ledger.Leg.debit(testAcc1, matchedAmountPesewas), Ledger.Leg.credit(testAcc2, matchedAmountPesewas))
        ).describedAs("Reconciliation match test " + matchRef));

        // 2. Create carrier session
        carrierService.createSession(
                DirectCarrierSessionService.Carrier.GHIPSS,
                DirectCarrierSessionService.SessionType.COLLECTION,
                matchRef,
                "BANK",
                "+233240000000",
                matchedAmountPesewas,
                "GHS",
                "{}"
        );

        // 3. Create MT940 bank statement with 1 matching line and 1 missing-in-ledger line
        String missingRef = "ORPHAN-LINE-" + UUID.randomUUID().toString().substring(0, 8);
        String mt940 = """
                :20:GCB-STAT-RECON
                :25:1010009999999
                :28C:00001/001
                :60F:C260301GHS50000,00
                :61:2603010301CR2000,00NTRFNONREF//%s
                :86:MATCHING TRANSACTION
                :61:2603010301CR500,00NTRFNONREF//%s
                :86:UNMATCHED BANK INFLOW
                :62F:C260301GHS52500,00
                -
                """.formatted(matchRef, missingRef);

        var ingestion = parser.ingestStatement("GCB", mt940);
        assertThat(ingestion.batchId()).isNotNull();

        // 4. Run 10-way reconciliation
        var report = reconEngine.reconcileBatch(ingestion.batchId());

        assertThat(report).isNotNull();
        assertThat(report.totalProcessedLines()).isEqualTo(2);
        assertThat(report.exactMatches()).isEqualTo(1);
        assertThat(report.missingInLedger()).isEqualTo(1);
        assertThat(report.outcomes()).hasSize(2);

        var matchOutcome = report.outcomes().stream()
                .filter(o -> o.matchType() == TenWayClearingReconciliationEngine.MatchType.THREE_WAY_EXACT)
                .findFirst();
        assertThat(matchOutcome).isPresent();
        assertThat(matchOutcome.get().status()).isEqualTo(TenWayClearingReconciliationEngine.MatchStatus.RECONCILED);
        assertThat(matchOutcome.get().variancePesewas()).isZero();

        var missingOutcome = report.outcomes().stream()
                .filter(o -> o.matchType() == TenWayClearingReconciliationEngine.MatchType.MISSING_IN_LEDGER)
                .findFirst();
        assertThat(missingOutcome).isPresent();
        assertThat(missingOutcome.get().status()).isEqualTo(TenWayClearingReconciliationEngine.MatchStatus.SUSPENSE_POSTED);
        assertThat(missingOutcome.get().variancePesewas()).isEqualTo(50_000L);
    }
}
