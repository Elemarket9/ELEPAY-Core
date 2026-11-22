package com.elepay.reliability;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 093: Fault injection and chaos testing harness.
 * Safely injects synthetic network delays, connection partitions, timeouts, and exceptions
 * into partner rail and persistence boundaries during integration testing.
 */
@Service
public class FaultInjectionHarness {

    public enum FailureMode {
        LATENCY,
        TIMEOUT,
        PARTITION,
        INTERNAL_ERROR
    }

    public record ChaosRule(
            UUID id,
            String experimentName,
            String targetRail,
            FailureMode mode,
            int delayMs,
            double errorRatePct,
            boolean active
    ) {}

    public static class InjectedFaultException extends RuntimeException {
        public InjectedFaultException(String message) {
            super(message);
        }
    }

    private final JdbcClient db;
    private final Map<String, ChaosRule> activeRules = new ConcurrentHashMap<>();

    public FaultInjectionHarness(JdbcClient db) {
        this.db = db;
    }

    @Transactional
    public ChaosRule registerExperiment(String experimentName, String targetRail, FailureMode mode, int delayMs, double errorRatePct) {
        UUID id = UUID.randomUUID();
        ChaosRule rule = new ChaosRule(id, experimentName, targetRail, mode, delayMs, errorRatePct, true);
        activeRules.put(targetRail, rule);

        db.sql("""
            INSERT INTO chaos_experiment
            (id, experiment_name, target_rail, failure_mode, delay_ms, error_rate_pct, status, start_time)
            VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', now())
        """).params(id, experimentName, targetRail, mode.name(), delayMs, errorRatePct)
        .update();

        return rule;
    }

    @Transactional
    public void stopExperiment(String targetRail) {
        ChaosRule rule = activeRules.remove(targetRail);
        if (rule != null) {
            db.sql("UPDATE chaos_experiment SET status = 'STOPPED', end_time = now() WHERE target_rail = ? AND status = 'ACTIVE'")
                    .param(targetRail).update();
        }
    }

    /**
     * Intercepts invocation for target rail and executes configured fault if active.
     */
    public void maybeInjectFault(String targetRail) {
        ChaosRule rule = activeRules.get(targetRail);
        if (rule == null || !rule.active()) {
            return;
        }

        // Apply latency if configured
        if (rule.delayMs() > 0) {
            try {
                Thread.sleep(rule.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Check error injection rate
        if (rule.errorRatePct() > 0) {
            double roll = Math.random() * 100.0;
            if (roll < rule.errorRatePct()) {
                switch (rule.mode()) {
                    case TIMEOUT -> throw new InjectedFaultException("Injected timeout failure on rail: " + targetRail);
                    case PARTITION -> throw new InjectedFaultException("Injected network partition to rail: " + targetRail);
                    case INTERNAL_ERROR -> throw new InjectedFaultException("Injected internal failure on rail: " + targetRail);
                    default -> {}
                }
            }
        }
    }
}
