package com.elepay.credit;

import com.elepay.PostgresTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CreditPlatformTest extends PostgresTestBase {

    @Autowired
    private InterestAccrualService interestService;

    @Autowired
    private FloatForecastingService floatForecastingService;

    @Autowired
    private FloatRebalancingService floatRebalancingService;

    @Autowired
    private MerchantCashFlowForecastService merchantForecastService;

    @Autowired
    private CashFlowUnderwritingService underwritingService;

    @Autowired
    private EwaEligibilityService ewaService;

    @Autowired
    private OverdraftScoringService overdraftService;

    @Autowired
    private MerchantCashAdvancePricingService mcaPricingService;

    @Autowired
    private RepaymentSweepsService sweepsService;

    @Autowired
    private CollectionsPrioritizationService collectionsService;

    @Autowired
    private DefaultPredictionService defaultPredictionService;

    @Autowired
    private DynamicCreditLimitService dynamicLimitService;

    @Test
    @DisplayName("Algorithm 008: Interest and yield accrual with day-count conventions and live disablement")
    void testInterestAndYieldAccrual() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 7, 1); // 181 days

        // Verify day-count conventions
        double act365 = DayCountConvention.ACT_365.yearFraction(start, end);
        assertThat(act365).isEqualTo(181.0 / 365.0);

        double act360 = DayCountConvention.ACT_360.yearFraction(start, end);
        assertThat(act360).isEqualTo(181.0 / 360.0);

        double thirty360 = DayCountConvention.THIRTY_360.yearFraction(start, end);
        assertThat(thirty360).isEqualTo(180.0 / 360.0);

        // Calculate accrual: 10,000 GHS (1,000,000 pesewas) @ 12% (1200 bps)
        long principal = 1_000_000L;
        long accrued = interestService.calculateAccrual(principal, 1200, start, end, DayCountConvention.ACT_365);
        assertThat(accrued).isGreaterThan(0L);

        UUID accountId = UUID.randomUUID();
        // Preview mode succeeds
        var calc = interestService.accrue(accountId, "SAVINGS_PLUS", principal, start, end, false);
        assertThat(calc.accruedPesewas()).isEqualTo(accrued);
        assertThat(calc.cumulativePesewas()).isEqualTo(accrued);

        // Live mode without authorization fails per regulatory policy
        assertThatThrownBy(() -> interestService.accrue(accountId, "SAVINGS_PLUS", principal, start, end, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled for live use");
    }

    @Test
    @DisplayName("Algorithm 056: Float forecasting with seasonal variance and uncertainty reporting")
    void testFloatForecasting() {
        LocalDate today = LocalDate.now();
        List<FloatForecastingService.DailyFlow> history = List.of(
                new FloatForecastingService.DailyFlow(today.minusDays(7), 500_000L, 450_000L),
                new FloatForecastingService.DailyFlow(today.minusDays(6), 600_000L, 550_000L),
                new FloatForecastingService.DailyFlow(today.minusDays(5), 700_000L, 680_000L),
                new FloatForecastingService.DailyFlow(today.minusDays(4), 520_000L, 510_000L),
                new FloatForecastingService.DailyFlow(today.minusDays(3), 610_000L, 590_000L),
                new FloatForecastingService.DailyFlow(today.minusDays(2), 750_000L, 720_000L),
                new FloatForecastingService.DailyFlow(today.minusDays(1), 800_000L, 770_000L)
        );

        var forecast = floatForecastingService.generateForecast("MTN_MOMO", today.plusDays(1), history, 2_000_000L);
        assertThat(forecast.expectedInflowPesewas()).isGreaterThan(0L);
        assertThat(forecast.expectedOutflowPesewas()).isGreaterThan(0L);
        assertThat(forecast.confidenceUpperPesewas()).isGreaterThan(forecast.confidenceLowerPesewas());
        assertThat(forecast.hasDataLimitation()).isFalse();

        // Check limitation flag with short history
        var shortForecast = floatForecastingService.generateForecast("TELECEL_CASH", today.plusDays(1), history.subList(0, 3), 1_000_000L);
        assertThat(shortForecast.hasDataLimitation()).isTrue();
    }

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient db;

    @Test
    @DisplayName("Algorithm 057: Float rebalancing recommendations and execution")
    void testFloatRebalancing() {
        db.sql("DELETE FROM float_rebalance_recommendation").update();
        db.sql("DELETE FROM float_account").update();

        String surplusRail = "MTN_MOMO_SURPLUS";
        String deficitRail = "TELECEL_DEFICIT";

        floatRebalancingService.upsertFloatAccount(surplusRail, "MTN Float", 5_000_000L, 1_000_000L, 3_000_000L);
        floatRebalancingService.upsertFloatAccount(deficitRail, "Telecel Float", 200_000L, 1_000_000L, 3_000_000L);

        List<FloatRebalancingService.RebalanceRecommendation> recs = floatRebalancingService.evaluateRebalance();
        assertThat(recs).isNotEmpty();

        var match = recs.stream().filter(r -> r.sourceRail().equals(surplusRail) && r.targetRail().equals(deficitRail)).findFirst();
        assertThat(match).isPresent();
        assertThat(match.get().recommendedAmountPesewas()).isEqualTo(800_000L); // 1,000,000 - 200,000 needed

        UUID operatorId = UUID.randomUUID();
        floatRebalancingService.executeRebalance(match.get().id(), operatorId);

        // Subsequent execute throws
        assertThatThrownBy(() -> floatRebalancingService.executeRebalance(match.get().id(), operatorId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Algorithm 058: Merchant cash-flow forecasting with backtest MAPE")
    void testMerchantCashFlowForecasting() {
        UUID merchantId = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        List<MerchantCashFlowForecastService.DailyMerchantSales> history = List.of(
                new MerchantCashFlowForecastService.DailyMerchantSales(today.minusDays(5), 100_000L, 2_000L),
                new MerchantCashFlowForecastService.DailyMerchantSales(today.minusDays(4), 120_000L, 1_500L),
                new MerchantCashFlowForecastService.DailyMerchantSales(today.minusDays(3), 110_000L, 3_000L),
                new MerchantCashFlowForecastService.DailyMerchantSales(today.minusDays(2), 115_000L, 1_000L),
                new MerchantCashFlowForecastService.DailyMerchantSales(today.minusDays(1), 125_000L, 2_500L)
        );

        var projections = merchantForecastService.forecastMerchantCashFlow(merchantId, history, 1);
        assertThat(projections).hasSize(7);
        assertThat(projections.get(0).projectedGrossPesewas()).isGreaterThan(0L);
        assertThat(projections.get(0).projectedSettledPesewas()).isLessThan(projections.get(0).projectedGrossPesewas());
        assertThat(projections.get(0).backtestMape()).isNotNull();
    }

    @Test
    @DisplayName("Algorithm 065: Cash-flow underwriting")
    void testCashFlowUnderwriting() {
        UUID merchantId = UUID.randomUUID();
        // Under 60 days operating history
        var decisionShort = underwritingService.evaluateMerchant(merchantId, List.of(), 45);
        assertThat(decisionShort.decision()).isEqualTo("DECLINED");
        assertThat(decisionShort.reason()).contains("below mandatory threshold");

        // High refund rate
        List<CashFlowUnderwritingService.DailyTradingVolume> highRefund = List.of(
                new CashFlowUnderwritingService.DailyTradingVolume(100_000L, 15_000L), // 15% refund
                new CashFlowUnderwritingService.DailyTradingVolume(100_000L, 12_000L)
        );
        var decisionRefund = underwritingService.evaluateMerchant(merchantId, highRefund, 90);
        assertThat(decisionRefund.decision()).isEqualTo("DECLINED");
        assertThat(decisionRefund.reason()).contains("exceeds maximum risk tolerance");

        // Qualified merchant
        List<CashFlowUnderwritingService.DailyTradingVolume> qualified = List.of(
                new CashFlowUnderwritingService.DailyTradingVolume(200_000L, 2_000L),
                new CashFlowUnderwritingService.DailyTradingVolume(210_000L, 1_000L),
                new CashFlowUnderwritingService.DailyTradingVolume(195_000L, 3_000L)
        );
        var decisionQualified = underwritingService.evaluateMerchant(merchantId, qualified, 120);
        assertThat(decisionQualified.decision()).isEqualTo("APPROVED");
        assertThat(decisionQualified.maxCreditPesewas()).isGreaterThan(0L);
        assertThat(decisionQualified.recommendedFactorRate()).isGreaterThan(BigDecimal.valueOf(1.0));
    }

    @Test
    @DisplayName("Algorithm 066: Earned-wage access (EWA) eligibility")
    void testEwaEligibility() {
        UUID employeeId = UUID.randomUUID();
        UUID employerId = UUID.randomUUID();

        // Too few days worked (< 5)
        var res1 = ewaService.calculateEligibility(employeeId, employerId, 300_000L, 3, 30, 0L, 0.50);
        assertThat(res1.status()).isEqualTo("INELIGIBLE_MINIMUM_DAYS_NOT_MET");

        // Qualified: 15 days worked, 300,000 salary -> 150,000 accrued -> 75,000 max advance
        var res2 = ewaService.calculateEligibility(employeeId, employerId, 300_000L, 15, 30, 0L, 0.50);
        assertThat(res2.status()).isEqualTo("ELIGIBLE");
        assertThat(res2.accruedSalaryPesewas()).isEqualTo(150_000L);
        assertThat(res2.maxAdvanceLimitPesewas()).isEqualTo(75_000L);
        assertThat(res2.eligibleAmountPesewas()).isEqualTo(75_000L);

        // Deducts existing advances
        var res3 = ewaService.calculateEligibility(employeeId, employerId, 300_000L, 15, 30, 50_000L, 0.50);
        assertThat(res3.eligibleAmountPesewas()).isEqualTo(25_000L);
    }

    @Test
    @DisplayName("Algorithm 067: Overdraft scoring with live disablement")
    void testOverdraftScoring() {
        UUID customerId = UUID.randomUUID();
        // Live requested throws
        assertThatThrownBy(() -> overdraftService.evaluateOverdraft(customerId, 10, 50_000L, 90, 20_000L, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled for live use");

        // Preview scoring
        var score = overdraftService.evaluateOverdraft(customerId, 10, 50_000L, 90, 20_000L, false);
        assertThat(score.creditScore()).isGreaterThan(600);
        assertThat(score.status()).startsWith("QUALIFIED");
        assertThat(score.qualifiedLimitPesewas()).isGreaterThan(0L);
        assertThat(score.liveEnabled()).isFalse();
    }

    @Test
    @DisplayName("Algorithm 068: Merchant cash advance pricing and APR disclosure")
    void testMcaPricing() {
        UUID merchantId = UUID.randomUUID();
        var quote = mcaPricingService.createOffer(merchantId, 100_000L, MerchantCashAdvancePricingService.RiskTier.TIER_B, 10_000L);

        assertThat(quote.factorRate()).isEqualTo(BigDecimal.valueOf(1.14));
        assertThat(quote.fixedFeePesewas()).isEqualTo(14_000L);
        assertThat(quote.totalRepaymentPesewas()).isEqualTo(114_000L);
        assertThat(quote.aprEquivalentBps()).isGreaterThan(0);
        assertThat(quote.status()).isEqualTo("OFFERED");

        mcaPricingService.acceptOffer(quote.id());
    }

    @Test
    @DisplayName("Algorithm 069: Repayment scheduling and duplicate-safe sweeps")
    void testRepaymentSweeps() {
        UUID merchantId = UUID.randomUUID();
        var offer = mcaPricingService.createOffer(merchantId, 100_000L, MerchantCashAdvancePricingService.RiskTier.TIER_B, 10_000L);
        var plan = sweepsService.createPlan(offer.id(), merchantId, 100_000L, 2000); // 20% sweep

        UUID batch1 = UUID.randomUUID();
        // Sweep from 200,000 settlement -> 20% = 40,000
        var exec1 = sweepsService.executeSweep(plan.id(), batch1, 200_000L);
        assertThat(exec1.sweptAmountPesewas()).isEqualTo(40_000L);
        assertThat(exec1.remainingBalancePesewas()).isEqualTo(60_000L);

        // Duplicate sweep attempt on same batch fails unique constraint
        assertThatThrownBy(() -> sweepsService.executeSweep(plan.id(), batch1, 200_000L))
                .isInstanceOf(Exception.class);

        // Second batch sweeps remainder
        UUID batch2 = UUID.randomUUID();
        var exec2 = sweepsService.executeSweep(plan.id(), batch2, 400_000L);
        assertThat(exec2.sweptAmountPesewas()).isEqualTo(60_000L);
        assertThat(exec2.remainingBalancePesewas()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Algorithm 070: Collections prioritization and recovery queue")
    void testCollectionsPrioritization() {
        UUID debtor1 = UUID.randomUUID();
        UUID debtor2 = UUID.randomUUID();

        var case1 = collectionsService.triageDelinquency(debtor1, "MERCHANT", 500_000L, 10);
        assertThat(case1.tier()).isEqualTo(CollectionsPrioritizationService.AgingTier.TIER_1_EARLY_REMINDER);

        var case2 = collectionsService.triageDelinquency(debtor2, "CONSUMER", 500_000L, 45);
        assertThat(case2.tier()).isEqualTo(CollectionsPrioritizationService.AgingTier.TIER_3_SPECIALIZED_RECOVERY);

        // Case 1 should have higher priority score due to fresher delinquency age
        assertThat(case1.priorityScore()).isGreaterThan(case2.priorityScore());

        var queue = collectionsService.getPrioritizedQueue(10);
        assertThat(queue).isNotEmpty();
    }

    @Test
    @DisplayName("Algorithm 071: Calibrated default prediction model")
    void testDefaultPrediction() {
        UUID merchantId = UUID.randomUUID();
        // Low risk merchant: growing turnover, low volatility, 0 missed payments, low debt ratio
        var lowRisk = defaultPredictionService.predictDefault(merchantId, "MERCHANT",
                new DefaultPredictionService.CreditFeatures(1.30, 0.15, 0, 0.10));
        assertThat(lowRisk.probabilityOfDefault()).isLessThan(0.15);
        assertThat(lowRisk.riskBand()).isIn(DefaultPredictionService.RiskBand.LOW, DefaultPredictionService.RiskBand.MEDIUM);

        // High risk merchant: severe decline, high volatility, 3 missed payments, 80% DTI
        var highRisk = defaultPredictionService.predictDefault(merchantId, "MERCHANT",
                new DefaultPredictionService.CreditFeatures(0.60, 0.85, 3, 0.80));
        assertThat(highRisk.probabilityOfDefault()).isGreaterThan(lowRisk.probabilityOfDefault());
        assertThat(highRisk.riskBand()).isIn(DefaultPredictionService.RiskBand.HIGH, DefaultPredictionService.RiskBand.VERY_HIGH);
    }

    @Test
    @DisplayName("Algorithm 072: Dynamic credit limits adjustment and ceilings")
    void testDynamicCreditLimits() {
        UUID merchantId = UUID.randomUUID();
        long baseline = 100_000L;
        var profile = dynamicLimitService.initializeProfile(merchantId, "MERCHANT", baseline);
        assertThat(profile.currentLimitPesewas()).isEqualTo(baseline);

        // Sales growth expands limit
        var expanded = dynamicLimitService.adjustLimit(merchantId, "MERCHANT", 1.30, 100, false, 200_000L);
        assertThat(expanded.currentLimitPesewas()).isGreaterThan(baseline);

        // Delinquency curtails limit
        var curtailed = dynamicLimitService.adjustLimit(merchantId, "MERCHANT", 1.00, 100, true, 200_000L);
        assertThat(curtailed.currentLimitPesewas()).isLessThan(expanded.currentLimitPesewas());
    }
}
