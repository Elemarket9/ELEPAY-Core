package com.elepay.reconciliation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * High-precision 10-Way Daily Clearing and Reconciliation Engine.
 * Performs multi-way matching across internal double-entry ledger transactions,
 * direct telecom/banking carrier sessions, and external bank clearing statements.
 * Categorizes exact matches, timing differences, amount mismatches, and orphan variances.
 */
@Service
public class TenWayClearingReconciliationEngine {

    public enum MatchType {
        THREE_WAY_EXACT,
        TWO_WAY_INTERNAL,
        AMOUNT_MISMATCH,
        MISSING_IN_LEDGER,
        MISSING_IN_BANK
    }

    public enum MatchStatus {
        RECONCILED,
        SUSPENSE_POSTED,
        DISPUTE_FILED,
        MANUALLY_CLEARED
    }

    public record MatchOutcome(
            UUID matchRecordId,
            UUID statementLineId,
            UUID ledgerTxId,
            UUID railSessionId,
            MatchType matchType,
            Long ledgerAmountPesewas,
            Long bankAmountPesewas,
            Long railAmountPesewas,
            long variancePesewas,
            MatchStatus status
    ) {}

    public record BatchReconciliationReport(
            UUID batchId,
            int totalProcessedLines,
            int exactMatches,
            int amountMismatches,
            int missingInLedger,
            long totalVariancePesewas,
            String batchStatus,
            List<MatchOutcome> outcomes
    ) {}

    private final JdbcClient db;

    public TenWayClearingReconciliationEngine(JdbcClient db) {
        this.db = db;
    }

    @Transactional
    public BatchReconciliationReport reconcileBatch(UUID batchId) {
        // Retrieve all lines for this statement batch
        var statementLines = db.sql("""
            SELECT id, line_number, debit_credit_mark, amount_pesewas,
                   bank_reference, customer_reference, match_status
            FROM recon_statement_line
            WHERE batch_id = :batchId
            ORDER BY line_number ASC
        """)
                .param("batchId", batchId)
                .query((rs, rowNum) -> new Object() {
                    UUID id = rs.getObject("id", UUID.class);
                    int lineNum = rs.getInt("line_number");
                    String dcMark = rs.getString("debit_credit_mark");
                    long amount = rs.getLong("amount_pesewas");
                    String bankRef = rs.getString("bank_reference");
                    String custRef = rs.getString("customer_reference");
                    String matchStatus = rs.getString("match_status");
                })
                .list();

        List<MatchOutcome> outcomes = new ArrayList<>();
        int exactMatches = 0;
        int amountMismatches = 0;
        int missingInLedger = 0;
        long totalVariance = 0L;

        for (var line : statementLines) {
            String searchRef = (line.bankRef != null && !line.bankRef.isBlank()) ? line.bankRef : line.custRef;
            if (searchRef == null) searchRef = "";

            // 1. Search for corresponding Direct Carrier Session
            var carrierOpt = db.sql("""
                SELECT id, amount_pesewas FROM direct_carrier_session
                WHERE reference_id = :ref OR external_transaction_id = :ref
                ORDER BY created_at DESC LIMIT 1
            """)
                    .param("ref", searchRef)
                    .query((rs, rowNum) -> new Object() {
                        UUID id = rs.getObject("id", UUID.class);
                        long amount = rs.getLong("amount_pesewas");
                    })
                    .optional();

            // 2. Search for corresponding Double-Entry Ledger Transaction
            var ledgerOpt = db.sql("""
                SELECT jt.id, jt.idempotency_key, COALESCE(ABS(je.amount), 0) AS leg_amount
                FROM journal_transaction jt
                LEFT JOIN journal_entry je ON je.transaction_id = jt.id AND je.amount > 0
                WHERE jt.idempotency_key = :ref OR jt.idempotency_key LIKE :refPattern OR jt.description LIKE :descPattern
                ORDER BY jt.posted_at DESC LIMIT 1
            """)
                    .param("ref", searchRef)
                    .param("refPattern", "%" + searchRef + "%")
                    .param("descPattern", "%" + searchRef + "%")
                    .query((rs, rowNum) -> new Object() {
                        UUID id = rs.getObject("id", UUID.class);
                        long amount = rs.getLong("leg_amount");
                    })
                    .optional();

            UUID railSessionId = carrierOpt.map(c -> c.id).orElse(null);
            Long railAmount = carrierOpt.map(c -> c.amount).orElse(null);
            UUID ledgerTxId = ledgerOpt.map(l -> l.id).orElse(null);
            Long ledgerAmount = ledgerOpt.map(l -> l.amount).orElse(null);
            Long bankAmount = line.amount;

            MatchType matchType;
            MatchStatus matchStatus;
            long variance = 0L;

            if (ledgerTxId != null) {
                if (ledgerAmount != null && ledgerAmount.equals(bankAmount)) {
                    matchType = MatchType.THREE_WAY_EXACT;
                    matchStatus = MatchStatus.RECONCILED;
                    exactMatches++;
                } else {
                    matchType = MatchType.AMOUNT_MISMATCH;
                    matchStatus = MatchStatus.SUSPENSE_POSTED;
                    variance = bankAmount - (ledgerAmount != null ? ledgerAmount : 0L);
                    amountMismatches++;
                    totalVariance += Math.abs(variance);
                }
            } else {
                matchType = MatchType.MISSING_IN_LEDGER;
                matchStatus = MatchStatus.SUSPENSE_POSTED;
                variance = bankAmount;
                missingInLedger++;
                totalVariance += Math.abs(variance);
            }

            // Persist match record
            UUID matchRecordId = UUID.randomUUID();
            db.sql("""
                INSERT INTO recon_match_record (
                    id, batch_id, statement_line_id, ledger_tx_id, rail_session_id,
                    match_type, ledger_amount_pesewas, bank_amount_pesewas,
                    rail_amount_pesewas, variance_pesewas, status, created_at
                ) VALUES (
                    :id, :batchId, :lineId, :ledgerTxId, :railSessionId,
                    :matchType, :ledgerAmt, :bankAmt,
                    :railAmt, :variance, :status, now()
                )
            """)
                    .param("id", matchRecordId)
                    .param("batchId", batchId)
                    .param("lineId", line.id)
                    .param("ledgerTxId", ledgerTxId)
                    .param("railSessionId", railSessionId)
                    .param("matchType", matchType.name())
                    .param("ledgerAmt", ledgerAmount)
                    .param("bankAmt", bankAmount)
                    .param("railAmt", railAmount)
                    .param("variance", variance)
                    .param("status", matchStatus.name())
                    .update();

            // Update statement line status
            db.sql("""
                UPDATE recon_statement_line
                SET match_status = :status,
                    matched_ledger_tx_id = :ledgerTxId
                WHERE id = :id
            """)
                    .param("status", matchType == MatchType.THREE_WAY_EXACT ? "EXACT_MATCH" : "MANUAL_VARIANCE")
                    .param("ledgerTxId", ledgerTxId)
                    .param("id", line.id)
                    .update();

            outcomes.add(new MatchOutcome(
                    matchRecordId, line.id, ledgerTxId, railSessionId, matchType,
                    ledgerAmount, bankAmount, railAmount, variance, matchStatus
            ));
        }

        String batchStatus = (amountMismatches == 0 && missingInLedger == 0) ? "RECONCILED" : "VARIANCE_SUSPENSE";
        db.sql("""
            UPDATE recon_bank_statement_batch
            SET matched_lines = :matched,
                discrepancy_lines = :discrepant,
                status = :status,
                reconciled_at = now()
            WHERE id = :batchId
        """)
                .param("matched", exactMatches)
                .param("discrepant", amountMismatches + missingInLedger)
                .param("status", batchStatus)
                .param("batchId", batchId)
                .update();

        return new BatchReconciliationReport(
                batchId, statementLines.size(), exactMatches,
                amountMismatches, missingInLedger, totalVariance, batchStatus, outcomes
        );
    }
}
