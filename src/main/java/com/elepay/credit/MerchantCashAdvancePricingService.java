package com.elepay.credit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 068: Merchant cash-advance (MCA) pricing.
 * Computes factor rate fixed-fee offers, holdback percentage, estimated duration, and equivalent APR disclosures.
 */
@Service
public class MerchantCashAdvancePricingService {

    private final JdbcClient db;

    public MerchantCashAdvancePricingService(JdbcClient db) {
        this.db = db;
    }

    public enum RiskTier {
        TIER_A(BigDecimal.valueOf(1.10), 1000), // 10% factor fee, 10% sweep
        TIER_B(BigDecimal.valueOf(1.14), 1200), // 14% factor fee, 12% sweep
        TIER_C(BigDecimal.valueOf(1.18), 1500), // 18% factor fee, 15% sweep
        TIER_D(BigDecimal.valueOf(1.24), 2000); // 24% factor fee, 20% sweep

        private final BigDecimal factorRate;
        private final int defaultHoldbackBps;

        RiskTier(BigDecimal factorRate, int defaultHoldbackBps) {
            this.factorRate = factorRate;
            this.defaultHoldbackBps = defaultHoldbackBps;
        }

        public BigDecimal getFactorRate() { return factorRate; }
        public int getDefaultHoldbackBps() { return defaultHoldbackBps; }
    }

    public record McaQuote(
            UUID id,
            UUID merchantId,
            long advanceAmountPesewas,
            BigDecimal factorRate,
            long fixedFeePesewas,
            long totalRepaymentPesewas,
            int holdbackPercentageBps,
            int estimatedDurationDays,
            int aprEquivalentBps,
            String status
    ) {}

    @Transactional
    public McaQuote createOffer(UUID merchantId, long advanceAmountPesewas, RiskTier tier, long projectedDailyVolumePesewas) {
        if (advanceAmountPesewas <= 0 || projectedDailyVolumePesewas <= 0) {
            throw new IllegalArgumentException("Advance amount and daily volume must be positive");
        }

        BigDecimal factorRate = tier.getFactorRate();
        long totalRepayment = factorRate.multiply(BigDecimal.valueOf(advanceAmountPesewas)).setScale(0, RoundingMode.HALF_UP).longValueExact();
        long fixedFee = totalRepayment - advanceAmountPesewas;

        int holdbackBps = tier.getDefaultHoldbackBps();
        double dailySweep = projectedDailyVolumePesewas * (holdbackBps / 10000.0);
        int estimatedDurationDays = (int) Math.max(1, Math.ceil(totalRepayment / Math.max(1.0, dailySweep)));

        // APR equivalent disclosure: ((fixedFee / advance) * (365 / duration)) * 10000 bps
        double feeRatio = (double) fixedFee / advanceAmountPesewas;
        double annualizer = 365.0 / estimatedDurationDays;
        int aprEquivalentBps = (int) Math.round(feeRatio * annualizer * 10000.0);

        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO mca_offer
            (id, merchant_id, advance_amount_pesewas, factor_rate, fixed_fee_pesewas, total_repayment_pesewas,
             holdback_percentage_bps, estimated_duration_days, apr_equivalent_bps, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'OFFERED')
        """).params(id, merchantId, advanceAmountPesewas, factorRate, fixedFee, totalRepayment,
                holdbackBps, estimatedDurationDays, aprEquivalentBps)
        .update();

        return new McaQuote(id, merchantId, advanceAmountPesewas, factorRate, fixedFee, totalRepayment,
                holdbackBps, estimatedDurationDays, aprEquivalentBps, "OFFERED");
    }

    @Transactional
    public void acceptOffer(UUID offerId) {
        int updated = db.sql("UPDATE mca_offer SET status = 'ACCEPTED', accepted_at = now() WHERE id = ? AND status = 'OFFERED'")
                .param(offerId)
                .update();
        if (updated == 0) {
            throw new IllegalStateException("Offer not found or not in OFFERED state: " + offerId);
        }
    }
}
