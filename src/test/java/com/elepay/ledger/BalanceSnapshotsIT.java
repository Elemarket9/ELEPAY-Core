package com.elepay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.elepay.PostgresTestBase;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class BalanceSnapshotsIT extends PostgresTestBase {
    @Autowired Ledger ledger;
    @Autowired BalanceSnapshots snapshots;
    @Autowired JdbcClient db;

    @Test void capturesBalancedImmutableIdempotentObservation() {
        String account = ledger.open("customer", UUID.randomUUID(), "available", "GHS");
        ledger.post(Ledger.Posting.of("snapshot-test", UUID.randomUUID().toString(), "topup",
                List.of(Ledger.Leg.debit(Ledger.clearing("sandbox"), 100), Ledger.Leg.credit(account, 100))));
        LocalDate day = LocalDate.ofEpochDay(-1_200_000L + new java.util.Random().nextInt(1_000_000));
        long id = ledger.account(account).orElseThrow().id();
        assertThat(snapshots.capture(day)).isPositive();
        assertThat(snapshots.capture(day)).isZero();
        assertThat(db.sql("SELECT balance FROM ledger_balance_snapshot WHERE snapshot_day=:d AND account_id=:a AND balance=journal_balance")
                .param("d", day).param("a", id).query(Long.class).single()).isEqualTo(100);
        assertThatThrownBy(() -> db.sql("DELETE FROM ledger_balance_snapshot WHERE snapshot_day=:d")
                .param("d", day).update()).hasMessageContaining("immutable");
    }

    @Test void retryJitterRemainsBoundedAndCapsLargeAttempts() {
        assertThat(db.sql("SELECT elepay_retry_seconds(15,600,0,0)").query(Double.class).single()).isEqualTo(12.0);
        assertThat(db.sql("SELECT elepay_retry_seconds(15,600,1,1)").query(Double.class).single()).isEqualTo(30.0);
        assertThat(db.sql("SELECT elepay_retry_seconds(15,600,2147483647,1)").query(Double.class).single()).isEqualTo(600.0);
    }
}
