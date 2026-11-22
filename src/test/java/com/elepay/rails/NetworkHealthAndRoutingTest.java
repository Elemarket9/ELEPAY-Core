package com.elepay.rails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.elepay.common.OpsQueue;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@DisplayName("Algorithms 014, 015, 016: Smart Routing, Safe Failover & Network Health")
class NetworkHealthAndRoutingTest {

    private NetworkHealthDetector healthDetector;
    private SmartPaymentRouter router;
    private SafeFailoverRouter failoverRouter;
    private OpsQueue ops;
    private RailRegistry railRegistry;

    @BeforeEach
    void setUp() {
        JdbcClient db = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        ops = mock(OpsQueue.class);
        railRegistry = mock(RailRegistry.class);
        when(railRegistry.names()).thenReturn(Set.of("mtn", "telecel", "airteltigo", "ghipss", "card", "sandbox"));

        healthDetector = new NetworkHealthDetector(db);
        router = new SmartPaymentRouter(db, healthDetector, railRegistry);
        failoverRouter = new SafeFailoverRouter(db, healthDetector, ops);
    }

    @Test
    @DisplayName("Algorithm 016: Network health tracks success, failures, and latency")
    void tracksHealthMetrics() {
        healthDetector.record("mtn", true, false, 150);
        healthDetector.record("mtn", true, false, 200);
        healthDetector.record("mtn", false, false, 350);

        var health = healthDetector.getHealth("mtn");
        assertThat(health.totalRequests()).isEqualTo(3);
        assertThat(health.successfulRequests()).isEqualTo(2);
        assertThat(health.failedRequests()).isEqualTo(1);
        assertThat(health.circuitState()).isEqualTo(NetworkHealthDetector.CircuitState.CLOSED);
        assertThat(health.averageLatencyMs()).isGreaterThan(100);
    }

    @Test
    @DisplayName("Algorithm 016: Circuit trips to OPEN when error rate exceeds threshold")
    void circuitTripsToOpenOnHighErrors() {
        // Record 5 failures out of 5 (100% error rate >= 40% threshold with >= 5 samples)
        for (int i = 0; i < 5; i++) {
            healthDetector.record("telecel", false, false, 100);
        }

        var health = healthDetector.getHealth("telecel");
        assertThat(health.circuitState()).isEqualTo(NetworkHealthDetector.CircuitState.OPEN);
        assertThat(healthDetector.isRailAvailable("telecel")).isFalse();
    }

    @Test
    @DisplayName("Algorithm 016: Circuit trips to OPEN on 3 consecutive timeouts")
    void circuitTripsOnConsecutiveTimeouts() {
        healthDetector.record("ghipss", false, true, 5000);
        healthDetector.record("ghipss", false, true, 5000);
        assertThat(healthDetector.getHealth("ghipss").circuitState()).isEqualTo(NetworkHealthDetector.CircuitState.CLOSED);

        healthDetector.record("ghipss", false, true, 5000);
        // 3rd consecutive timeout trips circuit
        assertThat(healthDetector.getHealth("ghipss").circuitState()).isEqualTo(NetworkHealthDetector.CircuitState.OPEN);
        assertThat(healthDetector.isRailAvailable("ghipss")).isFalse();
    }

    @Test
    @DisplayName("Algorithm 014: Smart router selects healthy primary route")
    void selectsHealthyRoute() {
        var route = router.selectRoute("momo", "mtn", 10000L, "GHS");
        assertThat(route.rail()).isEqualTo("mtn");
        assertThat(route.channel()).isEqualTo("momo");
        assertThat(route.network()).isEqualTo("mtn");
        assertThat(route.healthState()).isEqualTo(NetworkHealthDetector.CircuitState.CLOSED);
    }

    @Test
    @DisplayName("Algorithm 015: Safe failover is ALLOWED when primary circuit is OPEN pre-submission")
    void safeFailoverAllowedWhenCircuitOpen() {
        healthDetector.tripCircuit("mtn");

        var decision = failoverRouter.evaluateFailover(
                "EPC123456", "mtn", "sandbox", PaymentRail.Status.NOT_FOUND, false
        );

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.targetRail()).isEqualTo("sandbox");
    }

    @Test
    @DisplayName("Algorithm 015: Safe failover is ALLOWED when partner confirms NOT_FOUND (no money moved)")
    void safeFailoverAllowedWhenNotFound() {
        var decision = failoverRouter.evaluateFailover(
                "EPC123456", "mtn", "sandbox", PaymentRail.Status.NOT_FOUND, false
        );

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.targetRail()).isEqualTo("sandbox");
        assertThat(decision.reason()).isEqualTo("partner_confirmed_not_found");
    }

    @Test
    @DisplayName("Algorithm 015: Safe failover is STRICTLY FORBIDDEN when primary outcome is UNKNOWN")
    void failoverStrictlyBlockedOnUnknown() {
        assertThatThrownBy(() -> failoverRouter.evaluateFailover(
                "EPC123456", "mtn", "sandbox", PaymentRail.Status.UNKNOWN, true
        )).isInstanceOf(SafeFailoverRouter.UnsafeFailoverException.class)
          .hasMessageContaining("Unsafe failover blocked");

        verify(ops).raise(eq("unsafe_failover_blocked"), eq("high"), anyString(), anyMap());
    }

    @Test
    @DisplayName("Algorithm 015: Safe failover is STRICTLY FORBIDDEN when primary outcome is PENDING")
    void failoverStrictlyBlockedOnPending() {
        assertThatThrownBy(() -> failoverRouter.evaluateFailover(
                "EPC123456", "mtn", "sandbox", PaymentRail.Status.PENDING, true
        )).isInstanceOf(SafeFailoverRouter.UnsafeFailoverException.class)
          .hasMessageContaining("Unsafe failover blocked");
    }

    @Test
    @DisplayName("Algorithm 015: Safe failover is STRICTLY FORBIDDEN when primary SUCCEEDED")
    void failoverStrictlyBlockedOnSucceeded() {
        assertThatThrownBy(() -> failoverRouter.evaluateFailover(
                "EPC123456", "mtn", "sandbox", PaymentRail.Status.SUCCEEDED, true
        )).isInstanceOf(SafeFailoverRouter.UnsafeFailoverException.class)
          .hasMessageContaining("succeeded");
    }
}
