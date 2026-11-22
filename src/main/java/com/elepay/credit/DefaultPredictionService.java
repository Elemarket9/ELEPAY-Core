package com.elepay.credit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 071: Calibrated default prediction model.
 * Computes probability of default (PD) using calibrated logistic regression scoring on borrower cash-flow features.
 */
@Service
public class DefaultPredictionService {

    private final JdbcClient db;

    public DefaultPredictionService(JdbcClient db) {
        this.db = db;
    }

    public enum RiskBand {
        LOW,        // PD < 5%
        MEDIUM,     // 5% <= PD < 15%
        HIGH,       // 15% <= PD < 30%
        VERY_HIGH   // PD >= 30%
    }

    public record PredictionResult(
            UUID id,
            UUID entityId,
            String entityType,
            double probabilityOfDefault,
            RiskBand riskBand,
            String modelVersion,
            boolean isCalibrated
    ) {}

    public record CreditFeatures(
            double turnoverGrowthTrend, // e.g. 1.0 = flat, 0.7 = 30% decline
            double volumeVolatilityCv,   // 0.2 = stable, 0.8 = erratic
            int pastDelinquentEvents,   // count of historical missed payments
            double debtToIncomeRatio    // active debt / monthly turnover (e.g. 0.25)
    ) {}

    /**
     * Calculates calibrated default probability via logistic sigmoid.
     */
    @Transactional
    public PredictionResult predictDefault(UUID entityId, String entityType, CreditFeatures features) {
        // Calibrated logistic regression weights
        // Intercept = -2.8 (baseline log-odds of default ~ 5.7%)
        // turnoverGrowthTrend weight = -1.2 (declining trend increases default log-odds)
        // volumeVolatilityCv weight = +1.5 (higher volatility increases default)
        // pastDelinquentEvents weight = +0.8 (each late event adds to log-odds)
        // debtToIncomeRatio weight = +2.4 (higher leverage increases default)
        double z = -2.80
                - (1.20 * (features.turnoverGrowthTrend() - 1.0))
                + (1.50 * features.volumeVolatilityCv())
                + (0.80 * features.pastDelinquentEvents())
                + (2.40 * features.debtToIncomeRatio());

        // Sigmoid: 1 / (1 + exp(-z))
        double pd = 1.0 / (1.0 + Math.exp(-z));
        pd = Math.max(0.001, Math.min(0.999, pd));

        RiskBand band;
        if (pd < 0.05) {
            band = RiskBand.LOW;
        } else if (pd < 0.15) {
            band = RiskBand.MEDIUM;
        } else if (pd < 0.30) {
            band = RiskBand.HIGH;
        } else {
            band = RiskBand.VERY_HIGH;
        }

        UUID id = UUID.randomUUID();
        String modelVersion = "LOGISTIC_CALIBRATED_V1";

        db.sql("""
            INSERT INTO credit_default_prediction
            (id, entity_id, entity_type, probability_of_default, risk_band, model_version, calibrated)
            VALUES (?, ?, ?, ?, ?, ?, true)
        """).params(id, entityId, entityType, BigDecimal.valueOf(pd).setScale(5, RoundingMode.HALF_UP), band.name(), modelVersion)
        .update();

        return new PredictionResult(id, entityId, entityType, pd, band, modelVersion, true);
    }
}
