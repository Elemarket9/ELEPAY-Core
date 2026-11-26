package com.elepay.operations;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operational gauges for alerting, refreshed every 30 seconds from
 * PostgreSQL (never on the scrape path): ledger drift, outbox backlog and
 * age, dead webhooks, unresolved rail results, open exceptions by severity.
 */
@Component
class OperationalMetrics implements MeterBinder {
    private static final Map<String, String> QUERIES = Map.ofEntries(
            Map.entry("elepay.ledger.accounts_with_drift",
                    "SELECT count(*) FROM ledger_account_reconstruction WHERE projected_balance <> journal_balance"),
            Map.entry("elepay.outbox.backlog", "SELECT count(*) FROM outbox_event WHERE published_at IS NULL"),
            Map.entry("elepay.outbox.dead", "SELECT count(*) FROM outbox_event WHERE dead_at IS NOT NULL"),
            Map.entry("elepay.postgres.waiting_locks", "SELECT count(*) FROM pg_locks WHERE NOT granted"),
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
    private final Map<String, Double> values = new ConcurrentHashMap<>();

    OperationalMetrics(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        QUERIES.keySet().forEach(name -> Gauge.builder(name, values, v -> v.getOrDefault(name, Double.NaN)).register(registry));
    }

    @Scheduled(fixedDelayString = "${elepay.metrics.refresh-ms:30000}", initialDelay = 5000)
    @Transactional(readOnly = true)
    public void refresh() {
        QUERIES.forEach((name, sql) -> {
            try {
                values.put(name, db.sql(sql).query(Number.class).single().doubleValue());
            } catch (RuntimeException e) {
                values.put(name, Double.NaN);
            }
        });
    }
}
