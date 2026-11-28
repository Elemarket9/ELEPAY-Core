package com.elepay.operations;

import com.elepay.common.ScheduledJobLock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Operational gauges for alerting, refreshed every 30 seconds from PostgreSQL (never on the scrape
 * path): ledger drift, outbox backlog and age, dead webhooks, unresolved rail results, open
 * exceptions by severity.
 *
 * <p>Each gauge query executes independently (autocommit, no shared {@code @Transactional}) so a
 * single failing or timed-out query cannot poison PostgreSQL with {@code 25P02} and blind all
 * remaining gauges. The full-history {@code ledger_account_reconstruction} drift scan is throttled
 * to a slower cadence ({@code elepay.metrics.drift-check-interval-ms}, default 15m) and leader-locked.
 */
@Component
class OperationalMetrics implements MeterBinder {
    static final String DRIFT_METRIC = "elepay.ledger.accounts_with_drift";
    private static final String DRIFT_SQL =
            "SELECT count(*) FROM ledger_account_reconstruction WHERE projected_balance <> journal_balance";

    private static final Map<String, String> FAST_QUERIES = Map.ofEntries(
            Map.entry("elepay.outbox.backlog", "SELECT count(*) FROM outbox_event WHERE published_at IS NULL"),
            Map.entry("elepay.outbox.oldest_pending_seconds",
                    "SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0) FROM outbox_event WHERE published_at IS NULL"),
            Map.entry("elepay.webhooks.dead", "SELECT count(*) FROM webhook_delivery WHERE status = 'dead'"),
            Map.entry("elepay.rails.unresolved", "SELECT count(*) FROM rail_transaction WHERE status IN ('created', 'pending', 'unknown')"),
            Map.entry("elepay.rails.unresolved_over_1h",
                    "SELECT count(*) FROM rail_transaction WHERE status IN ('created', 'pending', 'unknown') AND created_at < now() - interval '1 hour'"),
            Map.entry("elepay.ops.open_high", "SELECT count(*) FROM ops_item WHERE status = 'open' AND severity = 'high'"),
            Map.entry("elepay.ops.open_total", "SELECT count(*) FROM ops_item WHERE status = 'open'"),
            Map.entry("elepay.disputes.open", "SELECT count(*) FROM dispute WHERE status = 'open'"));

    private final JdbcClient db;
    private final ScheduledJobLock jobLock;
    private final long driftCheckIntervalMs;
    private final Map<String, Double> values = new ConcurrentHashMap<>();
    private final AtomicLong lastDriftCheckMs = new AtomicLong(0L);

    OperationalMetrics(JdbcClient db) {
        this(db, null, 900_000L);
    }

    @Autowired
    OperationalMetrics(
            JdbcClient db,
            @Autowired(required = false) ScheduledJobLock jobLock,
            @Value("${elepay.metrics.drift-check-interval-ms:900000}") long driftCheckIntervalMs
    ) {
        this.db = db;
        this.jobLock = jobLock;
        this.driftCheckIntervalMs = Math.max(0L, driftCheckIntervalMs);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(DRIFT_METRIC, values, v -> v.getOrDefault(DRIFT_METRIC, Double.NaN)).register(registry);
        FAST_QUERIES.keySet().forEach(name -> Gauge.builder(name, values, v -> v.getOrDefault(name, Double.NaN)).register(registry));
    }

    @Scheduled(fixedDelayString = "${elepay.metrics.refresh-ms:30000}", initialDelay = 5000)
    public void refresh() {
        FAST_QUERIES.forEach(this::refreshGauge);
        long now = System.currentTimeMillis();
        long prev = lastDriftCheckMs.get();
        if (prev == 0L || (now - prev) >= driftCheckIntervalMs) {
            if (lastDriftCheckMs.compareAndSet(prev, now)) {
                Runnable driftTask = () -> refreshGauge(DRIFT_METRIC, DRIFT_SQL);
                if (jobLock != null) {
                    jobLock.runIfLeader("metrics-ledger-drift", driftTask);
                } else {
                    driftTask.run();
                }
            }
        }
    }

    private void refreshGauge(String name, String sql) {
        try {
            values.put(name, db.sql(sql).query(Number.class).single().doubleValue());
        } catch (RuntimeException e) {
            values.put(name, Double.NaN);
        }
    }
}
