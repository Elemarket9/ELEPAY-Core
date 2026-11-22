package com.elepay.agents;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentFloatService {

    private final JdbcClient db;

    public AgentFloatService(JdbcClient db) {
        this.db = db;
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

    @Transactional
    public FloatAccount replenishFloat(UUID agentId, long amountPesewas, String externalRef) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Replenishment amount must be greater than zero");
        }

        FloatAccount current = getFloatAccount(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent float account not found: " + agentId));

        long before = current.availableFloatPesewas();
        long after = before + amountPesewas;

        db.sql("""
            UPDATE agent_float_account
            SET available_float_pesewas = available_float_pesewas + ?,
                updated_at = now()
            WHERE agent_id = ?
        """).params(amountPesewas, agentId).update();

        UUID txnId = UUID.randomUUID();
        String ref = "FLT-DEP-" + (externalRef != null ? externalRef : txnId.toString().substring(0, 8).toUpperCase());

        db.sql("""
            INSERT INTO agent_transaction
            (id, agent_id, transaction_type, customer_identifier, amount_pesewas,
             float_before_pesewas, float_after_pesewas, agent_commission_pesewas,
             platform_fee_pesewas, reference, status)
            VALUES (?, ?, 'FLOAT_DEPOSIT', 'AGENT_FLOAT', ?, ?, ?, 0, 0, ?, 'COMPLETED')
        """).params(txnId, agentId, amountPesewas, before, after, ref).update();

        return getFloatAccount(agentId).orElseThrow();
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

        long before = current.availableFloatPesewas();
        long after = before - amountPesewas;

        db.sql("""
            UPDATE agent_float_account
            SET available_float_pesewas = available_float_pesewas - ?,
                updated_at = now()
            WHERE agent_id = ?
        """).params(amountPesewas, agentId).update();

        UUID txnId = UUID.randomUUID();
        String ref = "FLT-WDR-" + txnId.toString().substring(0, 8).toUpperCase();

        db.sql("""
            INSERT INTO agent_transaction
            (id, agent_id, transaction_type, customer_identifier, amount_pesewas,
             float_before_pesewas, float_after_pesewas, agent_commission_pesewas,
             platform_fee_pesewas, reference, status)
            VALUES (?, ?, 'FLOAT_WITHDRAWAL', ?, ?, ?, ?, 0, 0, ?, 'COMPLETED')
        """).params(txnId, agentId, destinationBankOrWallet, amountPesewas, before, after, ref).update();

        return getFloatAccount(agentId).orElseThrow();
    }
}
