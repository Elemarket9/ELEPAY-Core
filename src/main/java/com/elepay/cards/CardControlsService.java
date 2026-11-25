package com.elepay.cards;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardControlsService {

    private final JdbcClient db;

    public CardControlsService(JdbcClient db) {
        this.db = db;
    }

    public record MccRule(
            UUID id,
            UUID cardId,
            String ruleType, // WHITELIST, BLACKLIST
            String mccCode,
            String description,
            boolean isActive
    ) {}

    @Transactional
    public void freezeCard(UUID cardId, String reason) {
        int rows = db.sql("UPDATE card_registry SET status = 'FROZEN', updated_at = now() WHERE id = ? AND status = 'ACTIVE'")
                .param(cardId).update();
        if (rows == 0) {
            throw new IllegalStateException("Card not found or not ACTIVE: " + cardId);
        }
    }

    @Transactional
    public void unfreezeCard(UUID cardId) {
        int rows = db.sql("UPDATE card_registry SET status = 'ACTIVE', updated_at = now() WHERE id = ? AND status = 'FROZEN'")
                .param(cardId).update();
        if (rows == 0) {
            throw new IllegalStateException("Card not found or not FROZEN: " + cardId);
        }
    }

    @Transactional
    public void activatePhysicalCard(UUID cardId) {
        int rows = db.sql("UPDATE card_registry SET status = 'ACTIVE', updated_at = now() WHERE id = ? AND status = 'PENDING_ACTIVATION'")
                .param(cardId).update();
        if (rows == 0) {
            throw new IllegalStateException("Card not found or not in PENDING_ACTIVATION: " + cardId);
        }
    }

    @Transactional
    public void updateSpendingLimits(UUID cardId, long perTxLimitPesewas, long dailyLimitPesewas, long monthlyLimitPesewas) {
        if (perTxLimitPesewas <= 0 || dailyLimitPesewas <= 0 || monthlyLimitPesewas <= 0) {
            throw new IllegalArgumentException("Spending limits must be positive");
        }
        if (perTxLimitPesewas > dailyLimitPesewas || dailyLimitPesewas > monthlyLimitPesewas) {
            throw new IllegalArgumentException("Spending limits hierarchy violation: perTx <= daily <= monthly");
        }

        db.sql("""
            UPDATE card_registry
            SET per_tx_limit_pesewas = ?,
                daily_limit_pesewas = ?,
                monthly_limit_pesewas = ?,
                updated_at = now()
            WHERE id = ?
        """).params(perTxLimitPesewas, dailyLimitPesewas, monthlyLimitPesewas, cardId)
        .update();
    }

    @Transactional
    public MccRule addMccRule(UUID cardId, String ruleType, String mccCode, String description) {
        if (!"WHITELIST".equalsIgnoreCase(ruleType) && !"BLACKLIST".equalsIgnoreCase(ruleType)) {
            throw new IllegalArgumentException("Invalid rule type (must be WHITELIST or BLACKLIST): " + ruleType);
        }

        UUID ruleId = UUID.randomUUID();
        db.sql("""
            INSERT INTO card_mcc_rule (id, card_id, rule_type, mcc_code, description, is_active)
            VALUES (?, ?, ?, ?, ?, true)
            ON CONFLICT (card_id, rule_type, mcc_code)
            DO UPDATE SET is_active = true, description = EXCLUDED.description
        """).params(ruleId, cardId, ruleType.toUpperCase(), mccCode, description)
        .update();

        return new MccRule(ruleId, cardId, ruleType.toUpperCase(), mccCode, description, true);
    }

    @Transactional
    public void removeMccRule(UUID cardId, String ruleType, String mccCode) {
        db.sql("""
            UPDATE card_mcc_rule
            SET is_active = false
            WHERE card_id = ? AND rule_type = ? AND mcc_code = ?
        """).params(cardId, ruleType.toUpperCase(), mccCode)
        .update();
    }

    public List<MccRule> getActiveMccRules(UUID cardId) {
        return db.sql("""
            SELECT id, card_id, rule_type, mcc_code, description, is_active
            FROM card_mcc_rule
            WHERE card_id = ? AND is_active = true
        """).param(cardId)
        .query((rs, rowNum) -> new MccRule(
                rs.getObject("id", UUID.class),
                rs.getObject("card_id", UUID.class),
                rs.getString("rule_type"),
                rs.getString("mcc_code"),
                rs.getString("description"),
                rs.getBoolean("is_active")
        )).list();
    }
}
