package com.elepay.credit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 070: Collections prioritization.
 * Triages delinquent accounts into recovery tiers using exposure, delinquency age, and expected recovery probability.
 */
@Service
public class CollectionsPrioritizationService {

    private final JdbcClient db;

    public CollectionsPrioritizationService(JdbcClient db) {
        this.db = db;
    }

    public enum AgingTier {
        TIER_1_EARLY_REMINDER,   // 1-15 days
        TIER_2_STRUCTURED_OUTREACH, // 16-30 days
        TIER_3_SPECIALIZED_RECOVERY, // 31-60 days
        TIER_4_LEGAL_WRITE_OFF   // 61+ days
    }

    public record DelinquentAccount(
            UUID id,
            UUID debtorId,
            String debtorType,
            long overdueAmountPesewas,
            int daysOverdue,
            double recoveryProbability,
            double priorityScore,
            AgingTier tier,
            String status
    ) {}

    /**
     * Computes priority score and categorizes delinquency case.
     * Formula: PriorityScore = (overdueAmountPesewas * recoveryProbability) / log2(daysOverdue + 2)
     */
    @Transactional
    public DelinquentAccount triageDelinquency(UUID debtorId, String debtorType, long overdueAmountPesewas, int daysOverdue) {
        if (overdueAmountPesewas <= 0 || daysOverdue <= 0) {
            throw new IllegalArgumentException("Overdue amount and days must be positive");
        }

        // Expected recovery probability decays with days overdue
        double recoveryProb = Math.max(0.05, Math.min(0.95, 0.95 - (daysOverdue * 0.012)));

        // Log2 denominator discount for aging
        double logAgeDiscount = Math.log(daysOverdue + 2.0) / Math.log(2.0);
        double priorityScore = (overdueAmountPesewas * recoveryProb) / logAgeDiscount;

        AgingTier tier;
        if (daysOverdue <= 15) {
            tier = AgingTier.TIER_1_EARLY_REMINDER;
        } else if (daysOverdue <= 30) {
            tier = AgingTier.TIER_2_STRUCTURED_OUTREACH;
        } else if (daysOverdue <= 60) {
            tier = AgingTier.TIER_3_SPECIALIZED_RECOVERY;
        } else {
            tier = AgingTier.TIER_4_LEGAL_WRITE_OFF;
        }

        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO collections_case
            (id, debtor_id, debtor_type, overdue_amount_pesewas, days_overdue, recovery_probability, priority_score, tier, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN')
        """).params(id, debtorId, debtorType, overdueAmountPesewas, daysOverdue,
                BigDecimal.valueOf(recoveryProb).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(priorityScore).setScale(4, RoundingMode.HALF_UP),
                tier.name())
        .update();

        return new DelinquentAccount(id, debtorId, debtorType, overdueAmountPesewas, daysOverdue, recoveryProb, priorityScore, tier, "OPEN");
    }

    public List<DelinquentAccount> getPrioritizedQueue(int limit) {
        return db.sql("SELECT id, debtor_id, debtor_type, overdue_amount_pesewas, days_overdue, recovery_probability, priority_score, tier, status FROM collections_case WHERE status = 'OPEN' ORDER BY priority_score DESC LIMIT ?")
                .param(limit)
                .query((rs, rowNum) -> new DelinquentAccount(
                        rs.getObject("id", UUID.class),
                        rs.getObject("debtor_id", UUID.class),
                        rs.getString("debtor_type"),
                        rs.getLong("overdue_amount_pesewas"),
                        rs.getInt("days_overdue"),
                        rs.getDouble("recovery_probability"),
                        rs.getDouble("priority_score"),
                        AgingTier.valueOf(rs.getString("tier")),
                        rs.getString("status")
                )).list();
    }
}
