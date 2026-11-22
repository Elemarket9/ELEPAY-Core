package com.elepay.credit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 067: Overdraft eligibility scoring.
 * Assesses wallet overdraft qualification based on deposit cadence, tenure, and wallet activity.
 * Strict regulatory policy: disabled for live use until authorized.
 */
@Service
public class OverdraftScoringService {

    private final JdbcClient db;

    public OverdraftScoringService(JdbcClient db) {
        this.db = db;
    }

    public record OverdraftAssessment(
            UUID id,
            UUID customerId,
            int creditScore,
            long qualifiedLimitPesewas,
            long currentUtilizationPesewas,
            double monthlyDepositCadenceScore,
            String status,
            boolean liveEnabled
    ) {}

    @Transactional
    public OverdraftAssessment evaluateOverdraft(UUID customerId, int monthlyDepositCount, long avgDepositPesewas,
                                                 int walletTenureDays, long minBalancePesewas, boolean liveRequested) {
        if (liveRequested) {
            throw new IllegalStateException("Overdraft facility is strictly disabled for live use per regulatory policy");
        }

        // Scoring: 0 to 1000
        // Cadence: up to 350 pts (35 pts per deposit/mo up to 10)
        int cadenceScore = Math.min(350, monthlyDepositCount * 35);
        // Average amount: up to 250 pts
        int amountScore = (int) Math.min(250, (avgDepositPesewas / 2000L) * 10);
        // Tenure: up to 200 pts (min 30 days required)
        int tenureScore = walletTenureDays >= 30 ? Math.min(200, (walletTenureDays / 30) * 40) : 0;
        // Balance buffer: up to 200 pts
        int balanceScore = (int) Math.min(200, (minBalancePesewas / 1000L) * 10);

        int totalScore = cadenceScore + amountScore + tenureScore + balanceScore;

        long qualifiedLimit = 0L;
        String status;

        if (walletTenureDays < 30 || totalScore < 600) {
            status = "INELIGIBLE";
        } else if (totalScore < 750) {
            status = "QUALIFIED_TIER_1";
            qualifiedLimit = 20_000L; // 200 GHS
        } else if (totalScore < 850) {
            status = "QUALIFIED_TIER_2";
            qualifiedLimit = 50_000L; // 500 GHS
        } else {
            status = "QUALIFIED_TIER_3";
            qualifiedLimit = 100_000L; // 1,000 GHS
        }

        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO overdraft_facility
            (id, customer_id, credit_score, qualified_limit_pesewas, current_utilization_pesewas,
             monthly_deposit_cadence_score, status, live_enabled)
            VALUES (?, ?, ?, ?, 0, ?, ?, false)
            ON CONFLICT (customer_id) DO UPDATE SET
                credit_score = EXCLUDED.credit_score,
                qualified_limit_pesewas = EXCLUDED.qualified_limit_pesewas,
                monthly_deposit_cadence_score = EXCLUDED.monthly_deposit_cadence_score,
                status = EXCLUDED.status,
                evaluated_at = now()
        """).params(id, customerId, totalScore, qualifiedLimit, BigDecimal.valueOf(cadenceScore).setScale(2, RoundingMode.HALF_UP), status)
        .update();

        return new OverdraftAssessment(id, customerId, totalScore, qualifiedLimit, 0L, cadenceScore, status, false);
    }
}
