package com.elepay.growth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 100: Personalized limits.
 * Calculates risk-based personalized transaction and daily velocity limits under strict regulatory policy ceilings.
 * Explains limit assignment and prevents unauthorized manual limit inflation.
 */
@Service
public class PersonalizedLimitService {

    private final JdbcClient db;

    public PersonalizedLimitService(JdbcClient db) {
        this.db = db;
    }

    public record LimitProfile(
            UUID id,
            UUID customerId,
            int baseTier,
            double tierMultiplier,
            long maxDailyPesewas,
            long maxTransactionPesewas,
            long policyCeilingDailyPesewas,
            int riskScore,
            String reason,
            String status
    ) {}

    // Statutory daily limits and absolute ceilings per KYC tier (in pesewas)
    // Tier 1: Base 30,000 (300 GHS), Ceiling 50,000 (500 GHS)
    // Tier 2: Base 200,000 (2,000 GHS), Ceiling 500,000 (5,000 GHS)
    // Tier 3: Base 1,000,000 (10,000 GHS), Ceiling 2,000,000 (20,000 GHS)
    public record TierBaseline(long baseDaily, long ceilingDaily, long basePerTxn) {}

    private TierBaseline getTierBaseline(int tier) {
        return switch (tier) {
            case 1 -> new TierBaseline(30_000L, 50_000L, 10_000L);
            case 2 -> new TierBaseline(200_000L, 500_000L, 100_000L);
            case 3 -> new TierBaseline(1_000_000L, 2_000_000L, 500_000L);
            default -> new TierBaseline(30_000L, 50_000L, 10_000L);
        };
    }

    @Transactional
    public LimitProfile computePersonalizedLimit(UUID customerId, int kycTier, int riskScore, int tenureDays, int disputesCount) {
        TierBaseline baseline = getTierBaseline(kycTier);

        double multiplier = 1.0;
        String reason;

        if (disputesCount > 0) {
            multiplier = 0.50; // Curtail due to chargebacks/disputes
            reason = "Reduced limit by 50% due to recent dispute history (" + disputesCount + ")";
        } else if (riskScore > 70) {
            multiplier = 0.60; // Elevated fraud risk
            reason = "Contracted limit by 40% due to elevated fraud risk score (" + riskScore + ")";
        } else if (tenureDays >= 90 && riskScore < 20) {
            multiplier = 1.40; // Trusted long-standing customer
            reason = "Elevated limit by 40% due to clean 90+ day track record and low risk score (" + riskScore + ")";
        } else if (tenureDays >= 30 && riskScore < 35) {
            multiplier = 1.15; // Positive initial track record
            reason = "Elevated limit by 15% due to reliable initial history";
        } else {
            multiplier = 1.00;
            reason = "Standard baseline KYC tier limit applied";
        }

        // Apply multiplier and cap strictly at policy statutory ceiling
        long rawDaily = Math.round(baseline.baseDaily() * multiplier);
        long dailyLimit = Math.min(baseline.ceilingDaily(), rawDaily);

        long rawTxn = Math.round(baseline.basePerTxn() * multiplier);
        long txnLimit = Math.min(dailyLimit, rawTxn);

        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO personalized_limit_profile
            (id, customer_id, base_tier, tier_multiplier, max_daily_pesewas, max_transaction_pesewas,
             policy_ceiling_daily_pesewas, risk_score, reason, status, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', now())
            ON CONFLICT (customer_id) DO UPDATE SET
                base_tier = EXCLUDED.base_tier,
                tier_multiplier = EXCLUDED.tier_multiplier,
                max_daily_pesewas = EXCLUDED.max_daily_pesewas,
                max_transaction_pesewas = EXCLUDED.max_transaction_pesewas,
                risk_score = EXCLUDED.risk_score,
                reason = EXCLUDED.reason,
                updated_at = now()
        """).params(id, customerId, kycTier,
                BigDecimal.valueOf(multiplier).setScale(2, RoundingMode.HALF_UP),
                dailyLimit, txnLimit, baseline.ceilingDaily(), riskScore, reason)
        .update();

        return new LimitProfile(id, customerId, kycTier, multiplier, dailyLimit, txnLimit, baseline.ceilingDaily(), riskScore, reason, "ACTIVE");
    }
}
