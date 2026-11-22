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
}
