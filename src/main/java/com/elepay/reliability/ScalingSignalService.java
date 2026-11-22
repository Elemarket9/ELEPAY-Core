package com.elepay.reliability;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 092: Scaling signals and capacity metrics.
 * Evaluates connection pool saturation, outbox lag, queue depth, and p99 latency
 * to emit actionable autoscaling signals (SCALE_UP, SCALE_DOWN, STABLE).
 */
@Service
public class ScalingSignalService {

    private final JdbcClient db;

    public ScalingSignalService(JdbcClient db) {
        this.db = db;
    }

    public enum ScaleAction {
        SCALE_UP,
        SCALE_DOWN,
        STABLE
    }

    public record ScalingSignal(
            UUID id,
            String component,
            double poolUtilizationPct,
            int queueDepth,
            long outboxLagMs,
            long p99LatencyMs,
            ScaleAction recommendation,
            String reason
    ) {}

    @Transactional
    public ScalingSignal evaluateScaling(String component, double poolUtilizationPct,
                                         int queueDepth, long outboxLagMs, long p99LatencyMs) {
        ScaleAction action;
        String reason;

        if (poolUtilizationPct > 80.0 || queueDepth > 50 || outboxLagMs > 5000L || p99LatencyMs > 1000L) {
            action = ScaleAction.SCALE_UP;
            reason = String.format("High bottleneck detected: pool=%.1f%%, queue=%d, outboxLag=%dms, p99=%dms",
                    poolUtilizationPct, queueDepth, outboxLagMs, p99LatencyMs);
        } else if (poolUtilizationPct < 20.0 && queueDepth < 5 && outboxLagMs < 200L && p99LatencyMs < 100L) {
            action = ScaleAction.SCALE_DOWN;
            reason = "Capacity underutilized across all metrics; scale down safe";
        } else {
            action = ScaleAction.STABLE;
            reason = "Capacity metrics within nominal operating boundaries";
        }

        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO scaling_signal_snapshot
            (id, component, pool_utilization_pct, queue_depth, outbox_lag_ms, p99_latency_ms, scale_recommendation)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """).params(id, component,
                BigDecimal.valueOf(poolUtilizationPct).setScale(2, RoundingMode.HALF_UP),
                queueDepth, outboxLagMs, p99LatencyMs, action.name())
        .update();

        return new ScalingSignal(id, component, poolUtilizationPct, queueDepth, outboxLagMs, p99LatencyMs, action, reason);
    }
}
