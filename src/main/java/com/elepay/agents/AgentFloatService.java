package com.elepay.agents;

import com.elepay.ledger.Ledger;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentFloatService {

    public static final String BANK_FUNDING = "system:agent_funding:bank:GHS";

    private final JdbcClient db;
    private final Ledger ledger;

    public AgentFloatService(JdbcClient db, Ledger ledger) {
        this.db = db;
        this.ledger = ledger;
    }

    public record FloatAccount(
            UUID id,
            UUID agentId,
            long availableFloatPesewas,
            long reservedFloatPesewas,
            long accruedCommissionPesewas,
            long totalLifetimeCommissionPesewas,
            String currency,
            OffsetDateTime updatedAt
    ) {}

    public Optional<FloatAccount> getFloatAccount(UUID agentId) {
        return db.sql("""
            SELECT id, agent_id, available_float_pesewas, reserved_float_pesewas,
                   accrued_commission_pesewas, total_lifetime_commission_pesewas,
                   currency, updated_at
            FROM agent_float_account
            WHERE agent_id = ?
        """).param(agentId)
        .query((rs, rowNum) -> new FloatAccount(
                rs.getObject("id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                rs.getLong("available_float_pesewas"),
                rs.getLong("reserved_float_pesewas"),
                rs.getLong("accrued_commission_pesewas"),
                rs.getLong("total_lifetime_commission_pesewas"),
                rs.getString("currency"),
                rs.getObject("updated_at", OffsetDateTime.class)
        )).optional();
    }

    /**
     * Records an inflow from an authorized bank settlement deposit into the platform agent funding account.
     */
    @Transactional
    public Ledger.Posted recordBankDeposit(long amountPesewas, String bankReference) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Bank deposit amount must be greater than zero");
        }
        String bankClearing = ledger.openSystem("system:clearing:bank:GHS", "clearing", Ledger.GHS, true);
        ledger.openSystem(BANK_FUNDING, "clearing", Ledger.GHS, false);
        String key = "bank-dep-" + (bankReference != null ? bankReference : UUID.randomUUID().toString());
        return ledger.post(Ledger.Posting.of("agent-bank-deposit", key, "agent_bank_deposit", List.of(
                Ledger.Leg.debit(bankClearing, amountPesewas),
                Ledger.Leg.credit(BANK_FUNDING, amountPesewas)
        )).describedAs("Authorized bank deposit funding: " + bankReference));
    }

    /**
     * Replenishes an agent's float backed by either a merchant settlement account or bank deposit clearing.
     * Prevents money creation out of thin air: the funding source account must have sufficient balance.
     */
    @Transactional
    public FloatAccount replenishFloat(UUID agentId, long amountPesewas, String externalRef) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Replenishment amount must be greater than zero");
        }

        FloatAccount current = getFloatAccount(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent float account not found: " + agentId));

        String agentLedgerAccount = ledger.open("merchant", agentId, "available", Ledger.GHS);

        // Determine funding source: merchant settlement or bank deposit
        String fundingSource;
        if (externalRef != null && isMerchantAccount(externalRef)) {
            fundingSource = Ledger.code("merchant", UUID.fromString(externalRef), "available", Ledger.GHS);
        } else {
            fundingSource = ledger.openSystem(BANK_FUNDING, "clearing", Ledger.GHS, false);
        }

        UUID txnId = UUID.randomUUID();
        String ref = "FLT-DEP-" + (externalRef != null ? externalRef : txnId.toString().substring(0, 8).toUpperCase());

        // Double-entry journal posting: strictly debit funding source, credit agent float
        var posted = ledger.post(Ledger.Posting.of("agent-float-replenish", ref, "float_deposit", List.of(
                Ledger.Leg.debit(fundingSource, amountPesewas),
                Ledger.Leg.credit(agentLedgerAccount, amountPesewas)
        )).describedAs("Agent float replenishment from: " + fundingSource));

        long before = current.availableFloatPesewas();
        long after = before + amountPesewas;

        db.sql("""
            UPDATE agent_float_account
            SET available_float_pesewas = available_float_pesewas + ?,
                updated_at = now()
            WHERE agent_id = ?
        """).params(amountPesewas, agentId).update();

        db.sql("""
            INSERT INTO agent_transaction
            (id, agent_id, transaction_type, customer_identifier, amount_pesewas,
             float_before_pesewas, float_after_pesewas, agent_commission_pesewas,
             platform_fee_pesewas, reference, status, journal_tx)
            VALUES (?, ?, 'FLOAT_DEPOSIT', 'AGENT_FLOAT', ?, ?, ?, 0, 0, ?, 'COMPLETED', ?)
        """).params(txnId, agentId, amountPesewas, before, after, ref, posted.transactionId()).update();

        return getFloatAccount(agentId).orElseThrow();
    }

    private boolean isMerchantAccount(String ref) {
        try {
            UUID id = UUID.fromString(ref);
            return ledger.account(Ledger.code("merchant", id, "available", Ledger.GHS)).isPresent();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Transactional
    public FloatAccount withdrawFloat(UUID agentId, long amountPesewas, String destinationBankOrWallet) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be greater than zero");
        }

        FloatAccount current = getFloatAccount(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent float account not found: " + agentId));

        if (current.availableFloatPesewas() < amountPesewas) {
            throw new IllegalStateException("Insufficient available float: " + current.availableFloatPesewas() + " pesewas");
        }

        String agentLedgerAccount = ledger.open("merchant", agentId, "available", Ledger.GHS);
        String destinationClearing = ledger.openSystem("system:clearing:bank:GHS", "clearing", Ledger.GHS, true);

        UUID txnId = UUID.randomUUID();
        String ref = "FLT-WDR-" + txnId.toString().substring(0, 8).toUpperCase();

        var posted = ledger.post(Ledger.Posting.of("agent-float-withdraw", ref, "float_withdrawal", List.of(
                Ledger.Leg.debit(agentLedgerAccount, amountPesewas),
                Ledger.Leg.credit(destinationClearing, amountPesewas)
        )).describedAs("Agent float withdrawal to: " + destinationBankOrWallet));

        long before = current.availableFloatPesewas();
        long after = before - amountPesewas;

        db.sql("""
            UPDATE agent_float_account
            SET available_float_pesewas = available_float_pesewas - ?,
                updated_at = now()
            WHERE agent_id = ?
        """).params(amountPesewas, agentId).update();

        db.sql("""
            INSERT INTO agent_transaction
            (id, agent_id, transaction_type, customer_identifier, amount_pesewas,
             float_before_pesewas, float_after_pesewas, agent_commission_pesewas,
             platform_fee_pesewas, reference, status, journal_tx)
            VALUES (?, ?, 'FLOAT_WITHDRAWAL', ?, ?, ?, ?, 0, 0, ?, 'COMPLETED', ?)
        """).params(txnId, agentId, destinationBankOrWallet, amountPesewas, before, after, ref, posted.transactionId()).update();

        return getFloatAccount(agentId).orElseThrow();
    }
}
