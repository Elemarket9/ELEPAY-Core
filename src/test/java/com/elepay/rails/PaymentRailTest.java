package com.elepay.rails;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.reliability.BackoffAndJitterRetry;
import com.elepay.reliability.PartnerCircuitBreaker;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Algorithms 086 & 087 resilience wiring in PaymentRail.
 */
class PaymentRailTest {

    private BackoffAndJitterRetry retry;
    private BackoffAndJitterRetry.RetryConfig fastRetryConfig;

    @BeforeEach
    void setUp() {
        retry = new BackoffAndJitterRetry();
        // Fast configuration for unit tests
        fastRetryConfig = new BackoffAndJitterRetry.RetryConfig(
                3, 5, 20, BackoffAndJitterRetry.JitterStrategy.FULL_JITTER
        );
    }

    private static class MockPaymentRail implements PaymentRail {
        final AtomicInteger chargeCalls = new AtomicInteger(0);
        final AtomicInteger payoutCalls = new AtomicInteger(0);
        volatile boolean failWithNetworkError = false;
        volatile boolean returnUnavailable = false;

        @Override
        public String name() {
            return "mock-rail";
        }

        @Override
        public Result charge(Charge charge) {
            chargeCalls.incrementAndGet();
            if (failWithNetworkError) {
                throw new RuntimeException(new IOException("Connection reset by peer"));
            }
            if (returnUnavailable) {
                return Result.unavailable("Partner downstream gateway unavailable");
            }
            return new Result(Status.SUCCEEDED, "RAIL-TX-123", charge.amount(), charge.currency(), 50L, null, "Approved");
        }

        @Override
        public Result payout(Payout payout) {
            payoutCalls.incrementAndGet();
            if (failWithNetworkError) {
                throw new RuntimeException(new IOException("Socket timeout"));
            }
            return new Result(Status.SUCCEEDED, "RAIL-PO-456", payout.amount(), payout.currency(), 10L, null, "Disbursed");
        }

        @Override
        public Result refund(Refund refund) {
            return new Result(Status.SUCCEEDED, "RAIL-RF-789", refund.amount(), refund.currency(), 0L, null, "Refunded");
        }

        @Override
        public Result query(String direction, String reference, String railReference) {
            return new Result(Status.SUCCEEDED, railReference, 1000L, "GHS", 0L, null, "Success");
        }

        @Override
        public Callback verifyCallback(Map<String, String> headers, String body) {
            return null;
        }

        @Override
        public boolean callbacksAreAuthoritative() {
            return true;
        }
    }

    @Test
    @DisplayName("086 & 087: Resilient rail decorator succeeds on healthy partner and records success")
    void testResilientRailSuccess() {
        MockPaymentRail delegate = new MockPaymentRail();
        PartnerCircuitBreaker cb = new PartnerCircuitBreaker("mock-rail", 10, 50.0, 500, 2, null);
        PaymentRail resilientRail = PaymentRail.resilient(delegate, cb, retry, fastRetryConfig);

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "REF-1", 10_000, "GHS", "momo", "0240000000", "MTN", null, "Kwame", "Test", null
        );

        PaymentRail.Result res = resilientRail.charge(charge);

        assertThat(res.status()).isEqualTo(PaymentRail.Status.SUCCEEDED);
        assertThat(res.railReference()).isEqualTo("RAIL-TX-123");
        assertThat(delegate.chargeCalls.get()).isEqualTo(1);
        assertThat(cb.getState()).isEqualTo(PartnerCircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("087: Transient network transport errors trigger full jitter retries and recover")
    void testNetworkRetryWithJitter() {
        AtomicInteger attempts = new AtomicInteger(0);
        MockPaymentRail delegate = new MockPaymentRail() {
            @Override
            public Result charge(Charge charge) {
                int count = attempts.incrementAndGet();
                if (count < 3) {
                    throw new RuntimeException(new IOException("Transient network error attempt " + count));
                }
                return new Result(Status.SUCCEEDED, "RECOVERED-TX", charge.amount(), charge.currency(), 0L, null, "Recovered");
            }
        };

        PartnerCircuitBreaker cb = new PartnerCircuitBreaker("mock-rail", 10, 50.0, 500, 2, null);
        PaymentRail resilient = PaymentRail.resilient(delegate, cb, retry, fastRetryConfig);

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "REF-2", 5000, "GHS", "momo", "0240000000", "MTN", null, "Ama", "Test", null
        );

        PaymentRail.Result res = resilient.charge(charge);

        assertThat(res.status()).isEqualTo(PaymentRail.Status.SUCCEEDED);
        assertThat(res.railReference()).isEqualTo("RECOVERED-TX");
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(cb.getState()).isEqualTo(PartnerCircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("086: Sustained partner failures open the circuit and fast-fail subsequent requests")
    void testCircuitBreakerTripsAndFastFails() {
        MockPaymentRail delegate = new MockPaymentRail();
        delegate.returnUnavailable = true;

        // Sliding window 4, 50% failure threshold, 200ms wait
        PartnerCircuitBreaker cb = new PartnerCircuitBreaker("mock-rail", 4, 50.0, 200, 1, null);
        PaymentRail resilient = PaymentRail.resilient(delegate, cb, retry, fastRetryConfig);

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "REF-FAIL", 2000, "GHS", "momo", "0240000000", "MTN", null, "Kofi", "Test", null
        );

        // Fail 2 calls -> 2/2 = 100% >= 50% threshold with window size >= 2
        PaymentRail.Result r1 = resilient.charge(charge);
        PaymentRail.Result r2 = resilient.charge(charge);

        assertThat(r1.status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
        assertThat(r2.status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
        assertThat(cb.getState()).isEqualTo(PartnerCircuitBreaker.State.OPEN);

        int callsBefore = delegate.chargeCalls.get();

        // 3rd call must fast-fail without calling delegate
        PaymentRail.Result r3 = resilient.charge(charge);
        assertThat(r3.status()).isEqualTo(PaymentRail.Status.UNAVAILABLE);
        assertThat(r3.message()).contains("Circuit breaker OPEN");
        assertThat(delegate.chargeCalls.get()).isEqualTo(callsBefore);
    }

    @Test
    @DisplayName("086: Half-open probe executes after wait duration and recovers circuit on success")
    void testHalfOpenProbeAndRecovery() throws InterruptedException {
        MockPaymentRail delegate = new MockPaymentRail();
        delegate.returnUnavailable = true;

        long waitMs = 50;
        PartnerCircuitBreaker cb = new PartnerCircuitBreaker("mock-rail", 4, 50.0, waitMs, 1, null);
        PaymentRail resilient = PaymentRail.resilient(delegate, cb, retry, fastRetryConfig);

        PaymentRail.Charge charge = new PaymentRail.Charge(
                "REF-PROBE", 1000, "GHS", "momo", "0240000000", "MTN", null, "Yaw", "Test", null
        );

        // Trip the circuit
        resilient.charge(charge);
        resilient.charge(charge);
        assertThat(cb.getState()).isEqualTo(PartnerCircuitBreaker.State.OPEN);

        // Wait for waitDuration to elapse
        Thread.sleep(waitMs + 20);

        // State transitions to HALF_OPEN
        assertThat(cb.getState()).isEqualTo(PartnerCircuitBreaker.State.HALF_OPEN);

        // Partner recovers
        delegate.returnUnavailable = false;

        // Next call is the trial probe
        PaymentRail.Result probeResult = resilient.charge(charge);

        assertThat(probeResult.status()).isEqualTo(PaymentRail.Status.SUCCEEDED);
        assertThat(cb.getState()).isEqualTo(PartnerCircuitBreaker.State.CLOSED);
    }
}
