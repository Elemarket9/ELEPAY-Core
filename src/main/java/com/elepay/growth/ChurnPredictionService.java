package com.elepay.growth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 098: Customer churn and dormancy survival analysis.
 * Predicts customer inactivity risk using Weibull survival modeling on recency and transaction cadence.
 * Privacy-preserving: uses strictly internal transactional frequency without third-party tracking.
 */
@Service
public class ChurnPredictionService {

    private final JdbcClient db;

    public ChurnPredictionService(JdbcClient db) {
        this.db = db;
    }

    public enum ChurnRiskTier {
        ACTIVE,     // P_churn < 15%
        AT_RISK,    // 15% <= P_churn < 50%
        DORMANT,    // 50% <= P_churn < 80%
        CHURNED     // P_churn >= 80%
    }

    public record ChurnScore(
            UUID id,
            UUID customerId,
            double dormancyProbability,
            int daysSinceLastTxn,
            double hazardRate,
            ChurnRiskTier riskTier
    ) {}

    /**
     * Evaluates dormancy hazard and survival probability using Weibull distribution.
     * Scale lambda = 1 / expected_cycle_days; Shape p = 1.4 (aging hazard increases with inactivity)
     */
    @Transactional
    public ChurnScore evaluateChurnRisk(UUID customerId, int daysSinceLastTxn, double avgTxnPerWeek) {
        // Inter-arrival cycle days scaled to dormancy horizon (7 to 30 days)
        double cycleDays = avgTxnPerWeek > 0 ? (7.0 / avgTxnPerWeek) : 14.0;
        double scaleDays = Math.max(7.0, Math.min(30.0, cycleDays * 2.0));
        double lambda = 1.0 / scaleDays;
        double p = 1.35; // Shape parameter > 1: increasing hazard over time

        double lt = lambda * daysSinceLastTxn;
        // Survival S(t) = exp(-(lt)^p)
        double survival = Math.exp(-Math.pow(lt, p));
        double dormancyProb = Math.max(0.001, Math.min(0.999, 1.0 - survival));

        // Hazard rate h(t) = lambda * p * (lt)^(p - 1)
        double hazardRate = lambda * p * Math.pow(Math.max(0.0001, lt), p - 1.0);

        ChurnRiskTier tier;
        if (dormancyProb < 0.15) {
            tier = ChurnRiskTier.ACTIVE;
        } else if (dormancyProb < 0.50) {
            tier = ChurnRiskTier.AT_RISK;
        } else if (dormancyProb < 0.80) {
            tier = ChurnRiskTier.DORMANT;
        } else {
            tier = ChurnRiskTier.CHURNED;
        }

        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO customer_churn_score
            (id, customer_id, dormancy_probability, days_since_last_txn, hazard_rate, churn_risk_tier)
            VALUES (?, ?, ?, ?, ?, ?)
        """).params(id, customerId,
                BigDecimal.valueOf(dormancyProb).setScale(4, RoundingMode.HALF_UP),
                daysSinceLastTxn,
                BigDecimal.valueOf(hazardRate).setScale(5, RoundingMode.HALF_UP),
                tier.name())
        .update();

        return new ChurnScore(id, customerId, dormancyProb, daysSinceLastTxn, hazardRate, tier);
    }
}
