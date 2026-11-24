package com.elepay.reconciliation;

import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    private final Ledger ledger;

    public TenWayClearingReconciliationEngine(JdbcClient db, ReconSuspenseService suspenseService) {
        this(db, suspenseService, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TenWayClearingReconciliationEngine(
            JdbcClient db,
            ReconSuspenseService suspenseService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Autowired(required = false) Ledger ledger
    ) {
        this.db = db;
        this.suspenseService = suspenseService;
        this.jdbcTemplate = jdbcTemplate;
        this.ledger = ledger;
    }

    private record StmtLine(UUID id, int lineNum, String dcMark, long amount, LocalDate valueDate,
                            String bankRef, String custRef, String matchStatus, String searchRef) {}
    private record CarrierRow(UUID id, long amount, String sessionType) {}
    private record LedgerRow(UUID id, long amount, String kind) {}

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

    private static boolean isCreditMark(String dcMark) {
        return "C".equalsIgnoreCase(dcMark) || "RD".equalsIgnoreCase(dcMark);
    }

    private static boolean isDirectionCompatible(String dcMark, String carrierSessionType, String ledgerKind) {
        boolean credit = isCreditMark(dcMark);
        if (carrierSessionType != null) {
            String st = carrierSessionType.toUpperCase(Locale.ROOT);
            if (credit && (st.contains("DISBURSEMENT") || st.contains("PAYOUT"))) {
                return false;
            }
            if (!credit && st.contains("COLLECTION")) {
                return false;
            }
        }
        if (ledgerKind != null) {
            String k = ledgerKind.toUpperCase(Locale.ROOT);
            if (credit && (k.contains("PAYOUT") || k.contains("DISBURSEMENT") || k.contains("WITHDRAWAL"))) {
                return false;
            }
            if (!credit && (k.equals("PAYMENT") || k.equals("SPLIT_PAYMENT") || k.contains("COLLECTION") || k.contains("CHECKOUT") || k.contains("DEPOSIT"))) {
                return false;
            }
        }
        return true;
    }

    @Transactional
    public BatchReconciliationReport reconcileBatch(UUID batchId) {
        // Lock batch row FOR UPDATE and enforce INGESTED state
        var batchRow = db.sql("""
            SELECT bank_code, currency, status, statement_date
            FROM recon_bank_statement_batch
            WHERE id = :batchId
            FOR UPDATE
        """)
                .param("batchId", batchId)
                .query((rs, rowNum) -> new Object[] {
                        rs.getString("bank_code"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getObject("statement_date", LocalDate.class)
                })
                .optional()
                .orElseThrow(() -> ApiException.notFound("Statement batch not found: " + batchId));

        String bankCode = batchRow[0] != null ? (String) batchRow[0] : "GENERAL";
        String currency = batchRow[1] != null ? (String) batchRow[1] : "GHS";
        String currentStatus = (String) batchRow[2];
        LocalDate statementDate = batchRow[3] != null ? (LocalDate) batchRow[3] : LocalDate.now(ZoneOffset.UTC);

        if (!"INGESTED".equalsIgnoreCase(currentStatus)) {
            throw ApiException.conflict("batch_already_reconciled",
                    "Statement batch " + batchId + " has already been reconciled (status: " + currentStatus + ")");
        }

        // Retrieve all lines for this statement batch
        List<StmtLine> statementLines = db.sql("""
            SELECT id, line_number, debit_credit_mark, amount_pesewas, value_date,
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
                            rs.getObject("value_date", LocalDate.class),
                            bRef,
                            cRef,
                            rs.getString("match_status"),
                            extractSearchRef(bRef, cRef)
                    );
                })
                .list();

        // Bulk hash-join pre-fetch for direct_carrier_session and journal_transaction
        Set<String> refSet = new LinkedHashSet<>(statementLines.size());
        for (StmtLine line : statementLines) {
            String sr = line.searchRef();
            if (sr != null && !sr.isBlank()) {
                refSet.add(sr);
            }
        }
        List<String> allRefs = new ArrayList<>(refSet);

        Map<String, CarrierRow> carrierMap = new HashMap<>(allRefs.size());
        Map<String, LedgerRow> ledgerMap = new HashMap<>(allRefs.size());

        if (jdbcTemplate != null && !allRefs.isEmpty()) {
            int arrayChunkSize = 50_000;
            for (int start = 0; start < allRefs.size(); start += arrayChunkSize) {
                String[] chunkArr = allRefs.subList(start, Math.min(start + arrayChunkSize, allRefs.size()))
                        .toArray(String[]::new);
                jdbcTemplate.execute((java.sql.Connection conn) -> {
                    java.sql.Array sqlRefs = conn.createArrayOf("text", chunkArr);
                    try (var ps = conn.prepareStatement("""
                            WITH refs AS (
                                SELECT unnest(?::text[]) AS ref
                            )
                            SELECT d.id, d.reference_id, d.external_transaction_id, d.amount_pesewas, d.session_type
                            FROM direct_carrier_session d
                            JOIN refs r ON d.reference_id = r.ref
                            UNION ALL
                            SELECT d.id, d.reference_id, d.external_transaction_id, d.amount_pesewas, d.session_type
                            FROM direct_carrier_session d
                            JOIN refs r ON d.external_transaction_id = r.ref
                            """)) {
                        ps.setArray(1, sqlRefs);
                        ps.setFetchSize(5000);
                        try (var rs = ps.executeQuery()) {
                            while (rs.next()) {
                                CarrierRow cr = new CarrierRow(
                                        rs.getObject(1, UUID.class),
                                        rs.getLong(4),
                                        rs.getString(5));
                                String r1 = rs.getString(2);
                                String r2 = rs.getString(3);
                                if (r1 != null) carrierMap.putIfAbsent(r1, cr);
                                if (r2 != null) carrierMap.putIfAbsent(r2, cr);
                            }
                        }
                    }

                    try (var ps = conn.prepareStatement("""
                            WITH refs AS (
                                SELECT unnest(?::text[]) AS ref
                            ),
                            matched_jt AS (
                                SELECT jt.id, jt.idempotency_key, jt.description, jt.kind
                                FROM journal_transaction jt
                                JOIN refs r ON jt.idempotency_key = r.ref
                                UNION
                                SELECT jt.id, jt.idempotency_key, jt.description, jt.kind
                                FROM journal_transaction jt
                                JOIN refs r ON jt.description = r.ref
                            )
                            SELECT m.id, m.idempotency_key, m.description, COALESCE(SUM(je.amount), 0) AS total_amount, m.kind
                            FROM matched_jt m
                            JOIN journal_entry je ON je.transaction_id = m.id AND je.amount > 0 AND je.currency = ?
                            GROUP BY m.id, m.idempotency_key, m.description, m.kind
                            """)) {
                        ps.setArray(1, sqlRefs);
                        ps.setString(2, currency);
                        ps.setFetchSize(5000);
                        try (var rs = ps.executeQuery()) {
                            while (rs.next()) {
                                LedgerRow lr = new LedgerRow(
                                        rs.getObject(1, UUID.class),
                                        rs.getLong(4),
                                        rs.getString(5));
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
                            }
                        }
                    }
                    return null;
                });
            }
        } else {
            int chunkSize = 5000;
            for (int start = 0; start < allRefs.size(); start += chunkSize) {
                List<String> chunk = allRefs.subList(start, Math.min(start + chunkSize, allRefs.size()));
                db.sql("""
                    SELECT id, reference_id, external_transaction_id, amount_pesewas, session_type
                    FROM direct_carrier_session
                    WHERE reference_id IN (:refs) OR external_transaction_id IN (:refs)
                """)
                        .param("refs", chunk)
                        .query((rs, i) -> {
                            CarrierRow cr = new CarrierRow(rs.getObject(1, UUID.class), rs.getLong(4), rs.getString(5));
                            String r1 = rs.getString(2);
                            String r2 = rs.getString(3);
                            if (r1 != null) carrierMap.putIfAbsent(r1, cr);
                            if (r2 != null) carrierMap.putIfAbsent(r2, cr);
                            return cr;
                        }).list();

                db.sql("""
                    SELECT jt.id, jt.idempotency_key, jt.description, COALESCE(SUM(je.amount), 0) AS total_amount, jt.kind
                    FROM journal_transaction jt
                    JOIN journal_entry je ON je.transaction_id = jt.id AND je.amount > 0 AND je.currency = :currency
                    WHERE jt.idempotency_key IN (:refs) OR jt.description IN (:refs)
                    GROUP BY jt.id, jt.idempotency_key, jt.description, jt.kind
                """)
                        .param("currency", currency)
                        .param("refs", chunk)
                        .query((rs, i) -> {
                            LedgerRow lr = new LedgerRow(rs.getObject(1, UUID.class), rs.getLong(4), rs.getString(5));
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
        }

        List<MatchOutcome> outcomes = new ArrayList<>(statementLines.size());
        Set<UUID> matchedLedgerTxIds = new HashSet<>(statementLines.size());
        int exactMatches = 0;
        int amountMismatches = 0;
        int missingInLedger = 0;
        int missingInBank = 0;
        long totalVariance = 0L;
        boolean allowFallbackLike = statementLines.size() <= 200;
        LocalDate minDate = null;
        LocalDate maxDate = null;

        for (StmtLine line : statementLines) {
            LocalDate vd = line.valueDate();
            if (vd != null) {
                if (minDate == null || vd.isBefore(minDate)) minDate = vd;
                if (maxDate == null || vd.isAfter(maxDate)) maxDate = vd;
            }
            String searchRef = line.searchRef();
            UUID railSessionId = null;
            Long railAmount = null;
            String carrierSessionType = null;
            UUID ledgerTxId = null;
            Long ledgerAmount = null;
            String ledgerKind = null;

            if (searchRef != null && !searchRef.isBlank()) {
                CarrierRow cr = carrierMap.get(searchRef);
                if (cr != null) {
                    railSessionId = cr.id();
                    railAmount = cr.amount();
                    carrierSessionType = cr.sessionType();
                }

                LedgerRow lr = ledgerMap.get(searchRef);
                if (lr == null && allowFallbackLike) {
                    var ledgerOpt = db.sql("""
                        SELECT jt.id, COALESCE(SUM(je.amount), 0) AS total_amount, jt.kind
                        FROM journal_transaction jt
                        JOIN journal_entry je ON je.transaction_id = jt.id AND je.amount > 0 AND je.currency = :currency
                        WHERE jt.idempotency_key = :ref OR jt.idempotency_key LIKE :refPattern OR jt.description LIKE :descPattern
                        GROUP BY jt.id, jt.posted_at, jt.kind
                        ORDER BY jt.posted_at DESC LIMIT 1
                    """)
                            .param("currency", currency)
                            .param("ref", searchRef)
                            .param("refPattern", "%" + searchRef + "%")
                            .param("descPattern", "%" + searchRef + "%")
                            .query((rs, rowNum) -> new LedgerRow(
                                    rs.getObject("id", UUID.class),
                                    rs.getLong("total_amount"),
                                    rs.getString("kind")))
                            .optional();
                    if (ledgerOpt.isPresent()) {
                        lr = ledgerOpt.get();
                        ledgerMap.put(searchRef, lr);
                    }
                }
                if (lr != null) {
                    ledgerTxId = lr.id();
                    ledgerAmount = lr.amount();
                    ledgerKind = lr.kind();
                    matchedLedgerTxIds.add(ledgerTxId);
                }
            }

            Long bankAmount = line.amount();
            MatchType matchType;
            MatchStatus matchStatus;
            long variance = 0L;

            if (ledgerTxId != null) {
                boolean directionOk = isDirectionCompatible(line.dcMark(), carrierSessionType, ledgerKind);
                if (directionOk && ledgerAmount != null && ledgerAmount.equals(bankAmount)) {
                    if (railSessionId != null && railAmount != null && railAmount.equals(bankAmount)) {
                        matchType = MatchType.THREE_WAY_EXACT;
                        matchStatus = MatchStatus.RECONCILED;
                        exactMatches++;
                    } else if (railSessionId == null) {
                        matchType = MatchType.TWO_WAY_INTERNAL;
                        matchStatus = MatchStatus.RECONCILED;
                        exactMatches++;
                    } else {
                        matchType = MatchType.AMOUNT_MISMATCH;
                        matchStatus = MatchStatus.SUSPENSE_POSTED;
                        variance = bankAmount - (railAmount != null ? railAmount : 0L);
                        amountMismatches++;
                        totalVariance += Math.abs(variance);
                    }
                } else {
                    matchType = MatchType.AMOUNT_MISMATCH;
                    matchStatus = MatchStatus.SUSPENSE_POSTED;
                    if (!directionOk) {
                        long signedBank = isCreditMark(line.dcMark()) ? bankAmount : -bankAmount;
                        long signedLedger = isCreditMark(line.dcMark()) ? -(ledgerAmount != null ? ledgerAmount : 0L)
                                : (ledgerAmount != null ? ledgerAmount : 0L);
                        variance = signedBank - signedLedger;
                    } else {
                        variance = bankAmount - (ledgerAmount != null ? ledgerAmount : 0L);
                    }
                    amountMismatches++;
                    totalVariance += Math.abs(variance);
                }
            } else {
                matchType = MatchType.MISSING_IN_LEDGER;
                matchStatus = MatchStatus.SUSPENSE_POSTED;
                variance = isCreditMark(line.dcMark()) ? bankAmount : -bankAmount;
                missingInLedger++;
                totalVariance += Math.abs(variance);
            }

            UUID matchRecordId = UUID.randomUUID();
            outcomes.add(new MatchOutcome(
                    matchRecordId, line.id(), ledgerTxId, railSessionId, matchType,
                    ledgerAmount, bankAmount, railAmount, variance, matchStatus
            ));
        }

        // Emit MISSING_IN_BANK for unmatched internal transactions in the statement period
        if (minDate == null) minDate = statementDate;
        if (maxDate == null) maxDate = statementDate;
        OffsetDateTime periodStart = minDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime periodEnd = maxDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();

        if (jdbcTemplate != null && !matchedLedgerTxIds.isEmpty()) {
            UUID[] matchedArr = matchedLedgerTxIds.toArray(UUID[]::new);
            List<MatchOutcome> missingInBankOutcomes = jdbcTemplate.execute((java.sql.Connection conn) -> {
                List<MatchOutcome> list = new ArrayList<>();
                try (var ps = conn.prepareStatement("""
                        WITH period_jt AS (
                            SELECT jt.id
                            FROM journal_transaction jt
                            WHERE jt.posted_at >= ? AND jt.posted_at < ?
                              AND jt.kind NOT IN ('RECON_VARIANCE_SUSPENSE', 'RECON_VARIANCE_RESOLUTION', 'suspense_item', 'suspense_clearing')
                              AND NOT EXISTS (SELECT 1 FROM recon_match_record rmr WHERE rmr.ledger_tx_id = jt.id)
                            EXCEPT
                            SELECT unnest(?::uuid[])
                        )
                        SELECT p.id, COALESCE(SUM(je.amount), 0) AS total_amount
                        FROM period_jt p
                        JOIN journal_entry je ON je.transaction_id = p.id AND je.amount > 0 AND je.currency = ?
                        GROUP BY p.id
                        """)) {
                    ps.setObject(1, periodStart);
                    ps.setObject(2, periodEnd);
                    ps.setArray(3, conn.createArrayOf("uuid", matchedArr));
                    ps.setString(4, currency);
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) {
                            UUID txId = rs.getObject(1, UUID.class);
                            long amt = rs.getLong(2);
                            list.add(new MatchOutcome(
                                    UUID.randomUUID(), null, txId, null, MatchType.MISSING_IN_BANK,
                                    amt, 0L, null, -amt, MatchStatus.SUSPENSE_POSTED
                            ));
                        }
                    }
                }
                return list;
            });
            if (missingInBankOutcomes != null && !missingInBankOutcomes.isEmpty()) {
                for (MatchOutcome mib : missingInBankOutcomes) {
                    missingInBank++;
                    totalVariance += Math.abs(mib.ledgerAmountPesewas());
                    outcomes.add(mib);
                }
            }
        } else {
            var internalPeriodTxs = db.sql("""
                SELECT jt.id, COALESCE(SUM(je.amount), 0) AS total_amount
                FROM journal_transaction jt
                JOIN journal_entry je ON je.transaction_id = jt.id AND je.amount > 0 AND je.currency = :currency
                WHERE jt.posted_at >= :periodStart AND jt.posted_at < :periodEnd
                  AND jt.kind NOT IN ('RECON_VARIANCE_SUSPENSE', 'RECON_VARIANCE_RESOLUTION', 'suspense_item', 'suspense_clearing')
                  AND NOT EXISTS (SELECT 1 FROM recon_match_record rmr WHERE rmr.ledger_tx_id = jt.id)
                GROUP BY jt.id
            """)
                    .param("currency", currency)
                    .param("periodStart", periodStart)
                    .param("periodEnd", periodEnd)
                    .query((rs, i) -> new Object[] { rs.getObject(1, UUID.class), rs.getLong(2) })
                    .list();

            for (Object[] internalTx : internalPeriodTxs) {
                UUID txId = (UUID) internalTx[0];
                long amt = (Long) internalTx[1];
                if (!matchedLedgerTxIds.contains(txId)) {
                    missingInBank++;
                    totalVariance += Math.abs(amt);
                    outcomes.add(new MatchOutcome(
                            UUID.randomUUID(), null, txId, null, MatchType.MISSING_IN_BANK,
                            amt, 0L, null, -amt, MatchStatus.SUSPENSE_POSTED
                    ));
                }
            }
        }

        if (jdbcTemplate != null && outcomes.size() > 1) {
            int writeChunkSize = 25_000;
            for (int start = 0; start < outcomes.size(); start += writeChunkSize) {
                List<MatchOutcome> chunk = outcomes.subList(start, Math.min(start + writeChunkSize, outcomes.size()));
                int n = chunk.size();
                UUID[] ids = new UUID[n];
                UUID[] lineIds = new UUID[n];
                UUID[] ledgerIds = new UUID[n];
                UUID[] railIds = new UUID[n];
                String[] matchTypes = new String[n];
                Long[] ledgerAmts = new Long[n];
                Long[] bankAmts = new Long[n];
                Long[] railAmts = new Long[n];
                Long[] variances = new Long[n];
                String[] statuses = new String[n];

                int lineCount = 0;
                for (int i = 0; i < n; i++) {
                    MatchOutcome o = chunk.get(i);
                    ids[i] = o.matchRecordId();
                    lineIds[i] = o.statementLineId();
                    if (o.statementLineId() != null) lineCount++;
                    ledgerIds[i] = o.ledgerTxId();
                    railIds[i] = o.railSessionId();
                    matchTypes[i] = o.matchType().name();
                    ledgerAmts[i] = o.ledgerAmountPesewas();
                    bankAmts[i] = o.bankAmountPesewas();
                    railAmts[i] = o.railAmountPesewas();
                    variances[i] = o.variancePesewas();
                    statuses[i] = o.status().name();
                }

                UUID[] updLineIds = lineCount > 0 ? new UUID[lineCount] : null;
                String[] updStatuses = lineCount > 0 ? new String[lineCount] : null;
                UUID[] updLedgerIds = lineCount > 0 ? new UUID[lineCount] : null;
                if (lineCount > 0) {
                    int idx = 0;
                    for (int i = 0; i < n; i++) {
                        MatchOutcome o = chunk.get(i);
                        if (o.statementLineId() != null) {
                            updLineIds[idx] = o.statementLineId();
                            updStatuses[idx] = o.status() == MatchStatus.RECONCILED ? "EXACT_MATCH" : "MANUAL_VARIANCE";
                            updLedgerIds[idx] = o.ledgerTxId();
                            idx++;
                        }
                    }
                }

                jdbcTemplate.execute((java.sql.Connection conn) -> {
                    try (var ps = conn.prepareStatement("""
                            INSERT INTO recon_match_record (
                                id, batch_id, statement_line_id, ledger_tx_id, rail_session_id,
                                match_type, ledger_amount_pesewas, bank_amount_pesewas,
                                rail_amount_pesewas, variance_pesewas, status, created_at
                            )
                            SELECT u.id, ?, u.statement_line_id, u.ledger_tx_id, u.rail_session_id,
                                   u.match_type, u.ledger_amount_pesewas, u.bank_amount_pesewas,
                                   u.rail_amount_pesewas, u.variance_pesewas, u.status, now()
                            FROM unnest(
                                ?::uuid[], ?::uuid[], ?::uuid[], ?::uuid[],
                                ?::varchar[], ?::bigint[], ?::bigint[],
                                ?::bigint[], ?::bigint[], ?::varchar[]
                            ) AS u(
                                id, statement_line_id, ledger_tx_id, rail_session_id,
                                match_type, ledger_amount_pesewas, bank_amount_pesewas,
                                rail_amount_pesewas, variance_pesewas, status
                            )
                            """)) {
                        ps.setObject(1, batchId);
                        ps.setArray(2, conn.createArrayOf("uuid", ids));
                        ps.setArray(3, conn.createArrayOf("uuid", lineIds));
                        ps.setArray(4, conn.createArrayOf("uuid", ledgerIds));
                        ps.setArray(5, conn.createArrayOf("uuid", railIds));
                        ps.setArray(6, conn.createArrayOf("varchar", matchTypes));
                        ps.setArray(7, conn.createArrayOf("bigint", ledgerAmts));
                        ps.setArray(8, conn.createArrayOf("bigint", bankAmts));
                        ps.setArray(9, conn.createArrayOf("bigint", railAmts));
                        ps.setArray(10, conn.createArrayOf("bigint", variances));
                        ps.setArray(11, conn.createArrayOf("varchar", statuses));
                        ps.executeUpdate();
                    }

                    if (updLineIds != null) {
                        try (var ps = conn.prepareStatement("""
                                UPDATE recon_statement_line AS r
                                SET match_status = u.match_status,
                                    matched_ledger_tx_id = u.matched_ledger_tx_id
                                FROM unnest(?::uuid[], ?::varchar[], ?::uuid[]) AS u(id, match_status, matched_ledger_tx_id)
                                WHERE r.id = u.id
                                """)) {
                            ps.setArray(1, conn.createArrayOf("uuid", updLineIds));
                            ps.setArray(2, conn.createArrayOf("varchar", updStatuses));
                            ps.setArray(3, conn.createArrayOf("uuid", updLedgerIds));
                            ps.executeUpdate();
                        }
                    }
                    return null;
                });
            }
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

                if (o.statementLineId() != null) {
                    db.sql("""
                        UPDATE recon_statement_line
                        SET match_status = :status,
                            matched_ledger_tx_id = :ledgerTxId
                        WHERE id = :id
                    """)
                            .param("status", o.status() == MatchStatus.RECONCILED ? "EXACT_MATCH" : "MANUAL_VARIANCE")
                            .param("ledgerTxId", o.ledgerTxId())
                            .param("id", o.statementLineId())
                            .update();
                }
            }
        }

        List<MatchOutcome> suspenseOutcomes = new ArrayList<>();
        for (MatchOutcome o : outcomes) {
            if (o.status() == MatchStatus.SUSPENSE_POSTED && o.variancePesewas() != 0L) {
                suspenseOutcomes.add(o);
            }
        }

        if (!suspenseOutcomes.isEmpty()) {
            if (jdbcTemplate == null || ledger == null || suspenseOutcomes.size() <= 10) {
                for (MatchOutcome o : suspenseOutcomes) {
                    suspenseService.postSuspenseVariance(o.matchRecordId(), bankCode, o.variancePesewas(), currency);
                }
            } else {
                MatchOutcome first = suspenseOutcomes.get(0);
                suspenseService.postSuspenseVariance(first.matchRecordId(), bankCode, first.variancePesewas(), currency);

                String curr = (currency != null && !currency.isBlank()) ? currency : "GHS";
                String bank = (bankCode != null && !bankCode.isBlank()) ? bankCode.toUpperCase(Locale.ROOT) : "GENERAL";
                String suspenseAccount = "system:suspense:recon:" + bank + ":" + curr;
                String clearingAccount = "system:clearing:" + bank.toLowerCase(Locale.ROOT) + ":" + curr;

                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                List<MatchOutcome> remaining = suspenseOutcomes.subList(1, suspenseOutcomes.size());
                long netRemainingVariance = 0L;
                int suspenseChunkSize = 10_000;
                for (int start = 0; start < remaining.size(); start += suspenseChunkSize) {
                    List<MatchOutcome> chunk = remaining.subList(start, Math.min(start + suspenseChunkSize, remaining.size()));
                    int n = chunk.size();
                    UUID[] suspenseIds = new UUID[n];
                    UUID[] matchRecordIds = new UUID[n];
                    Long[] variances = new Long[n];

                    for (int i = 0; i < n; i++) {
                        MatchOutcome o = chunk.get(i);
                        suspenseIds[i] = UUID.randomUUID();
                        matchRecordIds[i] = o.matchRecordId();
                        variances[i] = o.variancePesewas();
                        netRemainingVariance += o.variancePesewas();
                    }

                    jdbcTemplate.execute((java.sql.Connection conn) -> {
                        try (var ps = conn.prepareStatement("""
                                INSERT INTO recon_suspense_ledger (
                                    id, match_record_id, suspense_account_code, variance_pesewas,
                                    currency, aging_bucket, status, created_at, updated_at
                                )
                                SELECT u.id, u.match_id, ?, u.variance, ?, '0_24H', 'OPEN', ?, ?
                                FROM unnest(?::uuid[], ?::uuid[], ?::bigint[]) AS u(id, match_id, variance)
                                """)) {
                            ps.setString(1, suspenseAccount);
                            ps.setString(2, curr);
                            ps.setObject(3, now);
                            ps.setObject(4, now);
                            ps.setArray(5, conn.createArrayOf("uuid", suspenseIds));
                            ps.setArray(6, conn.createArrayOf("uuid", matchRecordIds));
                            ps.setArray(7, conn.createArrayOf("bigint", variances));
                            ps.executeUpdate();
                        }
                        return null;
                    });
                }

                if (netRemainingVariance != 0L) {
                    long absVariance = Math.abs(netRemainingVariance);
                    var legs = (netRemainingVariance > 0)
                            ? List.of(Ledger.Leg.debit(suspenseAccount, absVariance), Ledger.Leg.credit(clearingAccount, absVariance))
                            : List.of(Ledger.Leg.debit(clearingAccount, absVariance), Ledger.Leg.credit(suspenseAccount, absVariance));

                    ledger.post(Ledger.Posting.of(
                            "recon_suspense",
                            "SUSPENSE-BATCH-" + batchId,
                            "RECON_VARIANCE_SUSPENSE",
                            legs
                    ).describedAs("Aggregate reconciliation variance suspense posting for batch " + batchId
                            + " (" + remaining.size() + " items)"));
                }
            }
        }

        int totalDiscrepancies = amountMismatches + missingInLedger + missingInBank;
        String batchStatus = totalDiscrepancies == 0 ? "RECONCILED" : "VARIANCE_SUSPENSE";
        db.sql("""
            UPDATE recon_bank_statement_batch
            SET matched_lines = :matched,
                discrepancy_lines = :discrepant,
                status = :status,
                reconciled_at = now()
            WHERE id = :batchId
        """)
                .param("matched", exactMatches)
                .param("discrepant", totalDiscrepancies)
                .param("status", batchStatus)
                .param("batchId", batchId)
                .update();

        return new BatchReconciliationReport(
                batchId, statementLines.size(), exactMatches,
                amountMismatches, missingInLedger, totalVariance, batchStatus, outcomes
        );
    }
}
