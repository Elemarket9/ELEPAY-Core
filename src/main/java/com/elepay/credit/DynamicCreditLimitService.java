package com.elepay.credit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 072: Dynamic credit limits.
 * Dynamically adjusts available credit lines based on real-time turnover velocity and delinquency signals.
 * Enforces bounded adjustments and statutory policy ceilings.
 */
@Service
public class DynamicCreditLimitService {

    private final JdbcClient db;

    public DynamicCreditLimitService(JdbcClient db) {
        this.db = db;
    }

    public record CreditLimitProfile(
            UUID id,
            UUID entityId,
            String entityType,
            long baselineLimitPesewas,
            long currentLimitPesewas,
            long utilizationPesewas,
            double healthScore,
            String reason
    ) {}

    @Transactional
    public CreditLimitProfile initializeProfile(UUID entityId, String entityType, long baselineLimitPesewas) {
        if (baselineLimitPesewas <= 0) {
            throw new IllegalArgumentException("Baseline credit limit must be positive");
        }
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO dynamic_credit_limit
            (id, entity_id, entity_type, baseline_limit_pesewas, current_limit_pesewas, utilization_pesewas, health_score, reason)
            VALUES (?, ?, ?, ?, ?, 0, 75.0, 'Initial baseline limit assignment')
            ON CONFLICT (entity_id, entity_type) DO NOTHING
        """).params(id, entityId, entityType, baselineLimitPesewas, baselineLimitPesewas)
        .update();

        return getProfile(entityId, entityType);
    }

    public CreditLimitProfile getProfile(UUID entityId, String entityType) {
        return db.sql("SELECT id, entity_id, entity_type, baseline_limit_pesewas, current_limit_pesewas, utilization_pesewas, health_score, reason FROM dynamic_credit_limit WHERE entity_id = ? AND entity_type = ?")
                .params(entityId, entityType)
                .query((rs, rowNum) -> new CreditLimitProfile(
                        rs.getObject("id", UUID.class),
                        rs.getObject("entity_id", UUID.class),
                        rs.getString("entity_type"),
                        rs.getLong("baseline_limit_pesewas"),
                        rs.getLong("current_limit_pesewas"),
                        rs.getLong("utilization_pesewas"),
                        rs.getDouble("health_score"),
                        rs.getString("reason")
                )).single();
    }

    /**
     * Re-evaluates credit limit given monthly turnover trend, refund rate, and hard regulatory ceiling.
     */
    @Transactional
    public CreditLimitProfile adjustLimit(UUID entityId, String entityType, double salesTrendRatio,
                                         int refundRateBps, boolean hasDelinquencies, long hardCeilingPesewas) {
        if (!Double.isFinite(salesTrendRatio) || salesTrendRatio < 0.0) {
            throw new IllegalArgumentException("salesTrendRatio must be finite and non-negative");
        }
        if (refundRateBps < 0 || refundRateBps > 10000) {
            throw new IllegalArgumentException("refundRateBps must be between 0 and 10000");
        }
        if (hardCeilingPesewas < 0) {
            throw new IllegalArgumentException("hardCeilingPesewas cannot be negative");
        }

        record LockedProfile(CreditLimitProfile profile, java.time.OffsetDateTime lastAdjustedAt) {}
        LockedProfile locked = db.sql("""
                SELECT id, entity_id, entity_type, baseline_limit_pesewas, current_limit_pesewas,
                       utilization_pesewas, health_score, reason, last_adjusted_at
                FROM dynamic_credit_limit
                WHERE entity_id = ? AND entity_type = ?
                FOR UPDATE
                """)
                .params(entityId, entityType)
                .query((rs, rowNum) -> new LockedProfile(
                        new CreditLimitProfile(
                                rs.getObject("id", UUID.class),
                                rs.getObject("entity_id", UUID.class),
                                rs.getString("entity_type"),
                                rs.getLong("baseline_limit_pesewas"),
                                rs.getLong("current_limit_pesewas"),
                                rs.getLong("utilization_pesewas"),
                                rs.getDouble("health_score"),
                                rs.getString("reason")
                        ),
                        rs.getObject("last_adjusted_at", java.time.OffsetDateTime.class)
                )).single();

        CreditLimitProfile profile = locked.profile();
        long currentLimit = profile.currentLimitPesewas();
        long newLimit = currentLimit;
        String reason;
        double healthScore = 75.0;
        long policyMaxCeiling = Math.min(hardCeilingPesewas, Math.multiplyExact(profile.baselineLimitPesewas(), 5L));

        if (hasDelinquencies) {
            // Immediate curtailment down to current utilization or 50% cut
            newLimit = Math.max(profile.utilizationPesewas(), Math.round(currentLimit * 0.50));
            healthScore = 30.0;
            reason = "Curtailed credit limit due to active delinquency";
        } else if (refundRateBps > 500) {
            // Elevated refund rate -> curtail 20%
            newLimit = Math.max(profile.utilizationPesewas(), Math.round(currentLimit * 0.80));
            healthScore = 50.0;
            reason = "Contracted limit by 20% due to elevated refund rate (" + (refundRateBps / 100.0) + "%)";
        } else if (salesTrendRatio >= 1.25) {
            // Cooldown guard: prevent unbounded 1.2^N compounding within 24h of a prior expansion
            boolean recentlyExpanded = locked.lastAdjustedAt() != null
                    && locked.lastAdjustedAt().isAfter(java.time.OffsetDateTime.now().minusHours(24))
                    && profile.reason() != null && profile.reason().startsWith("Expanded limit");
            if (recentlyExpanded) {
                newLimit = currentLimit;
                healthScore = 90.0;
                reason = "Maintained existing limit; expansion cooldown active";
            } else {
                long expanded = Math.round(currentLimit * 1.20);
                newLimit = Math.max(profile.utilizationPesewas(), Math.min(policyMaxCeiling, expanded));
                healthScore = 90.0;
                reason = "Expanded limit by 20% based on strong sales growth (" + Math.round(salesTrendRatio * 100) + "%)";
            }
        } else if (salesTrendRatio <= 0.70) {
            // Sales contraction (-30%) -> decrease limit by 15%
            newLimit = Math.max(profile.utilizationPesewas(), Math.round(currentLimit * 0.85));
            healthScore = 55.0;
            reason = "Decreased limit by 15% reflecting 30%+ sales decline";
        } else {
            reason = "Maintained existing limit; healthy operations";
            healthScore = 80.0;
        }

        db.sql("""
            UPDATE dynamic_credit_limit
            SET current_limit_pesewas = ?,
                health_score = ?,
                reason = ?,
                last_adjusted_at = now()
            WHERE entity_id = ? AND entity_type = ?
        """).params(newLimit, BigDecimal.valueOf(healthScore).setScale(2, RoundingMode.HALF_UP), reason, entityId, entityType)
        .update();

        return new CreditLimitProfile(profile.id(), entityId, entityType, profile.baselineLimitPesewas(),
                newLimit, profile.utilizationPesewas(), healthScore, reason);
    }
}
