package com.elepay.reliability;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Algorithm 086: Partner circuit breakers.
 * Resilience4j-style circuit state transitions (CLOSED -> OPEN -> HALF_OPEN -> CLOSED).
 * Tracks failure rates, enforces wait duration, allows trial probes, and prevents cascading failures to external partners.
 */
public class PartnerCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public static class CircuitBreakerOpenException extends RuntimeException {
        private final String partnerId;
        private final long retryAfterMs;

        public CircuitBreakerOpenException(String partnerId, long retryAfterMs) {
            super("Circuit breaker is OPEN for partner: " + partnerId + "; retry after " + retryAfterMs + "ms");
            this.partnerId = partnerId;
            this.retryAfterMs = retryAfterMs;
        }

        public String getPartnerId() { return partnerId; }
        public long getRetryAfterMs() { return retryAfterMs; }
    }

    private final String partnerId;
    private final int slidingWindowSize;
    private final double failureRateThresholdPct;
    private final long waitDurationInOpenStateMs;
    private final int permittedHalfOpenProbes;
    private final JdbcClient db;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final ConcurrentLinkedDeque<Boolean> outcomeWindow = new ConcurrentLinkedDeque<>();
    private final AtomicInteger halfOpenProbeCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0);
    private volatile long lastStateTransitionTime = System.currentTimeMillis();

    public PartnerCircuitBreaker(String partnerId, int slidingWindowSize, double failureRateThresholdPct,
                                 long waitDurationInOpenStateMs, int permittedHalfOpenProbes, JdbcClient db) {
        this.partnerId = partnerId;
        this.slidingWindowSize = slidingWindowSize;
        this.failureRateThresholdPct = failureRateThresholdPct;
        this.waitDurationInOpenStateMs = waitDurationInOpenStateMs;
        this.permittedHalfOpenProbes = permittedHalfOpenProbes;
        this.db = db;
    }

    public State getState() {
        State current = state.get();
        if (current == State.OPEN) {
            long elapsed = System.currentTimeMillis() - lastStateTransitionTime;
            if (elapsed >= waitDurationInOpenStateMs) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    transition(State.OPEN, State.HALF_OPEN, "Wait duration elapsed; entering half-open for probing");
                    halfOpenProbeCount.set(0);
                    halfOpenSuccessCount.set(0);
                    return State.HALF_OPEN;
                }
            }
        }
        return state.get();
    }

    public void recordSuccess() {
        State current = getState();
        if (current == State.HALF_OPEN) {
            int probes = halfOpenProbeCount.incrementAndGet();
            int successes = halfOpenSuccessCount.incrementAndGet();
            if (successes >= permittedHalfOpenProbes) {
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    transition(State.HALF_OPEN, State.CLOSED, "Probes succeeded (" + successes + "/" + permittedHalfOpenProbes + "); circuit recovered");
                    outcomeWindow.clear();
                }
            }
        } else if (current == State.CLOSED) {
            outcomeWindow.addLast(true);
            while (outcomeWindow.size() > slidingWindowSize) {
                outcomeWindow.pollFirst();
            }
        }
    }

    public void recordFailure() {
        State current = getState();
        if (current == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                transition(State.HALF_OPEN, State.OPEN, "Half-open probe failed; tripping back to open");
            }
        } else if (current == State.CLOSED) {
            outcomeWindow.addLast(false);
            while (outcomeWindow.size() > slidingWindowSize) {
                outcomeWindow.pollFirst();
            }

            if (outcomeWindow.size() >= (slidingWindowSize / 2)) {
                long failures = outcomeWindow.stream().filter(s -> !s).count();
                double rate = (double) failures / outcomeWindow.size() * 100.0;
                if (rate >= failureRateThresholdPct) {
                    if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                        transition(State.CLOSED, State.OPEN, String.format("Failure rate %.1f%% exceeded threshold %.1f%%", rate, failureRateThresholdPct));
                    }
                }
            }
        }
    }

    public void acquirePermission() {
        State current = getState();
        if (current == State.OPEN) {
            long remaining = Math.max(0, waitDurationInOpenStateMs - (System.currentTimeMillis() - lastStateTransitionTime));
            throw new CircuitBreakerOpenException(partnerId, remaining);
        }
    }

    private void transition(State from, State to, String reason) {
        lastStateTransitionTime = System.currentTimeMillis();
        if (db != null) {
            try {
                db.sql("""
                    INSERT INTO circuit_breaker_event
                    (id, partner_id, from_state, to_state, failure_rate_pct, slow_call_rate_pct, transition_reason)
                    VALUES (?, ?, ?, ?, 0.0, 0.0, ?)
                """).params(UUID.randomUUID(), partnerId, from.name(), to.name(), reason)
                .update();
            } catch (Exception ignored) {
                // Best-effort audit logging
            }
        }
    }

    public String getPartnerId() { return partnerId; }
}
