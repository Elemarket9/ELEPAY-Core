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

    @Autowired
    private com.elepay.ledger.Ledger ledger;

    @Autowired
    private com.elepay.compliance.Reporting reporting;

    @Autowired
    private com.elepay.compliance.Retention retention;

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
    @DisplayName("Algorithm 097: Voluntary savings round-ups, atomic double-entry ledger posting and insufficient funds protection")
    void testSavingsGoalsAndRoundUps() {
        UUID customerId = UUID.randomUUID();
        var goal = savingsGoalsService.createGoal(customerId, "Emergency Fund", 500_000L, 100, LocalDate.now().plusMonths(6));

        // 13.40 GHS (1340 pesewas) with 100 unit -> rounds up 60 pesewas
        assertThat(savingsGoalsService.calculateRoundUp(1340L, 100)).isEqualTo(60L);
        // Exact multiple -> rounds up 0 pesewas
        assertThat(savingsGoalsService.calculateRoundUp(1500L, 100)).isEqualTo(0L);

        // Pre-fund customer available wallet in ledger with 5,000 pesewas
        ledger.open("customer", customerId, "available", com.elepay.ledger.Ledger.GHS);
        ledger.open("customer", customerId, "reserved", com.elepay.ledger.Ledger.GHS);
        ledger.post(com.elepay.ledger.Ledger.Posting.of("test-setup", "fund-" + customerId, "topup", java.util.List.of(
                com.elepay.ledger.Ledger.Leg.debit(com.elepay.ledger.Ledger.clearing("sandbox"), 5000L),
                com.elepay.ledger.Ledger.Leg.credit(com.elepay.wallet.CustomerDirectory.available(customerId), 5000L)
        )));

        UUID txnId1 = UUID.randomUUID();
        // Customer has 5,000 pesewas -> sweep 60 pesewas succeeds with atomic ledger post
        var res1 = savingsGoalsService.sweepTransaction(customerId, txnId1, 1340L, 5000L);
        assertThat(res1.status()).isEqualTo("COMPLETED");
        assertThat(res1.roundUpAmountPesewas()).isEqualTo(60L);
        assertThat(res1.newSavingsBalancePesewas()).isEqualTo(60L);

        // Verify ledger balances: 60 pesewas debited from available and credited to savings:goal
        assertThat(ledger.balance(com.elepay.wallet.CustomerDirectory.available(customerId))).isEqualTo(5000L - 60L);
        assertThat(ledger.balance(com.elepay.wallet.CustomerDirectory.reserved(customerId))).isEqualTo(60L);

        UUID txnId2 = UUID.randomUUID();
        // Customer has only 10 pesewas -> insufficient funds safeguard skips sweep (never overdrafts)
        var res2 = savingsGoalsService.sweepTransaction(customerId, txnId2, 1340L, 10L);
        assertThat(res2.status()).isEqualTo("SKIPPED_INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("Algorithm 098: Customer churn dormancy survival analysis and negative time delta guard")
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

        // Negative time delta guard: clock skew or negative input does NOT produce NaN
        var negativeDays = churnPredictionService.evaluateChurnRisk(customerId, -10, 4.0);
        assertThat(Double.isNaN(negativeDays.dormancyProbability())).isFalse();
        assertThat(Double.isNaN(negativeDays.hazardRate())).isFalse();
        assertThat(negativeDays.riskTier()).isEqualTo(ChurnPredictionService.ChurnRiskTier.ACTIVE);
    }

    @Test
    @DisplayName("Algorithm 099: Notification timing, quiet-hours boundaries, and scheduled dispatch")
    void testNotificationTiming() {
        UUID customerId = UUID.randomUUID();

        // Test quiet hours boundary transitions (21:00 and 07:00 are quiet hours)
        assertThat(notificationTimingService.isQuietHours(LocalTime.of(21, 0))).isTrue();
        assertThat(notificationTimingService.isQuietHours(LocalTime.of(23, 0))).isTrue();
        assertThat(notificationTimingService.isQuietHours(LocalTime.of(7, 0))).isTrue();
        assertThat(notificationTimingService.isQuietHours(LocalTime.of(7, 1))).isFalse();
        assertThat(notificationTimingService.isQuietHours(LocalTime.of(14, 0))).isFalse();
        assertThat(notificationTimingService.isQuietHours(LocalTime.of(20, 59))).isFalse();

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

        // Test scheduled poller and dispatcher: dispatches queued notifications due as of morning
        OffsetDateTime morningUtc = nightUtc.plusDays(1).withHour(8).withMinute(5);
        int dispatched = notificationTimingService.dispatchDueNotifications(morningUtc);
        assertThat(dispatched).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Algorithm 100: Personalized limits with checked basis-point arithmetic and policy ceilings")
    void testPersonalizedLimits() {
        UUID customerId = UUID.randomUUID();

        // Tier 1 customer with disputes -> curtailed to 50% (5,000 bps)
        var curtailed = personalizedLimitService.computePersonalizedLimit(customerId, 1, 40, 30, 2);
        assertThat(curtailed.multiplierBps()).isEqualTo(5_000);
        assertThat(curtailed.tierMultiplier()).isEqualTo(0.50);
        assertThat(curtailed.maxDailyPesewas()).isEqualTo(15_000L); // 30,000 * 5000 / 10000

        // Tier 2 loyal customer (clean 100 days, low risk score 10) -> elevated by 40% (14,000 bps)
        var elevated = personalizedLimitService.computePersonalizedLimit(customerId, 2, 10, 100, 0);
        assertThat(elevated.multiplierBps()).isEqualTo(14_000);
        assertThat(elevated.tierMultiplier()).isEqualTo(1.40);
        assertThat(elevated.maxDailyPesewas()).isEqualTo(280_000L); // 200,000 * 14000 / 10000
        assertThat(elevated.maxDailyPesewas()).isLessThanOrEqualTo(elevated.policyCeilingDailyPesewas());

        // Policy ceiling enforcement: limit never exceeds statutory ceiling (500,000 for Tier 2)
        assertThat(elevated.maxDailyPesewas()).isLessThanOrEqualTo(500_000L);
    }

    @Test
    @DisplayName("Algorithm 074: USSD PIN sliding-window validator rejecting sequential and repeated digits")
    void testPinValidationAlgorithm074() {
        // Sequential ascending digits (window size 4)
        assertThat(com.elepay.wallet.WalletChannel.isWeakPin("1234")).isTrue();
        assertThat(com.elepay.wallet.WalletChannel.isWeakPin("2345")).isTrue();
        assertThat(com.elepay.wallet.WalletChannel.isWeakPin("0123")).isTrue();
        assertThat(com.elepay.wallet.WalletChannel.isWeakPin("91234")).isTrue();

        // Sequential descending digits
        assertThat(com.elepay.wallet.WalletChannel.isWeakPin("4321")).isTrue();
        assertThat(com.elepay.wallet.WalletChannel.isWeakPin("6543")).isTrue();
        assertThat(com.elepay.wallet.WalletChannel.isWeakPin("9876")).isTrue();

        // Repeated digits
        assertThat(com.elepay.wallet.WalletChannel.isWeakPin("1111")).isTrue();
        assertThat(com.elepay.wallet.WalletChannel.isWeakPin("9999")).isTrue();
        assertThat(com.elepay.wallet.WalletChannel.isWeakPin("000000")).isTrue();

        // Strong non-sequential PINs
        assertThat(com.elepay.wallet.WalletChannel.isWeakPin("1948")).isFalse();
        assertThat(com.elepay.wallet.WalletChannel.isWeakPin("83726")).isFalse();
        assertThat(com.elepay.wallet.WalletChannel.isWeakPin("2951")).isFalse();
    }

    @Test
    @DisplayName("Algorithm 047 & 048: Regulatory reporting JSON escaping and actual retention redaction")
    void testReportingAndRetentionAlgorithms047And048() {
        UUID preparer = UUID.randomUUID();
        UUID complianceOfficer = UUID.randomUUID();

        // 047: Proper escaping of special characters in customer names and counterparties
        var ctrEntry = new com.elepay.compliance.Reporting.CtrEntry(
                UUID.randomUUID(), UUID.randomUUID(), "Osei \"Kofi\" Tutu", 500_000L, "MTN\\MoMo", java.time.Instant.now()
        );
        var report = reporting.generateFicCtrReport("2026-11", java.util.List.of(ctrEntry), preparer);
        assertThat(report.contentPayload()).contains("Osei \\\"Kofi\\\" Tutu");
        assertThat(report.contentPayload()).contains("MTN\\\\MoMo");

        // Dual-control maker-checker
        var approved = reporting.approveReport(report.id(), complianceOfficer);
        assertThat(approved.status()).isEqualTo(com.elepay.compliance.Reporting.ReportStatus.APPROVED_FOR_FILING);

        // 048: Retention and legal holds
        UUID customer = UUID.randomUUID();
        var hold = retention.applyLegalHold(customer, "HOLD-001", "Subpoena pending", complianceOfficer);
        assertThat(retention.hasActiveLegalHold(customer)).isTrue();
        retention.releaseLegalHold(hold.holdId());
        assertThat(retention.hasActiveLegalHold(customer)).isFalse();

        // Actual redaction after statutory expiry (60 months)
        java.time.Instant closedYearsAgo = java.time.Instant.now().minus(java.time.Duration.ofDays(2500));
        var redaction = retention.redactCustomerPii(customer, closedYearsAgo, complianceOfficer);
        assertThat(redaction.successful()).isTrue();
        assertThat(redaction.redactedFields()).contains("id_number", "full_name");
    }
}
