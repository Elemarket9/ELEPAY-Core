package com.elepay.wallet;

import static org.junit.jupiter.api.Assertions.*;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import com.elepay.common.ApiException;
import com.elepay.ledger.Ledger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class TransferLimitsTest extends PostgresTestBase {

    @Autowired
    private TransferLimits limits;

    @Autowired
    private Ledger ledger;

    @Autowired
    private JdbcClient db;

    @Autowired
    private TransactionTemplate tx;

    private UUID createCustomer(String phone, int tier) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO customer (id, subject, phone, display_name, kyc_tier, status)
                VALUES (:id, :subject, :phone, :name, :tier, 'active')
                """)
                .param("id", id)
                .param("subject", "sub-" + id)
                .param("phone", phone)
                .param("name", "Customer " + tier)
                .param("tier", tier)
                .update();

        ledger.open("customer", id, "available", Ledger.GHS);
        return id;
    }

    @Test
    void tier1DailyLimitEnforced() {
        UUID sender = createCustomer(TestApi.uniquePhone(), 1);
        UUID recipient = createCustomer(TestApi.uniquePhone(), 2);

        // Within Tier 1 daily limit (100,000 pesewas)
        tx.executeWithoutResult(status -> limits.check(sender, recipient, 100_000L));

        // Exceeding Tier 1 daily limit
        ApiException ex = assertThrows(ApiException.class, () ->
                tx.executeWithoutResult(status -> limits.check(sender, recipient, 100_001L)));
        assertEquals("daily_transfer_limit", ex.code());
    }

    @Test
    void tier2DailyAndMonthlyLimits() {
        UUID sender = createCustomer(TestApi.uniquePhone(), 2);
        UUID recipient = createCustomer(TestApi.uniquePhone(), 3);

        // Tier 2 daily limit is 500,000 pesewas
        tx.executeWithoutResult(status -> limits.check(sender, recipient, 500_000L));

        ApiException ex = assertThrows(ApiException.class, () ->
                tx.executeWithoutResult(status -> limits.check(sender, recipient, 500_001L)));
        assertEquals("daily_transfer_limit", ex.code());
    }

    @Test
    void recipientMaxBalanceEnforced() {
        UUID sender = createCustomer(TestApi.uniquePhone(), 3);
        UUID recipient = createCustomer(TestApi.uniquePhone(), 1); // Tier 1 max balance is 100,000 pesewas

        // Fund recipient available balance to 80,000 pesewas
        String recAccount = Ledger.code("customer", recipient, "available", Ledger.GHS);
        ledger.post(Ledger.Posting.of("test-limits-" + UUID.randomUUID(), "tx-rec-fund", "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 80_000),
                Ledger.Leg.credit(recAccount, 80_000)
        )));

        // Sending 20,000 should succeed (total becomes 100,000)
        tx.executeWithoutResult(status -> limits.check(sender, recipient, 20_000L));

        // Sending 20,001 should fail because 80,000 + 20,001 = 100,001 > 100,000
        ApiException ex = assertThrows(ApiException.class, () ->
                tx.executeWithoutResult(status -> limits.check(sender, recipient, 20_001L)));
        assertEquals("recipient_balance_limit", ex.code());
    }

    @Test
    void cumulativeDailyTransfersExceedLimit() {
        UUID sender = createCustomer(TestApi.uniquePhone(), 1); // Tier 1 daily limit 100,000
        UUID recipient = createCustomer(TestApi.uniquePhone(), 2);

        // Record a past transfer operation today for 60,000 pesewas
        db.sql("""
                INSERT INTO wallet_operation (id, reference, customer_id, kind, amount, status)
                VALUES (:id, :ref, :c, 'transfer_out', 60000, 'completed')
                """)
                .param("id", UUID.randomUUID())
                .param("ref", "WLT-TEST-" + UUID.randomUUID())
                .param("c", sender)
                .update();

        // 40,000 remaining: transfer of 40,000 succeeds
        tx.executeWithoutResult(status -> limits.check(sender, recipient, 40_000L));

        // Transfer of 40,001 exceeds remaining daily limit
        ApiException ex = assertThrows(ApiException.class, () ->
                tx.executeWithoutResult(status -> limits.check(sender, recipient, 40_001L)));
        assertEquals("daily_transfer_limit", ex.code());
    }

    @Autowired(required = false)
    private com.elepay.growth.PersonalizedLimitService personalizedLimitService;

    @Test
    void personalizedLimitActivelyEnforced() {
        if (personalizedLimitService == null) return;
        UUID sender = createCustomer(TestApi.uniquePhone(), 2);
        UUID recipient = createCustomer(TestApi.uniquePhone(), 3);

        // Tier 2 baseline is 200,000. Curtailed to 50% = 100,000 max daily, 50,000 max txn
        personalizedLimitService.computePersonalizedLimit(sender, 2, 40, 30, 2);

        // Per-transaction limit check: 50,000 succeeds
        tx.executeWithoutResult(status -> limits.check(sender, recipient, 50_000L));

        // Exceeding per-transaction limit (50,001) fails with transfer_limit
        ApiException exTxn = assertThrows(ApiException.class, () ->
                tx.executeWithoutResult(status -> limits.check(sender, recipient, 50_001L)));
        assertEquals("transfer_limit", exTxn.code());

        // Within daily limit (100,000): Record 60,000 transferred out
        db.sql("""
                INSERT INTO wallet_operation (id, reference, customer_id, kind, amount, status)
                VALUES (:id, :ref, :c, 'transfer_out', 60000, 'completed')
                """)
                .param("id", UUID.randomUUID())
                .param("ref", "WLT-TEST-" + UUID.randomUUID())
                .param("c", sender)
                .update();

        // 40,000 remaining on 100,000 daily limit: 40,000 succeeds
        tx.executeWithoutResult(status -> limits.check(sender, recipient, 40_000L));

        // 40,001 exceeds 100,000 daily limit -> daily_transfer_limit
        ApiException exDaily = assertThrows(ApiException.class, () ->
                tx.executeWithoutResult(status -> limits.check(sender, recipient, 40_001L)));
        assertEquals("daily_transfer_limit", exDaily.code());
    }

    @Test
    void concurrentTransfersDoNotExceedRecipientMaxBalance() throws Exception {
        UUID sender1 = createCustomer(TestApi.uniquePhone(), 3);
        UUID sender2 = createCustomer(TestApi.uniquePhone(), 3);
        UUID recipient = createCustomer(TestApi.uniquePhone(), 1); // Tier 1 maxBalance = 100,000 pesewas

        // Fund both senders and bring recipient to 80,000 pesewas
        String recAcc = Ledger.code("customer", recipient, "available", Ledger.GHS);
        String s1Acc = Ledger.code("customer", sender1, "available", Ledger.GHS);
        String s2Acc = Ledger.code("customer", sender2, "available", Ledger.GHS);
        ledger.post(Ledger.Posting.of("test-conc-setup-" + UUID.randomUUID(), "tx-setup", "topup", List.of(
                Ledger.Leg.debit(Ledger.clearing("sandbox"), 280_000),
                Ledger.Leg.credit(recAcc, 80_000),
                Ledger.Leg.credit(s1Acc, 100_000),
                Ledger.Leg.credit(s2Acc, 100_000)
        )));

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.atomic.AtomicInteger successes = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var f1 = pool.submit(() -> {
                start.await();
                try {
                    tx.executeWithoutResult(st -> {
                        limits.check(sender1, recipient, 20_000L);
                        ledger.post(Ledger.Posting.of("test-conc-1-" + UUID.randomUUID(), "t1", "transfer", List.of(
                                Ledger.Leg.debit(s1Acc, 20_000),
                                Ledger.Leg.credit(recAcc, 20_000)
                        )));
                    });
                    successes.incrementAndGet();
                } catch (ApiException expected) {
                    assertEquals("recipient_balance_limit", expected.code());
                }
                return null;
            });
            var f2 = pool.submit(() -> {
                start.await();
                try {
                    tx.executeWithoutResult(st -> {
                        limits.check(sender2, recipient, 20_000L);
                        ledger.post(Ledger.Posting.of("test-conc-2-" + UUID.randomUUID(), "t2", "transfer", List.of(
                                Ledger.Leg.debit(s2Acc, 20_000),
                                Ledger.Leg.credit(recAcc, 20_000)
                        )));
                    });
                    successes.incrementAndGet();
                } catch (ApiException expected) {
                    assertEquals("recipient_balance_limit", expected.code());
                }
                return null;
            });
            start.countDown();
            f1.get(15, java.util.concurrent.TimeUnit.SECONDS);
            f2.get(15, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, successes.get());
        assertEquals(100_000L, ledger.balance(recAcc));
    }
}
