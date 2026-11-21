package com.elepay.ledger;

import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Captures the complete projection and journal reconstruction in one SQL statement/MVCC snapshot. */
@Component
class BalanceSnapshots {
    private final JdbcClient db;
    private final boolean enabled;

    BalanceSnapshots(JdbcClient db, @Value("${elepay.ledger.snapshots-enabled:true}") boolean enabled) {
        this.db = db;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${elepay.ledger.snapshot-poll-ms:60000}", initialDelay = 15000)
    void scheduled() {
        if (enabled) capture(java.time.LocalDate.now(java.time.ZoneOffset.UTC));
    }

    @Transactional
    public int capture(LocalDate day) {
        return db.sql("""
                WITH claimed AS (
                    INSERT INTO ledger_snapshot_run (snapshot_day) VALUES (:day)
                    ON CONFLICT (snapshot_day) DO NOTHING RETURNING snapshot_day
                )
                INSERT INTO ledger_balance_snapshot (snapshot_day, account_id, currency, balance, journal_balance)
                SELECT c.snapshot_day, a.id, a.currency, r.projected_balance, r.journal_balance
                FROM claimed c CROSS JOIN ledger_account a
                JOIN ledger_account_reconstruction r ON r.id = a.id
                """).param("day", day).update();
    }
}
