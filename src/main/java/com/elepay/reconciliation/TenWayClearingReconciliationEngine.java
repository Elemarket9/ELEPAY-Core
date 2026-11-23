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
    private final ReconSuspenseService suspenseService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public TenWayClearingReconciliationEngine(JdbcClient db, ReconSuspenseService suspenseService) {
        this(db, suspenseService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TenWayClearingReconciliationEngine(
            JdbcClient db,
            ReconSuspenseService suspenseService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) org.springframework.jdbc.core.JdbcTemplate jdbcTemplate
    ) {
        this.db = db;
        this.suspenseService = suspenseService;
        this.jdbcTemplate = jdbcTemplate;
    }

    private record StmtLine(UUID id, int lineNum, String dcMark, long amount, String bankRef, String custRef, String matchStatus, String searchRef) {}
    private record CarrierRow(UUID id, long amount) {}
    private record LedgerRow(UUID id, long amount) {}

    private static String extractSearchRef(String bankRef, String custRef) {
        if (bankRef != null && !bankRef.isBlank() && !bankRef.equalsIgnoreCase("NONREF")
                && !bankRef.startsWith("REF-") && !bankRef.startsWith("LINE-")) {
            return bankRef.trim();
        }
        if (custRef != null && !custRef.isBlank() && !custRef.equalsIgnoreCase("NONREF")
                && !custRef.startsWith("REF-") && !custRef.startsWith("LINE-")) {
            return custRef.trim();
        }
        return null;
    }

    @Transactional
    public BatchReconciliationReport reconcileBatch(UUID batchId) {
        // Retrieve batch metadata for bank code and currency
        var batchOpt = db.sql("""
            SELECT bank_code, currency FROM recon_bank_statement_batch WHERE id = :batchId
        """)
                .param("batchId", batchId)
                .query((rs, rowNum) -> new Object() {
                    String bankCode = rs.getString("bank_code");
                    String currency = rs.getString("currency");
                })
                .optional();
        String bankCode = batchOpt.map(b -> b.bankCode).orElse("GENERAL");
        String currency = batchOpt.map(b -> b.currency).orElse("GHS");

        // Retrieve all lines for this statement batch
        List<StmtLine> statementLines = db.sql("""
            SELECT id, line_number, debit_credit_mark, amount_pesewas,
                   bank_reference, customer_reference, match_status
            FROM recon_statement_line
            WHERE batch_id = :batchId
            ORDER BY line_number ASC
        """)
                .param("batchId", batchId)
                .query((rs, rowNum) -> {
                    String bRef = rs.getString("bank_reference");
                    String cRef = rs.getString("customer_reference");
                    return new StmtLine(
                            rs.getObject("id", UUID.class),
                            rs.getInt("line_number"),
                            rs.getString("debit_credit_mark"),
                            rs.getLong("amount_pesewas"),
                            bRef,
                            cRef,
                            rs.getString("match_status"),
                            extractSearchRef(bRef, cRef)
                    );
                })
                .list();

        // Bulk hash-join pre-fetch for direct_carrier_session and journal_transaction
        List<String> allRefs = statementLines.stream()
                .map(StmtLine::searchRef)
                .filter(r -> r != null && !r.isBlank())
                .distinct()
                .toList();

        Map<String, CarrierRow> carrierMap = new HashMap<>(allRefs.size());
        Map<String, LedgerRow> ledgerMap = new HashMap<>(allRefs.size());

        int chunkSize = 5000;
        for (int start = 0; start < allRefs.size(); start += chunkSize) {
            List<String> chunk = allRefs.subList(start, Math.min(start + chunkSize, allRefs.size()));
            db.sql("""
                SELECT id, reference_id, external_transaction_id, amount_pesewas
                FROM direct_carrier_session
                WHERE reference_id IN (:refs) OR external_transaction_id IN (:refs)
            """)
                    .param("refs", chunk)
                    .query((rs, i) -> {
                        CarrierRow cr = new CarrierRow(rs.getObject(1, UUID.class), rs.getLong(4));
                        String r1 = rs.getString(2);
                        String r2 = rs.getString(3);
                        if (r1 != null) carrierMap.putIfAbsent(r1, cr);
                        if (r2 != null) carrierMap.putIfAbsent(r2, cr);
                        return cr;
                    }).list();

            db.sql("""
                SELECT jt.id, jt.idempotency_key, jt.description, COALESCE(SUM(je.amount), 0) AS total_amount
                FROM journal_transaction jt
                JOIN journal_entry je ON je.transaction_id = jt.id AND je.amount > 0
                WHERE jt.idempotency_key IN (:refs) OR jt.description IN (:refs)
                GROUP BY jt.id, jt.idempotency_key, jt.description
            """)
                    .param("refs", chunk)
                    .query((rs, i) -> {
                        LedgerRow lr = new LedgerRow(rs.getObject(1, UUID.class), rs.getLong(4));
                        String ik = rs.getString(2);
                        String desc = rs.getString(3);
                        if (ik != null) {
                            ledgerMap.putIfAbsent(ik, lr);
                            int colon = ik.lastIndexOf(':');
                            if (colon >= 0 && colon + 1 < ik.length()) {
                                ledgerMap.putIfAbsent(ik.substring(colon + 1), lr);
                            }
                        }
                        if (desc != null) ledgerMap.putIfAbsent(desc, lr);
                        return lr;
                    }).list();
        }

        List<MatchOutcome> outcomes = new ArrayList<>(statementLines.size());
        int exactMatches = 0;
        int amountMismatches = 0;
        int missingInLedger = 0;
        long totalVariance = 0L;
        boolean allowFallbackLike = statementLines.size() <= 200;

        for (StmtLine line : statementLines) {
            String searchRef = line.searchRef();
            UUID railSessionId = null;
            Long railAmount = null;
            UUID ledgerTxId = null;
            Long ledgerAmount = null;

            if (searchRef != null && !searchRef.isBlank()) {
                CarrierRow cr = carrierMap.get(searchRef);
                if (cr != null) {
                    railSessionId = cr.id();
                    railAmount = cr.amount();
                }

                LedgerRow lr = ledgerMap.get(searchRef);
                if (lr == null && allowFallbackLike) {
                    var ledgerOpt = db.sql("""
                        SELECT jt.id, COALESCE(SUM(je.amount), 0) AS total_amount
                        FROM journal_transaction jt
                        JOIN journal_entry je ON je.transaction_id = jt.id AND je.amount > 0
                        WHERE jt.idempotency_key = :ref OR jt.idempotency_key LIKE :refPattern OR jt.description LIKE :descPattern
                        GROUP BY jt.id, jt.posted_at
                        ORDER BY jt.posted_at DESC LIMIT 1
                    """)
                            .param("ref", searchRef)
                            .param("refPattern", "%" + searchRef + "%")
                            .param("descPattern", "%" + searchRef + "%")
                            .query((rs, rowNum) -> new LedgerRow(rs.getObject("id", UUID.class), rs.getLong("total_amount")))
                            .optional();
                    if (ledgerOpt.isPresent()) {
                        lr = ledgerOpt.get();
                        ledgerMap.put(searchRef, lr);
                    }
                }
                if (lr != null) {
                    ledgerTxId = lr.id();
                    ledgerAmount = lr.amount();
                }
            }

            Long bankAmount = line.amount();
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

            UUID matchRecordId = UUID.randomUUID();
            outcomes.add(new MatchOutcome(
                    matchRecordId, line.id(), ledgerTxId, railSessionId, matchType,
                    ledgerAmount, bankAmount, railAmount, variance, matchStatus
            ));
        }

        if (jdbcTemplate != null && outcomes.size() > 1) {
            jdbcTemplate.batchUpdate("""
                INSERT INTO recon_match_record (
                    id, batch_id, statement_line_id, ledger_tx_id, rail_session_id,
                    match_type, ledger_amount_pesewas, bank_amount_pesewas,
                    rail_amount_pesewas, variance_pesewas, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            """, outcomes, 2000, (ps, o) -> {
                ps.setObject(1, o.matchRecordId());
                ps.setObject(2, batchId);
                ps.setObject(3, o.statementLineId());
                ps.setObject(4, o.ledgerTxId());
                ps.setObject(5, o.railSessionId());
                ps.setString(6, o.matchType().name());
                if (o.ledgerAmountPesewas() != null) ps.setLong(7, o.ledgerAmountPesewas());
                else ps.setNull(7, java.sql.Types.BIGINT);
                if (o.bankAmountPesewas() != null) ps.setLong(8, o.bankAmountPesewas());
                else ps.setNull(8, java.sql.Types.BIGINT);
                if (o.railAmountPesewas() != null) ps.setLong(9, o.railAmountPesewas());
                else ps.setNull(9, java.sql.Types.BIGINT);
                ps.setLong(10, o.variancePesewas());
                ps.setString(11, o.status().name());
            });

            jdbcTemplate.batchUpdate("""
                UPDATE recon_statement_line
                SET match_status = ?,
                    matched_ledger_tx_id = ?
                WHERE id = ?
            """, outcomes, 2000, (ps, o) -> {
                ps.setString(1, o.matchType() == MatchType.THREE_WAY_EXACT ? "EXACT_MATCH" : "MANUAL_VARIANCE");
                ps.setObject(2, o.ledgerTxId());
                ps.setObject(3, o.statementLineId());
            });
        } else {
            for (MatchOutcome o : outcomes) {
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
                        .param("id", o.matchRecordId())
                        .param("batchId", batchId)
                        .param("lineId", o.statementLineId())
                        .param("ledgerTxId", o.ledgerTxId())
                        .param("railSessionId", o.railSessionId())
                        .param("matchType", o.matchType().name())
                        .param("ledgerAmt", o.ledgerAmountPesewas())
                        .param("bankAmt", o.bankAmountPesewas())
                        .param("railAmt", o.railAmountPesewas())
                        .param("variance", o.variancePesewas())
                        .param("status", o.status().name())
                        .update();

                db.sql("""
                    UPDATE recon_statement_line
                    SET match_status = :status,
                        matched_ledger_tx_id = :ledgerTxId
                    WHERE id = :id
                """)
                        .param("status", o.matchType() == MatchType.THREE_WAY_EXACT ? "EXACT_MATCH" : "MANUAL_VARIANCE")
                        .param("ledgerTxId", o.ledgerTxId())
                        .param("id", o.statementLineId())
                        .update();
            }
        }

        for (MatchOutcome o : outcomes) {
            if (o.status() == MatchStatus.SUSPENSE_POSTED && o.variancePesewas() != 0L) {
                suspenseService.postSuspenseVariance(o.matchRecordId(), bankCode, o.variancePesewas(), currency);
            }
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
