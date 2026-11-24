package com.elepay.ledger;

import static com.elepay.ledger.Ledger.Leg.credit;
import static com.elepay.ledger.Ledger.Leg.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger.Posting;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class LedgerIT extends PostgresTestBase {
    @Autowired Ledger ledger;
    @Autowired JdbcClient db;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    String alice;
    String bob;
    String clearing = Ledger.clearing("sandbox");

    @BeforeEach
    void accounts() {
        alice = ledger.open("customer", UUID.randomUUID(), "available", "GHS");
        bob = ledger.open("customer", UUID.randomUUID(), "available", "GHS");
    }

    private Posting fund(String account, long amount) {
        return Posting.of("test", UUID.randomUUID().toString(), "topup",
                List.of(debit(clearing, amount), credit(account, amount)));
    }

    @Test
    void postsBalancedEntriesAndRejectsUnbalanced() {
        ledger.post(fund(alice, 15_000));
        assertThat(ledger.balance(alice)).isEqualTo(15_000);
        assertThatThrownBy(() -> ledger.post(Posting.of("test", "u-" + UUID.randomUUID(), "bad",
                List.of(debit(alice, 100), credit(bob, 99)))))
                .isInstanceOf(ApiException.class).hasMessageContaining("sum to zero");
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void identicalRetryReturnsOriginalAndChangedRequestIsRejected() {
        String key = UUID.randomUUID().toString();
        var first = ledger.post(Posting.of("test", key, "topup", List.of(debit(clearing, 500), credit(alice, 500))));
        var again = ledger.post(Posting.of("test", key, "topup", List.of(debit(clearing, 500), credit(alice, 500))));
        assertThat(again.transactionId()).isEqualTo(first.transactionId());
        assertThat(again.replay()).isTrue();
        assertThat(ledger.balance(alice)).isEqualTo(500);
        assertThatThrownBy(() -> ledger.post(Posting.of("test", key, "topup",
                List.of(debit(clearing, 501), credit(alice, 501)))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("idempotency_conflict");
    }

    @Test
    void preventsOverdraftAndCurrencyMismatch() {
        ledger.post(fund(alice, 100));
        assertThatThrownBy(() -> ledger.post(Posting.of("test", UUID.randomUUID().toString(), "transfer",
                List.of(debit(alice, 101), credit(bob, 101)))))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("insufficient_funds");
        String usd = ledger.open("customer", UUID.randomUUID(), "available", "USD");
        assertThatThrownBy(() -> ledger.post(Posting.of("test", UUID.randomUUID().toString(), "fx",
                List.of(debit(alice, 50), credit(usd, 50)))))
                .isInstanceOf(ApiException.class).hasMessageContaining("sum to zero");
    }

    @Test
    void journalIsAppendOnlyAndBalancesCannotBeEditedDirectly() {
        var posted = ledger.post(fund(alice, 100));
        assertThatThrownBy(() -> db.sql("UPDATE journal_entry SET amount = 1 WHERE transaction_id = :t")
                .param("t", posted.transactionId()).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> db.sql("DELETE FROM journal_transaction WHERE id = :t")
                .param("t", posted.transactionId()).update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> db.sql("UPDATE ledger_account SET balance = 1000000 WHERE code = :c")
                .param("c", alice).update()).hasMessageContaining("only through journal entries");
    }

    @Test
    void databaseRejectsUnbalancedInsertEvenWithoutApplicationChecks() {
        UUID txId = UUID.randomUUID();
        long account = ledger.account(alice).orElseThrow().id();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            db.sql("""
                INSERT INTO journal_transaction (id, idempotency_scope, idempotency_key, request_hash, kind)
                VALUES (:id, 'raw', :key, 'x', 'raw')""").param("id", txId).param("key", txId.toString()).update();
            db.sql("INSERT INTO journal_entry (transaction_id, line, account_id, currency, amount) VALUES (:id, 0, :a, 'GHS', 5)")
                .param("id", txId).param("a", account).update();
            db.sql("SET CONSTRAINTS ALL IMMEDIATE").update();
        }))
                .hasMessageContaining("at least two entries");
    }

    @Test
    void controlledReversalOnlyOnce() {
        ledger.post(fund(alice, 1_000));
        var transfer = ledger.post(Posting.of("test", UUID.randomUUID().toString(), "transfer",
                List.of(debit(alice, 400), credit(bob, 400))));
        var reversal = ledger.reverse(transfer.transactionId(), "test", "rev-" + transfer.transactionId(), "mistake");
        assertThat(ledger.balance(alice)).isEqualTo(1_000);
        assertThat(ledger.balance(bob)).isZero();
        var replay = ledger.reverse(transfer.transactionId(), "test", "rev-" + transfer.transactionId(), "mistake");
        assertThat(replay.transactionId()).isEqualTo(reversal.transactionId());
        assertThatThrownBy(() -> ledger.reverse(transfer.transactionId(), "test", "other-" + UUID.randomUUID(), "again"))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("already_reversed");
        assertThatThrownBy(() -> ledger.reverse(reversal.transactionId(), "test", "rr-" + UUID.randomUUID(), "x"))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("reversal_of_reversal");
    }

    @Test
    void concurrentSpendersCannotSpendTheSameBalanceTwice() throws Exception {
        ledger.post(fund(alice, 10_000));
        int attempts = 16;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    ledger.post(Posting.of("test", UUID.randomUUID().toString(), "spend",
                            List.of(debit(alice, 3_000), credit(bob, 3_000))));
                    return true;
                } catch (ApiException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        long ok = 0;
        for (var f : results) if (f.get(30, TimeUnit.SECONDS)) ok++;
        pool.shutdown();
        assertThat(ok).isEqualTo(3);
        assertThat(ledger.balance(alice)).isEqualTo(1_000);
        assertThat(ledger.balance(bob)).isEqualTo(9_000);
        assertThat(ledger.integrity().ok()).isTrue();
    }

    @Test
    void concurrentIdenticalRetriesPostOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UUID>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return ledger.post(Posting.of("test", key, "topup",
                        List.of(debit(clearing, 700), credit(alice, 700)))).transactionId();
            }));
        }
        start.countDown();
        var ids = new java.util.HashSet<UUID>();
        for (var f : results) ids.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(ids).hasSize(1);
        assertThat(ledger.balance(alice)).isEqualTo(700);
    }

    @Test
    void legRejectsNonPositiveAmounts() {
        assertThatThrownBy(() -> debit(alice, 0))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> debit(alice, -50))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> credit(bob, 0))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> credit(bob, -1))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("positive");
    }
}
