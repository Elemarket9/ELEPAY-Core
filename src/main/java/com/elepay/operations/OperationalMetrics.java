package com.elepay.operations;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
            Map.entry("elepay.outbox.oldest_pending_seconds",
                    "SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0) FROM outbox_event WHERE published_at IS NULL"),
            Map.entry("elepay.webhooks.dead", "SELECT count(*) FROM webhook_delivery WHERE status = 'dead'"),
            Map.entry("elepay.rails.unresolved", "SELECT count(*) FROM rail_transaction WHERE status IN ('created', 'pending', 'unknown')"),
            Map.entry("elepay.rails.unresolved_over_1h",
                    "SELECT count(*) FROM rail_transaction WHERE status IN ('created', 'pending', 'unknown') AND created_at < now() - interval '1 hour'"),
            Map.entry("elepay.ops.open_high", "SELECT count(*) FROM ops_item WHERE status = 'open' AND severity = 'high'"),
            Map.entry("elepay.ops.open_total", "SELECT count(*) FROM ops_item WHERE status = 'open'"),
            Map.entry("elepay.disputes.open", "SELECT count(*) FROM dispute WHERE status = 'open'"),
            Map.entry("elepay.audit.unsealed_over_60s",
                    "SELECT count(*) FROM audit_log a WHERE a.created_at < now() - interval '60 seconds' AND NOT EXISTS (SELECT 1 FROM audit_chain c WHERE c.audit_id = a.id)"),
            Map.entry("elepay.ledger.snapshot_age_seconds",
                    "SELECT coalesce(extract(epoch FROM now() - max(captured_at)), -1) FROM ledger_snapshot_run"),
            Map.entry("elepay.ledger.snapshot_drift",
                    "SELECT count(*) FROM ledger_balance_snapshot s WHERE s.snapshot_day = (SELECT max(snapshot_day) FROM ledger_snapshot_run) AND s.balance <> s.journal_balance"));

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
    void refresh() {
        QUERIES.forEach((name, sql) -> {
            try {
                values.put(name, db.sql(sql).query(Number.class).single().doubleValue());
            } catch (RuntimeException e) {
                values.put(name, Double.NaN);
            }
        });
    }
}
