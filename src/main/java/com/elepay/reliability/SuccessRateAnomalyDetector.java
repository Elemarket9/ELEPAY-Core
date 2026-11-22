package com.elepay.reliability;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 091: Success-rate anomaly detection.
 * Statistical conversion monitoring using rolling Z-scores to flag route degradations.
 */
@Service
public class SuccessRateAnomalyDetector {

    private final JdbcClient db;

    public SuccessRateAnomalyDetector(JdbcClient db) {
        this.db = db;
    }

    public record AnomalyEvaluation(
            UUID id,
            String routeOrRail,
            double baselineConversionPct,
            double currentConversionPct,
            int sampleSize,
            double zScore,
            boolean isAnomaly,
            String reason
    ) {}

    /**
     * Checks if current success/conversion rate deviates significantly from baseline.
     */
    @Transactional
    public AnomalyEvaluation evaluateSuccessRate(String routeOrRail, double baselineConversionPct,
                                                int successfulTxns, int totalTxns) {
        if (totalTxns < 30) {
            return new AnomalyEvaluation(null, routeOrRail, baselineConversionPct,
                    totalTxns > 0 ? (double) successfulTxns / totalTxns * 100.0 : 0.0,
                    totalTxns, 0.0, false, "INSUFFICIENT_SAMPLE_SIZE_MINIMUM_30");
        }

        double p0 = baselineConversionPct / 100.0;
        double pHat = (double) successfulTxns / totalTxns;
        double currentPct = pHat * 100.0;

        // Standard error of sample proportion = sqrt(p0 * (1 - p0) / n)
        double stdError = Math.sqrt((p0 * (1.0 - p0)) / totalTxns);
        double zScore = (stdError > 0) ? (pHat - p0) / stdError : 0.0;

        // Negative anomaly: Z-score < -2.58 (99% statistical significance drop)
        boolean isAnomaly = zScore < -2.58;
        String reason = isAnomaly ? String.format("Significant conversion drop: %.1f%% vs baseline %.1f%% (Z=%.2f)",
                currentPct, baselineConversionPct, zScore) : "NORMAL";

        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO conversion_anomaly_record
            (id, route_or_rail, baseline_conversion_pct, current_conversion_pct, sample_size, z_score, is_anomaly)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """).params(id, routeOrRail,
                BigDecimal.valueOf(baselineConversionPct).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(currentPct).setScale(2, RoundingMode.HALF_UP),
                totalTxns,
                BigDecimal.valueOf(zScore).setScale(3, RoundingMode.HALF_UP),
                isAnomaly)
        .update();

        return new AnomalyEvaluation(id, routeOrRail, baselineConversionPct, currentPct, totalTxns, zScore, isAnomaly, reason);
    }
}
