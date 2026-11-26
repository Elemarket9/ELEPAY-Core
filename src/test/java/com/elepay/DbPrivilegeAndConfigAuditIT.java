package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.ledger.Ledger;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P1 #8 & P2 #9: Verifies database-level financial table immutability, trigger-tampering defenses,
 * stored-balance sync / double-entry triggers, and safe production configuration defaults.
 */
class DbPrivilegeAndConfigAuditIT extends PostgresTestBase {
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void immutableFinancialTablesRejectUpdateDeleteTruncateAndTriggerTampering() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String acct = ledger.open("merchant", UUID.randomUUID(), "available", Ledger.GHS);
        var posted = ledger.post(Ledger.Posting.of("db-audit-" + suffix, "k1", "audit_seed", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 2_500L),
                Ledger.Leg.credit(acct, 2_500L))));
        UUID txId = posted.transactionId();

        // 1. journal_transaction is strictly append-only (UPDATE, DELETE, TRUNCATE rejected)
        assertThatThrownBy(() -> db.sql("UPDATE journal_transaction SET description = 'tampered' WHERE id = :id")
                .param("id", txId).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> db.sql("DELETE FROM journal_transaction WHERE id = :id")
                .param("id", txId).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> db.sql("TRUNCATE journal_transaction CASCADE").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");

        // 2. journal_entry is strictly append-only (UPDATE, DELETE, TRUNCATE rejected)
        assertThatThrownBy(() -> db.sql("UPDATE journal_entry SET amount = 999999 WHERE transaction_id = :id")
                .param("id", txId).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> db.sql("DELETE FROM journal_entry WHERE transaction_id = :id")
                .param("id", txId).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> db.sql("TRUNCATE journal_entry").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");

        // 3. ledger_account blocks direct balance mutation, identity mutation, DELETE, and TRUNCATE (V4 + V74)
        assertThatThrownBy(() -> db.sql("UPDATE ledger_account SET balance = balance + 1000 WHERE code = :c")
                .param("c", acct).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("journal entries");
        assertThatThrownBy(() -> db.sql("UPDATE ledger_account SET currency = 'USD' WHERE code = :c")
                .param("c", acct).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> db.sql("DELETE FROM ledger_account WHERE code = :c")
                .param("c", acct).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> db.sql("TRUNCATE ledger_account CASCADE").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");

        // 4. V74 newly hardened append-only financial/metric history tables reject UPDATE, DELETE, TRUNCATE
        UUID snapId = UUID.randomUUID();
        db.sql("""
                INSERT INTO financial_ledger_metric_snapshot
                    (id, currency, total_debits, total_credits, drift, is_healthy)
                VALUES (:id, 'GHS', 1000, 1000, 0, true)""")
                .param("id", snapId).update();
        assertThatThrownBy(() -> db.sql("UPDATE financial_ledger_metric_snapshot SET drift = 999 WHERE id = :id")
                .param("id", snapId).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> db.sql("DELETE FROM financial_ledger_metric_snapshot WHERE id = :id")
                .param("id", snapId).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> db.sql("TRUNCATE financial_ledger_metric_snapshot").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");

        // 5. Event triggers block both DISABLE TRIGGER and DROP TRIGGER on immutability & balance-sync triggers (V57 + V74)
        assertThatThrownBy(() -> db.sql("ALTER TABLE journal_entry DISABLE TRIGGER journal_entry_immutable").update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("strictly forbidden");
        assertThatThrownBy(() -> db.sql("ALTER TABLE journal_entry DISABLE TRIGGER journal_entry_apply").update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("strictly forbidden");
        assertThatThrownBy(() -> db.sql("ALTER TABLE journal_entry DISABLE TRIGGER journal_entry_balanced").update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("strictly forbidden");
        assertThatThrownBy(() -> db.sql("DROP TRIGGER journal_entry_immutable ON journal_entry").update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("strictly forbidden");
        assertThatThrownBy(() -> db.sql("DROP TRIGGER journal_entry_apply ON journal_entry").update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("strictly forbidden");
    }

    @Test
    void storedBalanceSyncAndDoubleEntryTriggersHoldAtDatabaseLevel() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String acct = ledger.open("merchant", UUID.randomUUID(), "available", Ledger.GHS);
        long acctId = ledger.account(acct).orElseThrow().id();

        ledger.post(Ledger.Posting.of("sync-audit-" + suffix, "credit", "deposit", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 5_000L),
                Ledger.Leg.credit(acct, 5_000L))));
        ledger.post(Ledger.Posting.of("sync-audit-" + suffix, "debit", "withdraw", List.of(
                Ledger.Leg.debit(acct, 2_000L),
                Ledger.Leg.credit(Ledger.clearing("sandbox"), 2_000L))));

        // Stored balance projection matches reconstructed journal sum exactly
        record Recon(long projected, long journal) {}
        Recon recon = db.sql("""
                SELECT projected_balance, journal_balance
                FROM ledger_account_reconstruction
                WHERE id = :id""")
                .param("id", acctId)
                .query((rs, i) -> new Recon(rs.getLong(1), rs.getLong(2)))
                .single();
        assertThat(recon.projected()).isEqualTo(3_000L);
        assertThat(recon.journal()).isEqualTo(3_000L);

        // Unbalanced raw SQL journal transaction is rejected at commit by deferred constraint trigger
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID badTx = UUID.randomUUID();
            db.sql("""
                    INSERT INTO journal_transaction (id, idempotency_scope, idempotency_key, request_hash, kind)
                    VALUES (:id, :scope, :key, 'raw-hash', 'tamper')""")
                    .param("id", badTx).param("scope", "raw-" + suffix).param("key", "unbalanced").update();
            db.sql("""
                    INSERT INTO journal_entry (transaction_id, line, account_id, currency, amount)
                    VALUES (:tx, 0, :acct, 'GHS', 1000)""")
                    .param("tx", badTx).param("acct", acctId).update();
        })).hasStackTraceContaining("at least two entries");

        // Currency-mismatched journal entry is rejected immediately by journal_entry_apply
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID badCurTx = UUID.randomUUID();
            db.sql("""
                    INSERT INTO journal_transaction (id, idempotency_scope, idempotency_key, request_hash, kind)
                    VALUES (:id, :scope, :key, 'raw-hash', 'tamper')""")
                    .param("id", badCurTx).param("scope", "raw-" + suffix).param("key", "bad-currency").update();
            db.sql("""
                    INSERT INTO journal_entry (transaction_id, line, account_id, currency, amount)
                    VALUES (:tx, 0, :acct, 'USD', 1000)""")
                    .param("tx", badCurTx).param("acct", acctId).update();
        })).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("does not match account currency");

        // Balance unchanged and global integrity intact after rejected tamper attempts
        assertThat(ledger.balance(acct)).isEqualTo(3_000L);
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void productionAndBaseConfigurationDefaultsAreHardened() throws IOException {
        Properties base = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        Properties prod = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-prod.properties"));

        // Base application.properties: fail-safe defaults
        assertThat(base.getProperty("spring.profiles.default")).isEqualTo("prod");
        assertThat(base.getProperty("elepay.rails.sandbox.enabled")).isEqualTo("${ELEPAY_SANDBOX_ENABLED:false}");
        assertThat(base.getProperty("elepay.dormant-modules.enabled")).isEqualTo("${ELEPAY_DORMANT_MODULES_ENABLED:false}");
        assertThat(base.getProperty("elepay.outbound.allow-http")).isEqualTo("false");
        assertThat(base.getProperty("elepay.outbound.allow-private-hosts")).isEqualTo("false");
        assertThat(base.getProperty("elepay.security.dual-control.enforced")).isEqualTo("true");
        assertThat(base.getProperty("elepay.security.destination-oob-required")).isEqualTo("true");
        assertThat(base.getProperty("elepay.security.destination-name-match-required")).isEqualTo("true");
        assertThat(base.getProperty("elepay.cors.allowed-origins")).doesNotContain("*");
        assertThat(base.getProperty("elepay.security.dev-jwt-secret")).isNull();

        // Production profile overrides: strict guardrails
        assertThat(prod.getProperty("elepay.environment")).isEqualTo("production");
        assertThat(prod.getProperty("elepay.rails.sandbox.enabled")).isEqualTo("false");
        assertThat(prod.getProperty("elepay.dormant-modules.enabled")).isEqualTo("false");
        assertThat(prod.getProperty("elepay.emtech.enabled")).isEqualTo("false");
        assertThat(prod.getProperty("elepay.stage1.strict-kill-switch-enabled")).isEqualTo("true");
        assertThat(prod.getProperty("elepay.security.request-signing.require-v2")).isEqualTo("true");
        assertThat(prod.getProperty("elepay.rate-limits.shared-required")).isEqualTo("true");
        assertThat(prod.getProperty("springdoc.api-docs.enabled")).isEqualTo("${ELEPAY_API_DOCS_ENABLED:false}");
        assertThat(prod.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("${ELEPAY_SWAGGER_UI_ENABLED:false}");
        assertThat(prod.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(prod.getProperty("server.error.include-stacktrace")).isEqualTo("never");
        assertThat(prod.getProperty("server.error.include-message")).isEqualTo("never");
        assertThat(prod.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
        assertThat(prod.getProperty("spring.flyway.out-of-order")).isEqualTo("false");
    }
}
