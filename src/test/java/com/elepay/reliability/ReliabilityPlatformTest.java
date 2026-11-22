package com.elepay.reliability;

import com.elepay.PostgresTestBase;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ReliabilityPlatformTest extends PostgresTestBase {

    @Autowired
    private PartnerCircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private BackoffAndJitterRetry backoffRetry;

    @Autowired
    private LoadShedder loadShedder;

    @Autowired
    private SloBurnRateAlertService sloBurnRateAlertService;

    @Autowired
    private SuccessRateAnomalyDetector anomalyDetector;

    @Autowired
    private ScalingSignalService scalingSignalService;

    @Autowired
    private FaultInjectionHarness faultHarness;

    @Autowired
    private DatabaseFailoverRecoveryService failoverService;

    @Test
    @DisplayName("Algorithm 086: Partner circuit breaker state transitions (CLOSED -> OPEN -> HALF_OPEN -> CLOSED)")
    void testPartnerCircuitBreaker() throws Exception {
        String partner = "MTN_MOMO_TEST_" + UUID.randomUUID();
        // Window 4, 50% threshold, 200ms wait, 2 probes
        PartnerCircuitBreaker cb = circuitBreakerRegistry.getOrCreate(partner, 4, 50.0, 200L, 2);

        assertThat(cb.getState()).isEqualTo(PartnerCircuitBreaker.State.CLOSED);
        cb.acquirePermission();

        // 2 failures out of 3 = 66% failure rate -> trips to OPEN
        cb.recordSuccess();
        cb.recordFailure();
        cb.recordFailure();

        assertThat(cb.getState()).isEqualTo(PartnerCircuitBreaker.State.OPEN);

        // While OPEN, acquirePermission throws CircuitBreakerOpenException
        assertThatThrownBy(cb::acquirePermission)
                .isInstanceOf(PartnerCircuitBreaker.CircuitBreakerOpenException.class);

        // Wait for wait duration to elapse -> HALF_OPEN
        Thread.sleep(250L);
        assertThat(cb.getState()).isEqualTo(PartnerCircuitBreaker.State.HALF_OPEN);

        // Probing in HALF_OPEN: 2 successes recover circuit back to CLOSED
        cb.recordSuccess();
        cb.recordSuccess();
        assertThat(cb.getState()).isEqualTo(PartnerCircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Algorithm 087: Exponential backoff with Full Jitter and retry budgets")
    void testBackoffAndJitter() throws Exception {
        var config = new BackoffAndJitterRetry.RetryConfig(4, 50, 500, BackoffAndJitterRetry.JitterStrategy.FULL_JITTER);

        // Test jitter calculation bounds
        for (int i = 0; i < 5; i++) {
            long delay = backoffRetry.calculateDelay(i, config, 50);
            assertThat(delay).isBetween(0L, 500L);
        }

        // Test successful retry execution after 2 transient failures
        AtomicInteger attempts = new AtomicInteger(0);
        String result = backoffRetry.executeWithRetry(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("Transient rail timeout");
            }
            return "SUCCESS";
        }, config, ex -> ex.getMessage().contains("timeout"));

        assertThat(result).isEqualTo("SUCCESS");
        assertThat(attempts.get()).isEqualTo(3);

        // Non-retryable error fails immediately without retries
        AtomicInteger nonRetryAttempts = new AtomicInteger(0);
        assertThatThrownBy(() -> backoffRetry.executeWithRetry(() -> {
            nonRetryAttempts.incrementAndGet();
            throw new IllegalArgumentException("Invalid PAN format");
        }, config, ex -> ex instanceof IllegalStateException))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(nonRetryAttempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Algorithm 089: Load shedding priority tiers protect financial operations")
    void testLoadShedding() {
        loadShedder.setInFlightForTesting(70); // 70% load

        // Critical operations (financial postings) are never shed
        var criticalDecision = loadShedder.evaluateAdmission(LoadShedder.PriorityTier.CRITICAL);
        assertThat(criticalDecision.allowed()).isTrue();

        // High priority admitted at 70% (threshold 90%)
        var highDecision = loadShedder.evaluateAdmission(LoadShedder.PriorityTier.HIGH);
        assertThat(highDecision.allowed()).isTrue();

        // Low priority shed at 70% (threshold 60%)
        var lowDecision = loadShedder.evaluateAdmission(LoadShedder.PriorityTier.LOW);
        assertThat(lowDecision.allowed()).isFalse();
        assertThat(lowDecision.reason()).contains("exceeds threshold");

        loadShedder.setInFlightForTesting(0);
    }

    @Test
    @DisplayName("Algorithm 090: SLO multi-window burn rate alerts")
    void testSloBurnRateAlerts() {
        // Target availability 99.95% -> error budget = 0.0005
        // 1h window with 1% errors -> burn rate = 0.01 / 0.0005 = 20x (exceeds 14.4x)
        var shortWindow = new SloBurnRateAlertService.WindowMetrics(1000, 15, 0.083);
        var longWindow = new SloBurnRateAlertService.WindowMetrics(10000, 150, 1.0);

        List<SloBurnRateAlertService.BurnAlert> alerts = sloBurnRateAlertService.evaluateBurnRate(
                "checkout-service", "checkout_availability_9995", 0.9995, shortWindow, longWindow);

        assertThat(alerts).isNotEmpty();
        assertThat(alerts.get(0).severity()).isEqualTo("PAGE_CRITICAL");
        assertThat(alerts.get(0).burnRate()).isGreaterThanOrEqualTo(14.4);
    }

    @Test
    @DisplayName("Algorithm 091: Statistical success-rate anomaly detection")
    void testSuccessRateAnomalyDetection() {
        // Baseline conversion is 95%
        // Severe drop to 60% with n=100 -> massive negative Z score
        var anomaly = anomalyDetector.evaluateSuccessRate("GHIPSS_INSTANT_PAY", 95.0, 60, 100);
        assertThat(anomaly.isAnomaly()).isTrue();
        assertThat(anomaly.zScore()).isLessThan(-2.58);
        assertThat(anomaly.reason()).contains("Significant conversion drop");

        // Normal conversion 94% -> no anomaly
        var normal = anomalyDetector.evaluateSuccessRate("GHIPSS_INSTANT_PAY", 95.0, 94, 100);
        assertThat(normal.isAnomaly()).isFalse();

        // Small sample size is gracefully handled
        var small = anomalyDetector.evaluateSuccessRate("GHIPSS_INSTANT_PAY", 95.0, 5, 10);
        assertThat(small.isAnomaly()).isFalse();
        assertThat(small.reason()).contains("INSUFFICIENT_SAMPLE_SIZE");
    }

    @Test
    @DisplayName("Algorithm 092: Capacity signals and scaling indicators")
    void testScalingSignals() {
        // High connection pool and outbox lag triggers scale up
        var scaleUp = scalingSignalService.evaluateScaling("payment-engine", 88.0, 65, 8000L, 1200L);
        assertThat(scaleUp.recommendation()).isEqualTo(ScalingSignalService.ScaleAction.SCALE_UP);

        // Low metrics trigger scale down
        var scaleDown = scalingSignalService.evaluateScaling("payment-engine", 10.0, 1, 50L, 40L);
        assertThat(scaleDown.recommendation()).isEqualTo(ScalingSignalService.ScaleAction.SCALE_DOWN);

        // Moderate metrics keep stable
        var stable = scalingSignalService.evaluateScaling("payment-engine", 50.0, 20, 500L, 300L);
        assertThat(stable.recommendation()).isEqualTo(ScalingSignalService.ScaleAction.STABLE);
    }

    @Test
    @DisplayName("Algorithm 093: Fault injection harness for network and latency chaos")
    void testFaultInjection() {
        String rail = "SIM_RAIL_" + UUID.randomUUID().toString().substring(0, 8);

        // Inject 100% timeout error
        faultHarness.registerExperiment("TimeoutDrill", rail, FaultInjectionHarness.FailureMode.TIMEOUT, 0, 100.0);

        assertThatThrownBy(() -> faultHarness.maybeInjectFault(rail))
                .isInstanceOf(FaultInjectionHarness.InjectedFaultException.class)
                .hasMessageContaining("Injected timeout failure");

        // Stop experiment removes fault
        faultHarness.stopExperiment(rail);
        faultHarness.maybeInjectFault(rail); // Does not throw
    }

    @Test
    @DisplayName("Algorithm 094: Database failover recovery exception classification and ping")
    void testDatabaseFailoverRecovery() throws Exception {
        // Ping returns true for healthy database
        assertThat(failoverService.pingDatabase()).isTrue();

        // Exception classification: 08006 (connection failure) is recognized as failover
        SQLException failoverEx = new SQLException("Connection terminated unexpectedly", "08006");
        assertThat(failoverService.isFailoverException(failoverEx)).isTrue();

        // Non-failover syntax error (42601) is not failover
        SQLException syntaxEx = new SQLException("Syntax error", "42601");
        assertThat(failoverService.isFailoverException(syntaxEx)).isFalse();

        // Successful execution with failover retry
        AtomicInteger attempts = new AtomicInteger(0);
        String result = failoverService.executeWithFailoverRetry(() -> {
            if (attempts.incrementAndGet() < 2) {
                throw new SQLException("Connection lost during failover", "08006");
            }
            return "RECOVERED";
        }, 3, 20L);

        assertThat(result).isEqualTo("RECOVERED");
        assertThat(attempts.get()).isEqualTo(2);
    }
}
