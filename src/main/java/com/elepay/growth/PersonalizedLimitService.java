package com.elepay.growth;

import com.elepay.wallet.TransferLimits;
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
public class PersonalizedLimitService implements TransferLimits.PersonalizedLimitProvider {

    private final JdbcClient db;

    public PersonalizedLimitService(JdbcClient db) {
        this.db = db;
    }

    public record LimitProfile(
            UUID id,
            UUID customerId,
            int baseTier,
            int multiplierBps,
            double tierMultiplier,
            long maxDailyPesewas,
            long maxTransactionPesewas,
            long policyCeilingDailyPesewas,
            int riskScore,
            String reason,
            String status
    ) {
        public LimitProfile(UUID id, UUID customerId, int baseTier, int multiplierBps,
                            long maxDailyPesewas, long maxTransactionPesewas,
                            long policyCeilingDailyPesewas, int riskScore, String reason, String status) {
            this(id, customerId, baseTier, multiplierBps, multiplierBps / 10000.0,
                    maxDailyPesewas, maxTransactionPesewas, policyCeilingDailyPesewas, riskScore, reason, status);
        }
    }

    public record ActiveLimit(
            long maxDailyPesewas,
            long maxTransactionPesewas,
            long policyCeilingDailyPesewas
    ) {}

    // Statutory daily limits and absolute ceilings per KYC tier (in pesewas) aligned with Bank of Ghana
    // Tier 1: Base 30,000 (300 GHS), Ceiling 100,000 (1,000 GHS)
    // Tier 2: Base 200,000 (2,000 GHS), Ceiling 500,000 (5,000 GHS)
    // Tier 3: Base 1,000,000 (10,000 GHS), Ceiling 2,000,000 (20,000 GHS)
    public record TierBaseline(long baseDaily, long ceilingDaily, long basePerTxn) {}

    private TierBaseline getTierBaseline(int tier) {
        return switch (tier) {
            case 1 -> new TierBaseline(30_000L, 100_000L, 10_000L);
            case 2 -> new TierBaseline(200_000L, 500_000L, 100_000L);
            case 3 -> new TierBaseline(1_000_000L, 2_000_000L, 500_000L);
            default -> new TierBaseline(30_000L, 100_000L, 10_000L);
        };
    }

    public java.util.Optional<ActiveLimit> getActiveLimit(UUID customerId) {
        return db.sql("""
            SELECT max_daily_pesewas, max_transaction_pesewas, policy_ceiling_daily_pesewas
            FROM personalized_limit_profile
            WHERE customer_id = ? AND status = 'ACTIVE'
        """).param(customerId)
        .query((rs, i) -> new ActiveLimit(
                rs.getLong("max_daily_pesewas"),
                rs.getLong("max_transaction_pesewas"),
                rs.getLong("policy_ceiling_daily_pesewas")
        )).optional();
    }

    @Override
    public java.util.Optional<TransferLimits.PersonalizedLimitProvider.Limit> findPersonalizedLimit(UUID customerId) {
        return getActiveLimit(customerId)
                .map(a -> new TransferLimits.PersonalizedLimitProvider.Limit(
                        a.maxDailyPesewas(), a.maxTransactionPesewas(), a.policyCeilingDailyPesewas()
                ));
    }

    @Transactional
    public LimitProfile computePersonalizedLimit(UUID customerId, int kycTier, int riskScore, int tenureDays, int disputesCount) {
        TierBaseline baseline = getTierBaseline(kycTier);

        int multiplierBps;
        String reason;

        if (disputesCount > 0) {
            multiplierBps = 5_000; // Curtail to 50% due to chargebacks/disputes
            reason = "Reduced limit by 50% due to recent dispute history (" + disputesCount + ")";
        } else if (riskScore > 70) {
            multiplierBps = 6_000; // Contract to 60% due to elevated fraud risk
            reason = "Contracted limit by 40% due to elevated fraud risk score (" + riskScore + ")";
        } else if (tenureDays >= 90 && riskScore < 20) {
            multiplierBps = 14_000; // Elevate to 140% for trusted long-standing customer
            reason = "Elevated limit by 40% due to clean 90+ day track record and low risk score (" + riskScore + ")";
        } else if (tenureDays >= 30 && riskScore < 35) {
            multiplierBps = 11_500; // Elevate to 115% for reliable initial history
            reason = "Elevated limit by 15% due to reliable initial history";
        } else {
            multiplierBps = 10_000; // Baseline 100%
            reason = "Standard baseline KYC tier limit applied";
        }

        // Checked integer arithmetic in basis points (10,000 bps = 1.0000)
        long rawDaily = Math.multiplyExact(baseline.baseDaily(), (long) multiplierBps) / 10_000L;
        long dailyLimit = Math.min(baseline.ceilingDaily(), rawDaily);

        long rawTxn = Math.multiplyExact(baseline.basePerTxn(), (long) multiplierBps) / 10_000L;
        long txnLimit = Math.min(dailyLimit, rawTxn);

        BigDecimal multiplierDecimal = BigDecimal.valueOf(multiplierBps).divide(BigDecimal.valueOf(10_000), 2, RoundingMode.HALF_UP);

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
                multiplierDecimal,
                dailyLimit, txnLimit, baseline.ceilingDaily(), riskScore, reason)
        .update();

        return new LimitProfile(id, customerId, kycTier, multiplierBps, dailyLimit, txnLimit, baseline.ceilingDaily(), riskScore, reason, "ACTIVE");
    }
}
