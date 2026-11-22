package com.elepay.rails;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Algorithm 016: Network health detection.
 * Maintains sliding-window partner health signals (error rates, timeouts, latencies)
 * and operates a circuit breaker state machine (CLOSED, OPEN, HALF_OPEN) for each rail.
 */
@Service
public class NetworkHealthDetector {
    private static final Logger log = LoggerFactory.getLogger(NetworkHealthDetector.class);

    public enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    private static final int WINDOW_SIZE = 50;
    private static final int MIN_SAMPLES_FOR_CIRCUIT = 5;
    private static final double ERROR_RATE_THRESHOLD = 0.40; // 40% error rate trips circuit
    private static final int CONSECUTIVE_TIMEOUTS_THRESHOLD = 3;
    private static final Duration OPEN_COOLDOWN = Duration.ofSeconds(30);

    public record RequestMetric(
            Instant timestamp,
            boolean success,
            boolean timeout,
            long latencyMs
    ) {}

    public record RailHealth(
            String rail,
            CircuitState circuitState,
            int totalRequests,
            int successfulRequests,
            int failedRequests,
            int timeoutRequests,
            double errorRate,
            double averageLatencyMs,
            double p95LatencyMs,
            Instant lastStateChange
    ) {}

    private static class RailState {
        final Deque<RequestMetric> metrics = new ConcurrentLinkedDeque<>();
        volatile CircuitState state = CircuitState.CLOSED;
        volatile Instant lastStateChange = Instant.now();
        volatile int consecutiveTimeouts = 0;
    }

    private final JdbcClient db;
    private final Map<String, RailState> railStates = new ConcurrentHashMap<>();

    public NetworkHealthDetector(JdbcClient db) {
        this.db = db;
    }

    private RailState getOrCreate(String rail) {
        return railStates.computeIfAbsent(rail, r -> new RailState());
    }

    /**
     * Records an execution sample from an external rail call.
     */
    public void record(String rail, boolean success, boolean timeout, long latencyMs) {
        RailState rs = getOrCreate(rail);
        RequestMetric m = new RequestMetric(Instant.now(), success, timeout, Math.max(0, latencyMs));
        rs.metrics.addLast(m);
        while (rs.metrics.size() > WINDOW_SIZE) {
            rs.metrics.pollFirst();
        }

        if (timeout) {
            rs.consecutiveTimeouts++;
        } else if (success) {
            rs.consecutiveTimeouts = 0;
        }

        evaluateCircuit(rail, rs, success);
    }

    private synchronized void evaluateCircuit(String rail, RailState rs, boolean lastSuccess) {
        Instant now = Instant.now();

        if (rs.state == CircuitState.HALF_OPEN) {
            if (lastSuccess) {
                rs.state = CircuitState.CLOSED;
                rs.lastStateChange = now;
                rs.consecutiveTimeouts = 0;
                log.info("Rail {} probe succeeded -> Circuit transitioned from HALF_OPEN to CLOSED", rail);
            } else {
                rs.state = CircuitState.OPEN;
                rs.lastStateChange = now;
                log.warn("Rail {} probe failed -> Circuit transitioned back to OPEN", rail);
            }
            return;
        }

        if (rs.state == CircuitState.OPEN) {
            if (now.isAfter(rs.lastStateChange.plus(OPEN_COOLDOWN))) {
                rs.state = CircuitState.HALF_OPEN;
                rs.lastStateChange = now;
                log.info("Rail {} cooldown elapsed -> Circuit transitioned from OPEN to HALF_OPEN (probing)", rail);
            }
            return;
        }

        // Circuit is CLOSED: check if trip threshold exceeded
        if (rs.consecutiveTimeouts >= CONSECUTIVE_TIMEOUTS_THRESHOLD) {
            rs.state = CircuitState.OPEN;
            rs.lastStateChange = now;
            log.warn("Rail {} tripped circuit to OPEN due to {} consecutive timeouts", rail, rs.consecutiveTimeouts);
            return;
        }

        List<RequestMetric> samples = new ArrayList<>(rs.metrics);
        if (samples.size() >= MIN_SAMPLES_FOR_CIRCUIT) {
            long failures = samples.stream().filter(s -> !s.success()).count();
            double errorRate = (double) failures / samples.size();
            if (errorRate >= ERROR_RATE_THRESHOLD) {
                rs.state = CircuitState.OPEN;
                rs.lastStateChange = now;
                log.warn("Rail {} tripped circuit to OPEN: error rate {}% exceeds threshold {}% (samples={})",
                        rail, (int)(errorRate * 100), (int)(ERROR_RATE_THRESHOLD * 100), samples.size());
            }
        }
    }

    /**
     * Checks if a rail is available to accept traffic (CLOSED or eligible HALF_OPEN probe).
     */
    public boolean isRailAvailable(String rail) {
        RailState rs = getOrCreate(rail);
        if (rs.state == CircuitState.CLOSED) return true;
        if (rs.state == CircuitState.OPEN) {
            // Check if cooldown elapsed for probe
            if (Instant.now().isAfter(rs.lastStateChange.plus(OPEN_COOLDOWN))) {
                synchronized (this) {
                    if (rs.state == CircuitState.OPEN && Instant.now().isAfter(rs.lastStateChange.plus(OPEN_COOLDOWN))) {
                        rs.state = CircuitState.HALF_OPEN;
                        rs.lastStateChange = Instant.now();
                        log.info("Rail {} transitioned to HALF_OPEN for recovery probe", rail);
                        return true;
                    }
                }
            }
            return false;
        }
        // In HALF_OPEN, allows single probe
        return true;
    }

    /**
     * Retrieves current health statistics for the given rail.
     */
    public RailHealth getHealth(String rail) {
        RailState rs = getOrCreate(rail);
        List<RequestMetric> samples = new ArrayList<>(rs.metrics);
        int total = samples.size();
        int successful = (int) samples.stream().filter(RequestMetric::success).count();
        int failed = (int) samples.stream().filter(s -> !s.success()).count();
        int timeout = (int) samples.stream().filter(RequestMetric::timeout).count();
        double errorRate = total == 0 ? 0.0 : (double) failed / total;

        double avgLatency = samples.stream().mapToLong(RequestMetric::latencyMs).average().orElse(0.0);
        List<Long> latencies = samples.stream().map(RequestMetric::latencyMs).sorted().toList();
        double p95 = 0.0;
        if (!latencies.isEmpty()) {
            int idx = (int) Math.ceil(0.95 * latencies.size()) - 1;
            p95 = latencies.get(Math.max(0, Math.min(idx, latencies.size() - 1)));
        }

        return new RailHealth(
                rail, rs.state, total, successful, failed, timeout,
                errorRate, avgLatency, p95, rs.lastStateChange
        );
    }

    /**
     * Manually trips a rail's circuit to OPEN (for ops/testing).
     */
    public void tripCircuit(String rail) {
        RailState rs = getOrCreate(rail);
        rs.state = CircuitState.OPEN;
        rs.lastStateChange = Instant.now();
        log.warn("Circuit for rail {} manually tripped to OPEN", rail);
    }

    /**
     * Resets a rail's circuit to CLOSED.
     */
    public void resetCircuit(String rail) {
        RailState rs = getOrCreate(rail);
        rs.state = CircuitState.CLOSED;
        rs.lastStateChange = Instant.now();
        rs.consecutiveTimeouts = 0;
        rs.metrics.clear();
        log.info("Circuit for rail {} reset to CLOSED", rail);
    }

    /**
     * Periodically persists sliding window snapshots to the database.
     */
    @Scheduled(fixedDelay = 60000)
    public void persistHealthWindows() {
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(60);
        for (Map.Entry<String, RailState> entry : railStates.entrySet()) {
            String rail = entry.getKey();
            RailHealth h = getHealth(rail);
            try {
                db.sql("""
                        INSERT INTO rail_health_window (id, rail, window_start, window_end, total_requests,
                                                       successful_requests, failed_requests, timeout_requests,
                                                       average_latency_ms, p95_latency_ms, circuit_state)
                        VALUES (:id, :rail, :start, :end, :tot, :succ, :fail, :to, :avg, :p95, :st)""")
                        .param("id", UUID.randomUUID())
                        .param("rail", rail)
                        .param("start", windowStart.toString())
                        .param("end", now.toString())
                        .param("tot", h.totalRequests())
                        .param("succ", h.successfulRequests())
                        .param("fail", h.failedRequests())
                        .param("to", h.timeoutRequests())
                        .param("avg", (int) Math.round(h.averageLatencyMs()))
                        .param("p95", (int) Math.round(h.p95LatencyMs()))
                        .param("st", h.circuitState().name().toLowerCase(Locale.ROOT))
                        .update();
            } catch (Exception e) {
                log.debug("Could not persist health window for {}: {}", rail, e.getMessage());
            }
        }
    }
}
