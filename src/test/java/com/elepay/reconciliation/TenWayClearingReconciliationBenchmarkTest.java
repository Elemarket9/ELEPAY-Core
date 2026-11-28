package com.elepay.reconciliation;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenWayClearingReconciliationBenchmarkTest extends PostgresTestBase {

    private static final Logger log = LoggerFactory.getLogger(TenWayClearingReconciliationBenchmarkTest.class);

    @Autowired
    private TenWayClearingReconciliationEngine reconEngine;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.elepay.ledger.Ledger ledger;

    @Test
    @DisplayName("Benchmark: 50,000 statement lines reconcile in < 15,000 ms (target < 8,000 ms)")
    void reconcile50kLinesUnderSla() throws Exception {
        int count = 50_000;
        String prefix = "T10W-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase() + "-";
        LocalDate statementDate = LocalDate.of(2018, 1, 1)
                .plusDays(java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 1000));
        java.time.OffsetDateTime postedAt = statementDate.atStartOfDay(ZoneOffset.UTC).plusHours(12).toOffsetDateTime();

        // Seed 2 ledger accounts
        String accDebit = "test:10w:debit:" + prefix + ":GHS";
        String accCredit = "test:10w:credit:" + prefix + ":GHS";
        ledger.openSystem(accDebit, "clearing", "GHS", true);
        ledger.openSystem(accCredit, "clearing", "GHS", true);
        Long debitAccId = jdbcTemplate.queryForObject("SELECT id FROM ledger_account WHERE code = ?", Long.class, accDebit);
        Long creditAccId = jdbcTemplate.queryForObject("SELECT id FROM ledger_account WHERE code = ?", Long.class, accCredit);

        UUID batchId = UUID.randomUUID();
        String sha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest((prefix + batchId).getBytes(StandardCharsets.UTF_8)));

        jdbcTemplate.update("""
                INSERT INTO recon_bank_statement_batch (
                    id, batch_reference, bank_code, account_number, statement_number,
                    currency, opening_balance_pesewas, closing_balance_pesewas,
                    statement_date, total_lines, status, raw_content_sha256
                ) VALUES (?, ?, 'TST', '10100001', '00001/001', 'GHS', 0, 0, ?, ?, 'INGESTED', ?)
                """, batchId, prefix + "BATCH", statementDate, count, sha);

        List<UUID> txIds = new ArrayList<>(count);
        List<UUID> sessionIds = new ArrayList<>(count);
        List<UUID> lineIds = new ArrayList<>(count);
        List<String> refs = new ArrayList<>(count);
        List<Long> amounts = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            txIds.add(UUID.randomUUID());
            sessionIds.add(UUID.randomUUID());
            lineIds.add(UUID.randomUUID());
            refs.add(prefix + i);
            amounts.add(1000L + (i % 500));
        }

        // Bulk seed journal_transaction, journal_entry (2 legs each), direct_carrier_session, recon_statement_line
        jdbcTemplate.execute((java.sql.Connection conn) -> {
            boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                UUID[] txArr = txIds.toArray(UUID[]::new);
                UUID[] sessArr = sessionIds.toArray(UUID[]::new);
                UUID[] lineArr = lineIds.toArray(UUID[]::new);
                String[] refArr = refs.toArray(String[]::new);
                Long[] amtArr = amounts.toArray(Long[]::new);

                try (var ps = conn.prepareStatement("""
                        INSERT INTO journal_transaction (id, idempotency_scope, idempotency_key, request_hash, kind, description, posted_at)
                        SELECT u.id, 'bench_10w', u.ref, u.ref, 'PAYMENT', u.ref, ?
                        FROM unnest(?::uuid[], ?::text[]) AS u(id, ref)
                        """)) {
                    ps.setObject(1, postedAt);
                    ps.setArray(2, conn.createArrayOf("uuid", txArr));
                    ps.setArray(3, conn.createArrayOf("text", refArr));
                    ps.executeUpdate();
                }

                try (var ps = conn.prepareStatement("""
                        INSERT INTO journal_entry (transaction_id, line, account_id, currency, amount)
                        SELECT u.id, 0, ?, 'GHS', -u.amt FROM unnest(?::uuid[], ?::bigint[]) AS u(id, amt)
                        UNION ALL
                        SELECT u.id, 1, ?, 'GHS', u.amt FROM unnest(?::uuid[], ?::bigint[]) AS u(id, amt)
                        """)) {
                    ps.setLong(1, debitAccId);
                    ps.setArray(2, conn.createArrayOf("uuid", txArr));
                    ps.setArray(3, conn.createArrayOf("bigint", amtArr));
                    ps.setLong(4, creditAccId);
                    ps.setArray(5, conn.createArrayOf("uuid", txArr));
                    ps.setArray(6, conn.createArrayOf("bigint", amtArr));
                    ps.executeUpdate();
                }

                try (var ps = conn.prepareStatement("""
                        INSERT INTO direct_carrier_session (id, carrier, session_type, reference_id, external_transaction_id, channel, amount_pesewas, currency, status)
                        SELECT u.id, 'GHIPSS', 'COLLECTION', u.ref, u.ref, 'BANK', u.amt, 'GHS', 'SUCCESS'
                        FROM unnest(?::uuid[], ?::text[], ?::bigint[]) AS u(id, ref, amt)
                        """)) {
                    ps.setArray(1, conn.createArrayOf("uuid", sessArr));
                    ps.setArray(2, conn.createArrayOf("text", refArr));
                    ps.setArray(3, conn.createArrayOf("bigint", amtArr));
                    ps.executeUpdate();
                }

                Integer[] lineNums = new Integer[count];
                for (int i = 0; i < count; i++) lineNums[i] = i + 1;
                try (var ps = conn.prepareStatement("""
                        INSERT INTO recon_statement_line (id, batch_id, line_number, value_date, entry_date, debit_credit_mark, amount_pesewas, transaction_type_code, bank_reference, customer_reference)
                        SELECT u.id, ?, u.ln, ?, ?, 'C', u.amt, 'NTRF', u.ref, u.ref
                        FROM unnest(?::uuid[], ?::int[], ?::bigint[], ?::text[]) AS u(id, ln, amt, ref)
                        """)) {
                    ps.setObject(1, batchId);
                    ps.setObject(2, statementDate);
                    ps.setObject(3, statementDate);
                    ps.setArray(4, conn.createArrayOf("uuid", lineArr));
                    ps.setArray(5, conn.createArrayOf("int4", lineNums));
                    ps.setArray(6, conn.createArrayOf("bigint", amtArr));
                    ps.setArray(7, conn.createArrayOf("text", refArr));
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(prevAutoCommit);
            }
            return null;
        });

        long t0 = System.nanoTime();
        var report = reconEngine.reconcileBatch(batchId);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        log.info("10-Way 50k reconciliation completed in {} ms (exact={}, mismatches={}, missingInLedger={})",
                elapsedMs, report.exactMatches(), report.amountMismatches(), report.missingInLedger());

        assertThat(report.totalProcessedLines()).isEqualTo(count);
        assertThat(report.exactMatches()).isEqualTo(count);
        assertThat(report.amountMismatches()).isZero();
        assertThat(report.missingInLedger()).isZero();
        assertThat(elapsedMs).isLessThan(15_000L);
    }
}
