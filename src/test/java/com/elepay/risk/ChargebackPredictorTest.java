package com.elepay.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

@DisplayName("Algorithm 036: Chargeback Prediction and Multi-Currency Dispute Fee Handling")
class ChargebackPredictorTest extends PostgresTestBase {

    @Autowired ChargebackPredictionService chargebackPredictionService;
    @Autowired ChargebackPredictor chargebackPredictor;

    @Test
    @DisplayName("Algorithm 036: Multi-currency dispute fee conversion does not corrupt foreign currencies with flat GHS fees")
    void testMultiCurrencyDisputeFeeConversion() {
        // GHS dispute fee is 1,500 pesewas (15.00 GHS)
        assertThat(chargebackPredictionService.getDisputeFeeForCurrency("GHS")).isEqualTo(1_500L);

        // USD dispute fee is 1,500 cents ($15.00 USD)
        assertThat(chargebackPredictionService.getDisputeFeeForCurrency("USD")).isEqualTo(1_500L);

        // GBP dispute fee is 1,200 pence (£12.00 GBP)
        assertThat(chargebackPredictionService.getDisputeFeeForCurrency("GBP")).isEqualTo(1_200L);

        // NGN dispute fee is 500,000 kobo (5,000 NGN)
        assertThat(chargebackPredictionService.getDisputeFeeForCurrency("NGN")).isEqualTo(500_000L);

        // Verify prediction assessment for USD transaction
        var usdProfile = new ChargebackPredictionService.PaymentRiskProfile(
                "pmt-usd-" + UUID.randomUUID(),
                "merch-" + UUID.randomUUID(),
                "5411", // Grocery
                10000,  // $100.00
                "USD",
                false,
                "FRICTIONLESS",
                0.002,
                0
        );

        var assessment = chargebackPredictor.predictChargeback(usdProfile);
        assertThat(assessment).isNotNull();
        assertThat(assessment.calibratedProbability()).isGreaterThan(0.0).isLessThan(0.05);
        assertThat(assessment.recommendedAction()).isEqualTo(ChargebackPredictionService.SettlementAction.CLEAR);

        // Expected loss uses USD dispute fee: total exposure = 10,000 + 1,500 = 11,500 cents
        long expectedLoss = Math.round(assessment.calibratedProbability() * (10000 + 1500));
        assertThat(assessment.expectedLossMinorUnits()).isEqualTo(expectedLoss);
    }

    @Test
    @DisplayName("Algorithm 036: High dispute risk triggers HOLD_AND_REVIEW with appropriate explanation")
    void testHighDisputeRiskHoldsSettlement() {
        var highRiskProfile = new ChargebackPredictionService.PaymentRiskProfile(
                "pmt-high-" + UUID.randomUUID(),
                "merch-" + UUID.randomUUID(),
                "7995", // Gaming/gambling
                2000000, // 20,000 GHS
                "GHS",
                true,   // Cross border card
                "NONE", // No 3DS
                0.035,  // High merchant dispute rate
                3       // Buyer has 3 prior disputes
        );

        var assessment = chargebackPredictionService.predictChargeback(highRiskProfile);
        assertThat(assessment.calibratedProbability()).isGreaterThanOrEqualTo(0.15);
        assertThat(assessment.recommendedAction()).isEqualTo(ChargebackPredictionService.SettlementAction.HOLD_AND_REVIEW);
        assertThat(assessment.decisionExplanation()).contains("Critical dispute probability");
    }
}
