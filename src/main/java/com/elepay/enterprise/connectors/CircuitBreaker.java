package com.elepay.enterprise.connectors;

import com.elepay.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe Circuit Breaker for enterprise connectors.
 * States:
 * - CLOSED: Normal operation, requests flow through. Consecutive failures increment counter.
 * - OPEN: Tripped after failure threshold. Rejects requests immediately with fail-fast exception.
 * - HALF_OPEN: Probe state after cooldown reset period. Single trial request allowed.
 */
public class CircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final String name;
    private final int failureThreshold;
    private final long resetTimeoutMillis;
    private final Clock clock;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private volatile Instant lastStateChange;

    public CircuitBreaker(String name, int failureThreshold, long resetTimeoutMillis, Clock clock) {
        this.name = name;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.resetTimeoutMillis = Math.max(1000L, resetTimeoutMillis);
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.lastStateChange = Instant.now(this.clock);
    }

    public CircuitBreaker(String name) {
        this(name, 5, 30_000L, Clock.systemUTC());
    }

    public String getName() {
        return name;
    }

    public State getState() {
        checkResetTimeout();
        return state.get();
    }

    public boolean allowRequest() {
        checkResetTimeout();
        State current = state.get();
        return current == State.CLOSED || current == State.HALF_OPEN;
    }

    public void recordSuccess() {
        State current = state.get();
        if (current == State.HALF_OPEN) {
            if (successCount.incrementAndGet() >= 2) {
                transitionTo(State.CLOSED);
                failureCount.set(0);
                successCount.set(0);
            }
        } else if (current == State.CLOSED) {
            failureCount.set(0);
        }
    }

    public void recordFailure() {
        State current = state.get();
        if (current == State.HALF_OPEN) {
            transitionTo(State.OPEN);
        } else if (current == State.CLOSED) {
            int failures = failureCount.incrementAndGet();
            if (failures >= failureThreshold) {
                transitionTo(State.OPEN);
            }
        }
    }

    public void reset() {
        failureCount.set(0);
        successCount.set(0);
        transitionTo(State.CLOSED);
    }

    public void trip() {
        transitionTo(State.OPEN);
    }

    private void checkResetTimeout() {
        if (state.get() == State.OPEN) {
            Instant now = Instant.now(clock);
            if (now.toEpochMilli() - lastStateChange.toEpochMilli() >= resetTimeoutMillis) {
                state.compareAndSet(State.OPEN, State.HALF_OPEN);
                lastStateChange = now;
                successCount.set(0);
            }
        }
    }

    private void transitionTo(State target) {
        state.set(target);
        lastStateChange = Instant.now(clock);
    }
}
