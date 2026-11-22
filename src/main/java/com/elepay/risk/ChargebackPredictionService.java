package com.elepay.risk;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Algorithm 036: Chargeback prediction.
 * Estimates dispute and chargeback risk using a calibrated probability model prior to merchant
 * settlement, calculating expected financial loss and issuing automated settlement hold/reserve actions.
 */
@Service
public class ChargebackPredictionService {

    private static final long FIXED_CHARGEBACK_FEE_MINOR_UNITS = 1_500L; // 15.00 GHS partner dispute fee

    private final JdbcClient db;

    public ChargebackPredictionService(JdbcClient db) {
        this.db = db;
    }

    public enum SettlementAction {
        CLEAR,
        DELAY_SETTLEMENT,
        REQUIRE_RESERVE,
        HOLD_AND_REVIEW
    }

    public record PaymentRiskProfile(
            String paymentId,
            String merchantId,
            String mcc,
            long amountMinorUnits,
            String currency,
            boolean isCrossBorderCard,
            String threeDsStatus, // FRICTIONLESS, CHALLENGED, EXEMPTED, NONE
            double merchantHistoricalDisputeRate,
            int buyerDisputeHistoryCount
    ) {}

    public record ChargebackAssessment(
            String paymentId,
            String merchantId,
            double calibratedProbability,
            long expectedLossMinorUnits,
            SettlementAction recommendedAction,
            String decisionExplanation,
            OffsetDateTime predictedAt
    ) {}

    @Transactional
    public ChargebackAssessment predictChargeback(PaymentRiskProfile profile) {
        OffsetDateTime now = OffsetDateTime.now();

        // Base calibrated log-odds prior for general chargeback rate (~0.8% baseline)
        double z = -4.8;

        // MCC Category risk
        if ("7995".equals(profile.mcc()) || "5734".equals(profile.mcc()) || "4816".equals(profile.mcc())) {
            z += 1.2; // Higher dispute categories (gaming, digital software, cyberlockers)
        } else if ("5411".equals(profile.mcc()) || "5541".equals(profile.mcc())) {
            z -= 0.6; // Grocery, fuel (low dispute categories)
        }

        // Cross border card mismatch
        if (profile.isCrossBorderCard()) {
            z += 0.8;
        }

        // 3-D Secure protection
        if ("CHALLENGED".equalsIgnoreCase(profile.threeDsStatus())) {
            z -= 1.5; // Strong liability shift to issuer
        } else if ("FRICTIONLESS".equalsIgnoreCase(profile.threeDsStatus())) {
            z -= 0.8; // Authenticated frictionless
        } else if ("NONE".equalsIgnoreCase(profile.threeDsStatus())) {
            z += 1.8; // No 3DS: maximum merchant liability
        }

        // Merchant historical dispute rate
        if (profile.merchantHistoricalDisputeRate() >= 0.015) {
            z += 1.4;
        } else if (profile.merchantHistoricalDisputeRate() >= 0.008) {
            z += 0.6;
        }

        // Buyer dispute history
        if (profile.buyerDisputeHistoryCount() >= 2) {
            z += 1.6;
        } else if (profile.buyerDisputeHistoryCount() == 1) {
            z += 0.7;
        }

        // High amount effect
        if (profile.amountMinorUnits() > 500_000L) { // > 5,000 GHS
            z += 0.5;
        }

        // Calibrated probability via sigmoid: 1 / (1 + exp(-z))
        double probability = 1.0 / (1.0 + Math.exp(-z));
        probability = Math.max(0.0001, Math.min(0.9999, probability));

        // Expected financial loss = P(chargeback) * (Amount + ChargebackFee)
        long totalExposureMinorUnits = profile.amountMinorUnits() + FIXED_CHARGEBACK_FEE_MINOR_UNITS;
        long expectedLoss = Math.round(probability * totalExposureMinorUnits);

        SettlementAction action;
        String explanation;

        if (probability >= 0.15) {
            action = SettlementAction.HOLD_AND_REVIEW;
            explanation = String.format("Critical dispute probability (%.2f%%). Expected loss: %d minor units. Settlement held pending review",
                    probability * 100, expectedLoss);
        } else if (probability >= 0.06) {
            action = SettlementAction.REQUIRE_RESERVE;
            explanation = String.format("Elevated dispute probability (%.2f%%). Expected loss: %d minor units. Rolling reserve allocation required",
                    probability * 100, expectedLoss);
        } else if (probability >= 0.02) {
            action = SettlementAction.DELAY_SETTLEMENT;
            explanation = String.format("Moderate dispute probability (%.2f%%). Delaying settlement payout by 48h observation window",
                    probability * 100);
        } else {
            action = SettlementAction.CLEAR;
            explanation = String.format("Low dispute probability (%.2f%%). Cleared for immediate settlement batching",
                    probability * 100);
        }

        // Persist prediction
        db.sql("""
            INSERT INTO risk_chargeback_predictions (
                id, payment_id, merchant_id, mcc, amount_minor_units, currency,
                calibrated_probability, expected_loss_minor_units, recommended_action,
                decision_explanation, predicted_at
            ) VALUES (
                :id, :pmtId, :merchId, :mcc, :amt, :curr,
                :prob, :loss, :action,
                :exp, :now
            )
        """)
                .param("id", UUID.randomUUID())
                .param("pmtId", profile.paymentId())
                .param("merchId", profile.merchantId())
                .param("mcc", profile.mcc())
                .param("amt", profile.amountMinorUnits())
                .param("curr", profile.currency())
                .param("prob", probability)
                .param("loss", expectedLoss)
                .param("action", action.name())
                .param("exp", explanation)
                .param("now", now)
                .update();

        return new ChargebackAssessment(
                profile.paymentId(),
                profile.merchantId(),
                probability,
                expectedLoss,
                action,
                explanation,
                now
        );
    }
}
