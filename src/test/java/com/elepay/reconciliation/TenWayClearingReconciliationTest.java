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
        String accRand = UUID.randomUUID().toString().substring(0, 8);
        String mt940 = """
                :20:GCB-STAT-%s
                :25:1010%s
                :28C:00001/001
                :60F:C260301GHS50000,00
                :61:2603010301CR2000,00NTRFNONREF//%s
                :86:MATCHING TRANSACTION
                :61:2603010301CR500,00NTRFNONREF//%s
                :86:UNMATCHED BANK INFLOW
                :62F:C260301GHS52500,00
                -
                """.formatted(accRand, accRand, matchRef, missingRef);

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

    @Test
    @DisplayName("Empty or missing reference does not wildcard-match arbitrary ledger transaction")
    void testEmptyReferenceDoesNotMatchArbitraryLedgerTransaction() {
        // 1. Create an unrelated ledger transaction
        String unrelatedRef = "UNRELATED-TX-" + UUID.randomUUID().toString().substring(0, 8);
        String accA = "test:acc:a:" + UUID.randomUUID() + ":GHS";
        String accB = "test:acc:b:" + UUID.randomUUID() + ":GHS";
        ledger.openSystem(accA, "clearing", "GHS", true);
        ledger.openSystem(accB, "clearing", "GHS", true);

        ledger.post(Ledger.Posting.of(
                "unrelated_posting",
                unrelatedRef,
                "PAYMENT",
                List.of(Ledger.Leg.debit(accA, 150_000L), Ledger.Leg.credit(accB, 150_000L))
        ).describedAs("Arbitrary ledger transaction " + unrelatedRef));

        // 2. Ingest MT940 line with NONREF and empty customer reference
        String s = UUID.randomUUID().toString().substring(0, 8);
        String mt940 = """
                :20:GCB-BLANK-REF-%s
                :25:1010%s
                :28C:00003/001
                :60F:C260301GHS10000,00
                :61:2603010301CR1500,00NTRFNONREF//
                :86:NO REFERENCE BANK TRANSACTION
                :62F:C260301GHS11500,00
                -
                """.formatted(s, s);

        var ingestion = parser.ingestStatement("GCB", mt940);
        var report = reconEngine.reconcileBatch(ingestion.batchId());

        assertThat(report.totalProcessedLines()).isEqualTo(1);
        assertThat(report.exactMatches()).isZero();
        assertThat(report.missingInLedger()).isEqualTo(1);

        var outcome = report.outcomes().get(0);
        assertThat(outcome.matchType()).isEqualTo(TenWayClearingReconciliationEngine.MatchType.MISSING_IN_LEDGER);
        assertThat(outcome.status()).isEqualTo(TenWayClearingReconciliationEngine.MatchStatus.SUSPENSE_POSTED);
        assertThat(outcome.ledgerTxId()).isNull();
        assertThat(outcome.variancePesewas()).isEqualTo(150_000L);
    }

    @Test
    @DisplayName("Multi-leg split transaction matches total transaction volume, not a single leg")
    void testMultiLegSplitTransactionReconciliation() {
        String splitTxRef = "SPLIT-TX-" + UUID.randomUUID().toString().substring(0, 8);
        long buyerDebit = 300_000L; // 3,000.00 GHS
        long merchantCredit = 270_000L; // 2,700.00 GHS
        long feeCredit = 30_000L; // 300.00 GHS

        String buyerAcc = "test:buyer:" + UUID.randomUUID() + ":GHS";
        String merchantAcc = "test:merchant:" + UUID.randomUUID() + ":GHS";
        String feeAcc = "test:fee:" + UUID.randomUUID() + ":GHS";
        ledger.openSystem(buyerAcc, "clearing", "GHS", true);
        ledger.openSystem(merchantAcc, "clearing", "GHS", true);
        ledger.openSystem(feeAcc, "clearing", "GHS", true);

        ledger.post(Ledger.Posting.of(
                "split_payment",
                splitTxRef,
                "SPLIT_PAYMENT",
                List.of(
                        Ledger.Leg.debit(buyerAcc, buyerDebit),
                        Ledger.Leg.credit(merchantAcc, merchantCredit),
                        Ledger.Leg.credit(feeAcc, feeCredit)
                )
        ).describedAs("Multi-leg split payment " + splitTxRef));

        // Create bank statement line for total clearing amount (3,000.00 GHS)
        String splitRand = UUID.randomUUID().toString().substring(0, 8);
        String mt940 = """
                :20:GCB-SPLIT-%s
                :25:1010%s
                :28C:00004/001
                :60F:C260301GHS20000,00
                :61:2603010301CR3000,00NTRFNONREF//%s
                :86:MULTI LEG CLEARING INFLOW
                :62F:C260301GHS23000,00
                -
                """.formatted(splitRand, splitRand, splitTxRef);

        var ingestion = parser.ingestStatement("GCB", mt940);
        var report = reconEngine.reconcileBatch(ingestion.batchId());

        assertThat(report.totalProcessedLines()).isEqualTo(1);
        assertThat(report.exactMatches()).isEqualTo(1);
        assertThat(report.missingInLedger()).isZero();

        var outcome = report.outcomes().get(0);
        assertThat(outcome.matchType()).isEqualTo(TenWayClearingReconciliationEngine.MatchType.TWO_WAY_INTERNAL);
        assertThat(outcome.status()).isEqualTo(TenWayClearingReconciliationEngine.MatchStatus.RECONCILED);
        assertThat(outcome.ledgerAmountPesewas()).isEqualTo(300_000L);
        assertThat(outcome.bankAmountPesewas()).isEqualTo(300_000L);
        assertThat(outcome.variancePesewas()).isZero();
    }

    @Test
    @DisplayName("Regression: Reconciling same batch twice returns 409 Conflict and does not duplicate suspense")
    void testDuplicateReconcileBatchRejected() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        String missingRef = "DUP-RECON-" + s;
        String mt940 = """
                :20:GCB-DUP-%s
                :25:1010%s
                :28C:00009/001
                :60F:C260301GHS10000,00
                :61:2603010301CR100,00NTRFNONREF//%s
                :86:ORPHAN LINE
                :62F:C260301GHS10100,00
                -
                """.formatted(s, s, missingRef);

        var ingestion = parser.ingestStatement("GCB", mt940);
        var first = reconEngine.reconcileBatch(ingestion.batchId());
        assertThat(first.batchStatus()).isEqualTo("VARIANCE_SUSPENSE");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reconEngine.reconcileBatch(ingestion.batchId()))
                .isInstanceOf(com.elepay.common.ApiException.class)
                .hasMessageContaining("already been reconciled");
    }

    @Test
    @DisplayName("Regression: Debit/credit mark mismatch and currency mismatch are flagged, not exact matched")
    void testDebitCreditMarkAndCurrencyFiltering() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        String debitMismatchRef = "DC-MISMATCH-" + s;
        String usdRef = "USD-MISMATCH-" + s;

        String acc1 = "test:dc:a:" + s + ":GHS";
        String acc2 = "test:dc:b:" + s + ":GHS";
        ledger.openSystem(acc1, "clearing", "GHS", true);
        ledger.openSystem(acc2, "clearing", "GHS", true);
        ledger.post(Ledger.Posting.of("dc_test", debitMismatchRef, "PAYMENT",
                List.of(Ledger.Leg.debit(acc1, 100_000L), Ledger.Leg.credit(acc2, 100_000L))));

        String usdAcc1 = "test:usd:a:" + s + ":USD";
        String usdAcc2 = "test:usd:b:" + s + ":USD";
        ledger.openSystem(usdAcc1, "clearing", "USD", true);
        ledger.openSystem(usdAcc2, "clearing", "USD", true);
        ledger.post(Ledger.Posting.of("usd_test", usdRef, "PAYMENT",
                List.of(Ledger.Leg.debit(usdAcc1, 100_000L), Ledger.Leg.credit(usdAcc2, 100_000L))));

        // Statement is GHS, line 1 is DR (debit) against a PAYMENT (credit inflow), line 2 is CR against a USD posting
        String mt940 = """
                :20:GCB-DC-%s
                :25:1010%s
                :28C:00010/001
                :60F:C260301GHS50000,00
                :61:2603010301DR1000,00NTRFNONREF//%s
                :86:DEBIT AGAINST PAYMENT
                :61:2603010301CR1000,00NTRFNONREF//%s
                :86:GHS LINE VS USD LEDGER
                :62F:C260301GHS50000,00
                -
                """.formatted(s, s, debitMismatchRef, usdRef);

        var ingestion = parser.ingestStatement("GCB", mt940);
        var report = reconEngine.reconcileBatch(ingestion.batchId());
        assertThat(report.exactMatches()).isZero();
        assertThat(report.amountMismatches()).isEqualTo(1);
        assertThat(report.missingInLedger()).isEqualTo(1);
    }

    @Test
    @DisplayName("Regression: Unmatched internal transactions in statement period emit MISSING_IN_BANK")
    void testMissingInBankEmittedForUnmatchedPeriodTransaction() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        String internalOnlyRef = "INTERNAL-ONLY-" + s;
        String acc1 = "test:mib:a:" + s + ":GHS";
        String acc2 = "test:mib:b:" + s + ":GHS";
        ledger.openSystem(acc1, "clearing", "GHS", true);
        ledger.openSystem(acc2, "clearing", "GHS", true);
        ledger.post(Ledger.Posting.of("mib_test", internalOnlyRef, "PAYMENT",
                List.of(Ledger.Leg.debit(acc1, 75_000L), Ledger.Leg.credit(acc2, 75_000L))));

        // Use today's date in MT940 so posted_at (now()) falls inside the statement period
        String yyMMdd = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
        String mmdd = yyMMdd.substring(2);
        String mt940 = """
                :20:GCB-MIB-%s
                :25:1010%s
                :28C:00011/001
                :60F:C%sGHS10000,00
                :61:%s%sCR100,00NTRFNONREF//OTHER-%s
                :86:OTHER TX
                :62F:C%sGHS10100,00
                -
                """.formatted(s, s, yyMMdd, yyMMdd, mmdd, s, yyMMdd);

        var ingestion = parser.ingestStatement("GCB", mt940);
        var report = reconEngine.reconcileBatch(ingestion.batchId());
        assertThat(report.outcomes())
                .anyMatch(o -> o.matchType() == TenWayClearingReconciliationEngine.MatchType.MISSING_IN_BANK
                        && o.ledgerAmountPesewas() != null && o.ledgerAmountPesewas() == 75_000L);
    }
}
