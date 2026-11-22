package com.elepay.crm;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain 7: Customer Store Credit Balance & Immutable Audit Ledger.
 */
@Service
public class StoreCreditService {

    private final JdbcClient db;

    public StoreCreditService(JdbcClient db) {
        this.db = db;
    }

    public record StoreCreditSummary(
            UUID id,
            UUID merchantId,
            UUID customerId,
            long balancePesewas,
            String currency
    ) {}

    public record StoreCreditTransaction(
            UUID ledgerId,
            UUID accountId,
            long amountPesewas,
            long balanceAfterPesewas,
            String entryType,
            String reference
    ) {}

    @Transactional
    public StoreCreditSummary getOrCreateAccount(UUID merchantId, UUID customerId) {
        var existing = db.sql("""
            SELECT id, merchant_id, customer_id, balance_pesewas, currency
            FROM crm_store_credit_account
            WHERE merchant_id = ? AND customer_id = ?
        """).params(merchantId, customerId)
        .query((rs, rowNum) -> new StoreCreditSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getLong("balance_pesewas"),
                rs.getString("currency")
        )).optional();

        if (existing.isPresent()) {
            return existing.get();
        }

        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO crm_store_credit_account
            (id, merchant_id, customer_id, balance_pesewas, currency)
            VALUES (?, ?, ?, 0, 'GHS')
        """).params(id, merchantId, customerId).update();

        return new StoreCreditSummary(id, merchantId, customerId, 0L, "GHS");
    }

    /**
     * Adds credit to a customer store credit balance (e.g. returned goods, goodwill, or advance deposit).
     */
    @Transactional
    public StoreCreditTransaction addCredit(UUID merchantId, UUID customerId, long amountPesewas,
                                           String entryType, String reference, String description) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Amount to credit must be greater than zero");
        }

        StoreCreditSummary account = getOrCreateAccount(merchantId, customerId);

        var current = db.sql("""
            SELECT balance_pesewas FROM crm_store_credit_account
            WHERE id = ? FOR UPDATE
        """).param(account.id()).query(Long.class).single();

        long newBalance = current + amountPesewas;
        db.sql("UPDATE crm_store_credit_account SET balance_pesewas = ?, updated_at = now() WHERE id = ?")
                .params(newBalance, account.id()).update();

        UUID ledgerId = UUID.randomUUID();
        db.sql("""
            INSERT INTO crm_store_credit_ledger
            (id, store_credit_account_id, amount_pesewas, entry_type, reference, description, balance_after_pesewas)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """).params(ledgerId, account.id(), amountPesewas, entryType, reference, description, newBalance)
        .update();

        return new StoreCreditTransaction(ledgerId, account.id(), amountPesewas, newBalance, entryType, reference);
    }

    /**
     * Deducts store credit to pay for a purchase.
     */
    @Transactional
    public StoreCreditTransaction deductCredit(UUID merchantId, UUID customerId, long amountPesewas,
                                              String reference, String description) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Amount to deduct must be greater than zero");
        }

        StoreCreditSummary account = getOrCreateAccount(merchantId, customerId);

        var current = db.sql("""
            SELECT balance_pesewas FROM crm_store_credit_account
            WHERE id = ? FOR UPDATE
        """).param(account.id()).query(Long.class).single();

        if (current < amountPesewas) {
            throw new IllegalStateException("Insufficient store credit: available " + current +
                    ", requested " + amountPesewas);
        }

        long newBalance = current - amountPesewas;
        db.sql("UPDATE crm_store_credit_account SET balance_pesewas = ?, updated_at = now() WHERE id = ?")
                .params(newBalance, account.id()).update();

        UUID ledgerId = UUID.randomUUID();
        db.sql("""
            INSERT INTO crm_store_credit_ledger
            (id, store_credit_account_id, amount_pesewas, entry_type, reference, description, balance_after_pesewas)
            VALUES (?, ?, ?, 'CREDIT_USED', ?, ?, ?)
        """).params(ledgerId, account.id(), -amountPesewas, reference, description, newBalance)
        .update();

        return new StoreCreditTransaction(ledgerId, account.id(), -amountPesewas, newBalance, "CREDIT_USED", reference);
    }
}
