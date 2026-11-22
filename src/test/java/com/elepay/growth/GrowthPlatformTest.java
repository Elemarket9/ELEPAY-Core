package com.elepay.growth;

import com.elepay.PostgresTestBase;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

public class GrowthPlatformTest extends PostgresTestBase {

    @Autowired
    private SpendingCategorizationService spendingCategorizationService;

    @Autowired
    private SavingsGoalsService savingsGoalsService;

    @Autowired
    private ChurnPredictionService churnPredictionService;

    @Autowired
    private NotificationTimingService notificationTimingService;

    @Autowired
    private PersonalizedLimitService personalizedLimitService;

    @Test
    @DisplayName("Algorithm 096: Spending categorization with heuristics, MCC lookup, and user corrections")
    void testSpendingCategorization() {
        UUID customerId = UUID.randomUUID();
        UUID txn1 = UUID.randomUUID();
        UUID txn2 = UUID.randomUUID();

        // MCC lookup
        var cat1 = spendingCategorizationService.categorizeTransaction(txn1, customerId, "Payment", "5411");
        assertThat(cat1.category()).isEqualTo(SpendingCategorizationService.Category.GROCERIES);
        assertThat(cat1.source()).isEqualTo("MCC_LOOKUP");

        // Heuristic lookup
        var cat2 = spendingCategorizationService.categorizeTransaction(txn2, customerId, "Bolt Ride in Accra", null);
        assertThat(cat2.category()).isEqualTo(SpendingCategorizationService.Category.TRANSPORT);

        // User correction
        spendingCategorizationService.correctCategory(txn2, SpendingCategorizationService.Category.UTILITIES, "BusinessTravelExpense");
        var updated = spendingCategorizationService.getCategory(txn2);
        assertThat(updated).isPresent();
        assertThat(updated.get().category()).isEqualTo(SpendingCategorizationService.Category.UTILITIES);
        assertThat(updated.get().correctedByUser()).isTrue();
    }

    @Test
    @DisplayName("Algorithm 097: Voluntary savings round-ups and insufficient funds protection")
    void testSavingsGoalsAndRoundUps() {
        UUID customerId = UUID.randomUUID();
        var goal = savingsGoalsService.createGoal(customerId, "Emergency Fund", 500_000L, 100, LocalDate.now().plusMonths(6));

        // 13.40 GHS (1340 pesewas) with 100 unit -> rounds up 60 pesewas
        assertThat(savingsGoalsService.calculateRoundUp(1340L, 100)).isEqualTo(60L);
        // Exact multiple -> rounds up 0 pesewas
        assertThat(savingsGoalsService.calculateRoundUp(1500L, 100)).isEqualTo(0L);

        UUID txnId1 = UUID.randomUUID();
        // Customer has 5,000 pesewas -> sweep 60 pesewas succeeds
        var res1 = savingsGoalsService.sweepTransaction(customerId, txnId1, 1340L, 5000L);
        assertThat(res1.status()).isEqualTo("COMPLETED");
        assertThat(res1.roundUpAmountPesewas()).isEqualTo(60L);
        assertThat(res1.newSavingsBalancePesewas()).isEqualTo(60L);

        UUID txnId2 = UUID.randomUUID();
        // Customer has only 10 pesewas -> insufficient funds safeguard skips sweep (never overdrafts)
        var res2 = savingsGoalsService.sweepTransaction(customerId, txnId2, 1340L, 10L);
        assertThat(res2.status()).isEqualTo("SKIPPED_INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("Algorithm 098: Customer churn dormancy survival analysis")
    void testChurnPrediction() {
        UUID customerId = UUID.randomUUID();

        // Highly active customer (last txn 1 day ago, 5 txns/week)
        var active = churnPredictionService.evaluateChurnRisk(customerId, 1, 5.0);
        assertThat(active.riskTier()).isEqualTo(ChurnPredictionService.ChurnRiskTier.ACTIVE);
        assertThat(active.dormancyProbability()).isLessThan(0.15);

        // Dormant customer (last txn 45 days ago, 0.5 txns/week)
        var dormant = churnPredictionService.evaluateChurnRisk(customerId, 45, 0.5);
        assertThat(dormant.riskTier()).isIn(ChurnPredictionService.ChurnRiskTier.DORMANT, ChurnPredictionService.ChurnRiskTier.CHURNED);
        assertThat(dormant.dormancyProbability()).isGreaterThan(0.50);
    }

    @Test
    @DisplayName("Algorithm 099: Notification timing and quiet-hours delivery scheduling")
    void testNotificationTiming() {
        UUID customerId = UUID.randomUUID();

        // Test quiet hours boundary (23:00 is quiet hours)
        LocalTime nightTime = LocalTime.of(23, 0);
        assertThat(notificationTimingService.isQuietHours(nightTime)).isTrue();

        LocalTime dayTime = LocalTime.of(14, 0);
        assertThat(notificationTimingService.isQuietHours(dayTime)).isFalse();

        OffsetDateTime nightUtc = OffsetDateTime.of(2026, 11, 22, 23, 0, 0, 0, ZoneOffset.UTC);

        // Urgent notification sent immediately even during quiet hours
        var urgent = notificationTimingService.enqueueNotification(customerId, "Security Alert", "New login from Accra",
                NotificationTimingService.Urgency.URGENT, nightUtc);
        assertThat(urgent.sentImmediately()).isTrue();
        assertThat(urgent.status()).isEqualTo("SENT");

        // Marketing notification queued until morning
        var marketing = notificationTimingService.enqueueNotification(customerId, "Weekend Special", "Earn 2% cashback",
                NotificationTimingService.Urgency.MARKETING, nightUtc);
        assertThat(marketing.sentImmediately()).isFalse();
        assertThat(marketing.status()).isEqualTo("QUEUED");
        assertThat(marketing.scheduledDeliveryTime().getHour()).isEqualTo(8);
    }

    @Test
    @DisplayName("Algorithm 100: Personalized limits with policy ceilings and risk adjustments")
    void testPersonalizedLimits() {
        UUID customerId = UUID.randomUUID();

        // Tier 1 customer with disputes -> curtailed to 50%
        var curtailed = personalizedLimitService.computePersonalizedLimit(customerId, 1, 40, 30, 2);
        assertThat(curtailed.tierMultiplier()).isEqualTo(0.50);
        assertThat(curtailed.maxDailyPesewas()).isEqualTo(15_000L); // 30,000 * 0.50

        // Tier 2 loyal customer (clean 100 days, low risk score 10) -> elevated by 40%
        var elevated = personalizedLimitService.computePersonalizedLimit(customerId, 2, 10, 100, 0);
        assertThat(elevated.tierMultiplier()).isEqualTo(1.40);
        assertThat(elevated.maxDailyPesewas()).isEqualTo(280_000L); // 200,000 * 1.40
        assertThat(elevated.maxDailyPesewas()).isLessThanOrEqualTo(elevated.policyCeilingDailyPesewas());

        // Policy ceiling enforcement: limit never exceeds statutory ceiling (500,000 for Tier 2)
        assertThat(elevated.maxDailyPesewas()).isLessThanOrEqualTo(500_000L);
    }
}
