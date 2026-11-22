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
 * Algorithm 065: Cash-flow underwriting.
 * Assesses merchant creditworthiness based on gross turnover, revenue consistency (CV), and refund ratios.
 * Produces explainable decision trees and recommended credit capacity.
 */
@Service
public class CashFlowUnderwritingService {

    private final JdbcClient db;

    public CashFlowUnderwritingService(JdbcClient db) {
        this.db = db;
    }

    public record DailyTradingVolume(long grossPesewas, long refundPesewas) {}

    public record UnderwritingDecision(
            UUID id,
            UUID merchantId,
            long monthlyTurnoverPesewas,
            int refundRateBps,
            double volatilityScore,
            long maxCreditPesewas,
            BigDecimal recommendedFactorRate,
            String decision,
            String reason,
            String featuresJson
    ) {}

    @Transactional
    public UnderwritingDecision evaluateMerchant(UUID merchantId, List<DailyTradingVolume> dailyTradingHistory, int operatingDays) {
        UUID id = UUID.randomUUID();

        if (operatingDays < 60) {
            String reason = "Operating history (" + operatingDays + " days) is below mandatory threshold of 60 days";
            saveAssessment(id, merchantId, 0L, 0, 1.0, 0L, BigDecimal.valueOf(1.25), "DECLINED", reason, "{}");
            return new UnderwritingDecision(id, merchantId, 0L, 0, 1.0, 0L, BigDecimal.valueOf(1.25), "DECLINED", reason, "{}");
        }

        if (dailyTradingHistory == null || dailyTradingHistory.isEmpty()) {
            String reason = "No trading activity found for evaluation period";
            saveAssessment(id, merchantId, 0L, 0, 1.0, 0L, BigDecimal.valueOf(1.25), "DECLINED", reason, "{}");
            return new UnderwritingDecision(id, merchantId, 0L, 0, 1.0, 0L, BigDecimal.valueOf(1.25), "DECLINED", reason, "{}");
        }

        long totalGross = 0;
        long totalRefunds = 0;
        for (DailyTradingVolume day : dailyTradingHistory) {
            totalGross += day.grossPesewas();
            totalRefunds += day.refundPesewas();
        }

        int days = dailyTradingHistory.size();
        double meanDaily = (double) totalGross / days;
        long monthlyTurnover = Math.round(meanDaily * 30.0);

        int refundRateBps = totalGross > 0 ? (int) Math.round(((double) totalRefunds / totalGross) * 10000.0) : 0;

        // Volatility score: Coefficient of variation (stdDev / mean)
        double sumSq = 0;
        for (DailyTradingVolume day : dailyTradingHistory) {
            sumSq += Math.pow(day.grossPesewas() - meanDaily, 2);
        }
        double stdDev = Math.sqrt(sumSq / Math.max(1, days - 1));
        double cv = meanDaily > 0 ? (stdDev / meanDaily) : 1.0;

        // Decision logic
        if (monthlyTurnover < 50_000L) { // Min 500 GHS monthly turnover
            String reason = "Monthly turnover (" + monthlyTurnover + " pesewas) is below minimum qualification threshold";
            saveAssessment(id, merchantId, monthlyTurnover, refundRateBps, cv, 0L, BigDecimal.valueOf(1.25), "DECLINED", reason, "{}");
            return new UnderwritingDecision(id, merchantId, monthlyTurnover, refundRateBps, cv, 0L, BigDecimal.valueOf(1.25), "DECLINED", reason, "{}");
        }

        if (refundRateBps > 800) { // Over 8% refund rate is excessive
            String reason = "Refund rate (" + (refundRateBps / 100.0) + "%) exceeds maximum risk tolerance of 8.0%";
            saveAssessment(id, merchantId, monthlyTurnover, refundRateBps, cv, 0L, BigDecimal.valueOf(1.25), "DECLINED", reason, "{}");
            return new UnderwritingDecision(id, merchantId, monthlyTurnover, refundRateBps, cv, 0L, BigDecimal.valueOf(1.25), "DECLINED", reason, "{}");
        }

        // Capacity calculation: 30% of monthly turnover, adjusted by consistency (1 - CV * 0.5)
        double consistencyMultiplier = Math.max(0.5, Math.min(1.0, 1.0 - (cv * 0.3)));
        long maxCredit = Math.round(monthlyTurnover * 0.35 * consistencyMultiplier);

        // Factor rate pricing recommendation based on risk profile
        BigDecimal factorRate = BigDecimal.valueOf(1.10 + (cv * 0.05) + (refundRateBps / 20000.0)).setScale(4, RoundingMode.HALF_UP);

        String decision = "APPROVED";
        String reason = String.format("Approved with capacity %d pesewas based on consistent turnover (%d pesewas/mo) and healthy refund rate (%d bps)",
                maxCredit, monthlyTurnover, refundRateBps);
        String featuresJson = String.format("{\"monthlyTurnover\":%d,\"refundRateBps\":%d,\"cv\":%.4f,\"consistencyMultiplier\":%.4f}",
                monthlyTurnover, refundRateBps, cv, consistencyMultiplier);

        saveAssessment(id, merchantId, monthlyTurnover, refundRateBps, cv, maxCredit, factorRate, decision, reason, featuresJson);

        return new UnderwritingDecision(id, merchantId, monthlyTurnover, refundRateBps, cv, maxCredit, factorRate, decision, reason, featuresJson);
    }

    private void saveAssessment(UUID id, UUID merchantId, long turnover, int refundBps, double volatility,
                                long maxCredit, BigDecimal factorRate, String decision, String reason, String features) {
        db.sql("""
            INSERT INTO credit_underwriting_assessment
            (id, merchant_id, evaluated_at, monthly_turnover_pesewas, refund_rate_bps, volatility_score,
             max_credit_pesewas, recommended_factor_rate, decision, reason, features_json)
            VALUES (?, ?, now(), ?, ?, ?, ?, ?, ?, ?, ?)
        """).params(id, merchantId, turnover, refundBps, BigDecimal.valueOf(volatility).setScale(4, RoundingMode.HALF_UP),
                maxCredit, factorRate, decision, reason, features)
        .update();
    }
}
