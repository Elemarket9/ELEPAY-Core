package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.Audit;
import com.elepay.ledger.Ledger;
import com.elepay.operations.PartitionMaintenanceService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies Stage 3A declarative table partitioning (V77), BRIN indexes, archive partitions,
 * V74 immutability preservation, double-entry balance enforcement, and partition lifecycle maintenance.
 */
class PartitioningIT extends PostgresTestBase {
    @Autowired Ledger ledger;
    @Autowired Audit audit;
    @Autowired PartitionMaintenanceService maintenance;
    @Autowired JdbcClient db;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void highVolumeTablesAndArchivesAreDeclarativelyPartitioned() {
        for (String rangeTable : List.of(
                "journal_entry",
                "audit_log",
                "webhook_delivery_log",
                "journal_transaction_archive",
                "checkout_archive")) {
            String relkind = db.sql("""
                    SELECT c.relkind::text
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relname = :tbl
                    """).param("tbl", rangeTable).query(String.class).single();
            assertThat(relkind).as("Table %s must be partitioned", rangeTable).isEqualTo("p");

            String strategy = db.sql("""
                    SELECT pt.partstrat::text
                    FROM pg_partitioned_table pt
                    JOIN pg_class c ON c.oid = pt.partrelid
                    WHERE c.relname = :tbl
                    """).param("tbl", rangeTable).query(String.class).single();
            assertThat(strategy).as("Table %s must use RANGE partitioning", rangeTable).isEqualTo("r");

            long partitionCount = db.sql("""
                    SELECT count(*)
                    FROM pg_inherits i
                    JOIN pg_class parent ON parent.oid = i.inhparent
                    WHERE parent.relname = :tbl
                    """).param("tbl", rangeTable).query(Long.class).single();
            assertThat(partitionCount)
                    .as("Table %s must have default + pre-provisioned monthly partitions", rangeTable)
                    .isGreaterThanOrEqualTo(7L);
        }

        // idempotent_request uses 16-way HASH partitioning on (scope, key)
        String idemRelkind = db.sql("""
                SELECT c.relkind::text
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'idempotent_request'
                """).query(String.class).single();
        assertThat(idemRelkind).isEqualTo("p");

        String idemStrategy = db.sql("""
                SELECT pt.partstrat::text
                FROM pg_partitioned_table pt
                JOIN pg_class c ON c.oid = pt.partrelid
                WHERE c.relname = 'idempotent_request'
                """).query(String.class).single();
        assertThat(idemStrategy).isEqualTo("h");

        long hashPartitions = db.sql("""
                SELECT count(*)
                FROM pg_inherits i
                JOIN pg_class parent ON parent.oid = i.inhparent
                WHERE parent.relname = 'idempotent_request'
                """).query(Long.class).single();
        assertThat(hashPartitions).isEqualTo(16L);

        // BRIN indexes & checkout_allocation.created_at
        long jtBrin = db.sql("""
                SELECT count(*) FROM pg_indexes
                WHERE tablename = 'journal_transaction' AND indexdef ILIKE '%USING brin%'
                """).query(Long.class).single();
        assertThat(jtBrin).isGreaterThanOrEqualTo(1L);

        long checkoutBrin = db.sql("""
                SELECT count(*) FROM pg_indexes
                WHERE tablename = 'checkout' AND indexdef ILIKE '%USING brin%'
                """).query(Long.class).single();
        assertThat(checkoutBrin).isGreaterThanOrEqualTo(1L);

        long allocCreatedAt = db.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'checkout_allocation'
                  AND column_name = 'created_at'
                """).query(Long.class).single();
        assertThat(allocCreatedAt).isEqualTo(1L);
    }

    @Test
    void ledgerPostingReconstructionAndBalanceEnforcementHoldOnPartitionedJournal() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String acct = ledger.open("merchant", UUID.randomUUID(), "available", Ledger.GHS);
        long acctId = ledger.account(acct).orElseThrow().id();

        var posted = ledger.post(Ledger.Posting.of("part-ledger-" + suffix, "k1", "deposit", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 4_200L),
                Ledger.Leg.credit(acct, 4_200L))));
        assertThat(ledger.balance(acct)).isEqualTo(4_200L);

        long drift = db.sql("SELECT count(*) FROM ledger_account_reconstruction WHERE projected_balance <> journal_balance")
                .query(Long.class)
                .single();
        assertThat(drift).isZero();

        // Single-entry unbalanced journal is rejected by folded journal_transaction_has_entries trigger
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID badTx = UUID.randomUUID();
            db.sql("""
                    INSERT INTO journal_transaction (id, idempotency_scope, idempotency_key, request_hash, kind)
                    VALUES (:id, :scope, :key, 'hash', 'raw')""")
                    .param("id", badTx).param("scope", "part-" + suffix).param("key", "one-line").update();
            db.sql("""
                    INSERT INTO journal_entry (transaction_id, line, account_id, currency, amount)
                    VALUES (:tx, 0, :acct, 'GHS', 500)""")
                    .param("tx", badTx).param("acct", acctId).update();
        })).hasStackTraceContaining("at least two entries");

        // Two-entry unbalanced journal is rejected by folded journal_transaction_has_entries trigger
        long clearingId = ledger.account(Ledger.clearing("sandbox")).orElseThrow().id();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID badTx = UUID.randomUUID();
            db.sql("""
                    INSERT INTO journal_transaction (id, idempotency_scope, idempotency_key, request_hash, kind)
                    VALUES (:id, :scope, :key, 'hash', 'raw')""")
                    .param("id", badTx).param("scope", "part-" + suffix).param("key", "unbalanced-two").update();
            db.sql("""
                    INSERT INTO journal_entry (transaction_id, line, account_id, currency, amount)
                    VALUES (:tx, 0, :clearing, 'GHS', -500), (:tx, 1, :acct, 'GHS', 600)""")
                    .param("tx", badTx).param("clearing", clearingId).param("acct", acctId).update();
        })).hasStackTraceContaining("is not balanced");

        // V74 immutability blocks UPDATE, DELETE, and trigger tampering on partitioned journal_entry
        assertThatThrownBy(() -> db.sql("UPDATE journal_entry SET amount = 1 WHERE transaction_id = :tx")
                .param("tx", posted.transactionId()).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> db.sql("DELETE FROM journal_entry WHERE transaction_id = :tx")
                .param("tx", posted.transactionId()).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> db.sql("ALTER TABLE journal_entry DISABLE TRIGGER journal_entry_immutable").update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("strictly forbidden");
    }

    @Test
    void auditLogSequenceImmutabilityAndPartitionMaintenanceWorkEndToEnd() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Audit.Entry entry = audit.recordEntry("system", "partition_test", "subject:" + suffix, Map.of("k", "v"));
        assertThat(entry.id()).isPositive();

        assertThatThrownBy(() -> db.sql("UPDATE audit_log SET action = 'tampered' WHERE id = :id")
                .param("id", entry.id()).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> db.sql("DELETE FROM audit_log WHERE id = :id")
                .param("id", entry.id()).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");

        // Idempotent ensure_monthly_partitions(6) and future horizon extension
        int firstCall = maintenance.ensurePartitions(6);
        int secondCall = maintenance.ensurePartitions(6);
        assertThat(firstCall).isGreaterThanOrEqualTo(0);
        assertThat(secondCall).isZero();

        int extended = maintenance.ensurePartitions(8);
        assertThat(extended).isGreaterThanOrEqualTo(10); // 2 extra months * 5 range-partitioned tables
        assertThat(maintenance.ensurePartitions(8)).isZero();

        List<PartitionMaintenanceService.PartitionStat> stats = maintenance.listPartitionStats();
        assertThat(stats).isNotEmpty();
        assertThat(stats).extracting(PartitionMaintenanceService.PartitionStat::parentTable)
                .contains("journal_entry", "audit_log", "webhook_delivery_log", "idempotent_request");

        // Idempotent request retention archives responses and preserves every unique claim
        String scope = "ttl-test-" + suffix;
        OffsetDateTime oldTs = OffsetDateTime.now(ZoneOffset.UTC).minusDays(45);
        OffsetDateTime freshTs = OffsetDateTime.now(ZoneOffset.UTC);
        db.sql("""
                INSERT INTO idempotent_request (scope, key, request_hash, created_at, response)
                VALUES (:scope, 'old-key', 'h1', :oldTs, '42'::jsonb), (:scope, 'fresh-key', 'h2', :freshTs, '43'::jsonb)
                """)
                .param("scope", scope)
                .param("oldTs", oldTs)
                .param("freshTs", freshTs)
                .update();

        long pruned = maintenance.pruneExpiredIdempotentRequests(30, 1000, 5);
        assertThat(pruned).isGreaterThanOrEqualTo(1L);

        long remainingOld = db.sql("SELECT count(*) FROM idempotent_request WHERE scope = :scope AND key = 'old-key'")
                .param("scope", scope).query(Long.class).single();
        long remainingFresh = db.sql("SELECT count(*) FROM idempotent_request WHERE scope = :scope AND key = 'fresh-key'")
                .param("scope", scope).query(Long.class).single();
        assertThat(remainingOld).isOne();
        assertThat(db.sql("SELECT response::text FROM idempotency_result_archive WHERE scope = :scope AND key = 'old-key'")
                .param("scope", scope).query(String.class).single()).isEqualTo("42");
        assertThat(remainingFresh).isEqualTo(1L);
    }
}
