package com.elepay.common.datasource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * Tracks per-cell read-replica health and replication lag with a cooldown circuit breaker.
 *
 * <p>Cells default to healthy until a probe failure or replication lag exceeding {@code maxLagMs}
 * is recorded. When tripped, {@link #isReplicaHealthy(String)} returns {@code false} for the
 * configured cooldown window so {@link CellAwareRoutingDataSource} falls back to
 * {@link RouteType#PRIMARY}. Once the cooldown expires, the circuit half-opens and allows
 * replica routing/probing again.
 */
public class ReplicaHealthTracker {

    public static final long DEFAULT_MAX_LAG_MS = 2_000L;
    public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(15);

    private static final String LAG_PROBE_SQL =
            "SELECT CASE "
                    + "WHEN NOT pg_is_in_recovery() THEN 0 "
                    + "WHEN pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn() THEN 0 "
                    + "ELSE COALESCE(EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp())) * 1000, 0) "
                    + "END";

    private record CellHealthState(
            boolean healthy,
            long lastLagMs,
            int consecutiveFailures,
            Instant unhealthyUntil) {}

    private final long maxLagMs;
    private final Duration cooldown;
    private final Clock clock;
    private final ConcurrentHashMap<String, CellHealthState> states = new ConcurrentHashMap<>();

    public ReplicaHealthTracker() {
        this(DEFAULT_MAX_LAG_MS, DEFAULT_COOLDOWN, Clock.systemUTC());
    }

    public ReplicaHealthTracker(long maxLagMs) {
        this(maxLagMs, DEFAULT_COOLDOWN, Clock.systemUTC());
    }

    public ReplicaHealthTracker(long maxLagMs, Duration cooldown) {
        this(maxLagMs, cooldown, Clock.systemUTC());
    }

    public ReplicaHealthTracker(long maxLagMs, Duration cooldown, Clock clock) {
        if (maxLagMs < 0) {
            throw new IllegalArgumentException("maxLagMs must be >= 0");
        }
        Objects.requireNonNull(cooldown, "cooldown must not be null");
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown must not be negative");
        }
        this.maxLagMs = maxLagMs;
        this.cooldown = cooldown;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Returns {@code true} if the replica for {@code cellId} is eligible to receive read traffic.
     * Defaults to {@code true} when no failure or excessive lag has been recorded.
     */
    public boolean isReplicaHealthy(String cellId) {
        CellHealthState state = states.get(normalizeCellId(cellId));
        if (state == null || state.healthy()) {
            return true;
        }
        Instant now = clock.instant();
        return state.unhealthyUntil() != null && !now.isBefore(state.unhealthyUntil());
    }

    /**
     * Records a replica probe/connection failure for {@code cellId}, tripping the circuit breaker
     * for {@code cooldown} so reads automatically fall back to {@link RouteType#PRIMARY}.
     */
    public void recordReplicaFailure(String cellId) {
        String key = normalizeCellId(cellId);
        Instant until = clock.instant().plus(cooldown);
        states.compute(key, (k, prev) -> {
            int failures = prev == null ? 1 : prev.consecutiveFailures() + 1;
            long lag = prev == null ? 0L : prev.lastLagMs();
            return new CellHealthState(false, lag, failures, until);
        });
    }

    /**
     * Records a successful replica probe with the observed replication lag in milliseconds.
     * If {@code lagMs > maxLagMs}, trips the circuit breaker to unhealthy; otherwise marks the
     * replica healthy and resets consecutive failure counters.
     */
    public void recordReplicaHealthy(String cellId, long lagMs) {
        String key = normalizeCellId(cellId);
        long normalizedLag = Math.max(0L, lagMs);
        if (normalizedLag > maxLagMs) {
            Instant until = clock.instant().plus(cooldown);
            states.compute(key, (k, prev) -> {
                int failures = prev == null ? 1 : prev.consecutiveFailures() + 1;
                return new CellHealthState(false, normalizedLag, failures, until);
            });
        } else {
            states.put(key, new CellHealthState(true, normalizedLag, 0, null));
        }
    }

    /**
     * Actively probes a PostgreSQL read replica for connectivity and replication lag, updating
     * health state accordingly.
     */
    public boolean probeReplica(String cellId, DataSource replicaDataSource) {
        if (replicaDataSource == null) {
            return false;
        }
        try (Connection conn = replicaDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(LAG_PROBE_SQL);
             ResultSet rs = ps.executeQuery()) {
            long lagMs = 0L;
            if (rs.next()) {
                lagMs = Math.round(rs.getDouble(1));
            }
            recordReplicaHealthy(cellId, lagMs);
            return isReplicaHealthy(cellId);
        } catch (SQLException ex) {
            recordReplicaFailure(cellId);
            return false;
        }
    }

    public long getMaxLagMs() {
        return maxLagMs;
    }

    public Duration getCooldown() {
        return cooldown;
    }

    public long getLastLagMs(String cellId) {
        CellHealthState state = states.get(normalizeCellId(cellId));
        return state == null ? 0L : state.lastLagMs();
    }

    public int getConsecutiveFailures(String cellId) {
        CellHealthState state = states.get(normalizeCellId(cellId));
        return state == null ? 0 : state.consecutiveFailures();
    }

    public void reset(String cellId) {
        states.remove(normalizeCellId(cellId));
    }

    public void resetAll() {
        states.clear();
    }

    private static String normalizeCellId(String cellId) {
        return (cellId == null || cellId.isBlank()) ? CellDataSourceKey.DEFAULT_CELL_ID : cellId.trim();
    }
}
