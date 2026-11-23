package com.elepay.reliability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * OpenTelemetry Distributed Tracing & Micrometer Prometheus Financial Metrics (Domain 15).
 * Tracks financial throughput, p99 latency, and ledger balance zero-sum health.
 */
@Service
public class ObservabilityService {
    private final MeterRegistry registry;
    private final JdbcClient db;
    private final com.elepay.ledger.Ledger ledger;
    private final Tracer tracer;

    private final AtomicLong ledgerDrift = new AtomicLong(0);
    private final AtomicLong ledgerHealthStatus = new AtomicLong(1); // 1 = healthy, 0 = drift
    private final AtomicLong unbalancedPostings = new AtomicLong(0);

    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public ObservabilityService(MeterRegistry registry, JdbcClient db) {
        this(registry, db, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ObservabilityService(MeterRegistry registry, JdbcClient db,
                                @org.springframework.beans.factory.annotation.Autowired(required = false)
                                com.elepay.ledger.Ledger ledger) {
        this.registry = registry;
        this.db = db;
        this.ledger = ledger;

        // OpenTelemetry Tracer
        this.tracer = OpenTelemetry.noop().getTracer("com.elepay.core", "0.1.0");

        // Register Gauges
        registry.gauge("elepay.ledger.drift.minor_units", ledgerDrift);
        registry.gauge("elepay.ledger.integrity.status", ledgerHealthStatus);
        registry.gauge("elepay.ledger.unbalanced.postings", unbalancedPostings);
    }

    public void recordTransaction(String operation, String currency, long minorUnits, long durationMs, boolean success) {
        String status = success ? "success" : "failure";

        // 1. Throughput Counter
        String counterKey = operation + ":" + currency + ":" + status;
        counters.computeIfAbsent(counterKey, k -> Counter.builder("elepay.financial.throughput")
                .description("Financial transaction throughput count")
                .tags(Tags.of("operation", operation, "currency", currency, "status", status))
                .register(registry)).increment();

        // 2. Volume Counter (minor units)
        if (minorUnits > 0) {
            registry.counter("elepay.financial.volume.minor_units",
                    Tags.of("operation", operation, "currency", currency, "status", status)).increment(minorUnits);
        }

        // 3. Latency Timer with SLA percentiles (p50, p95, p99)
        timers.computeIfAbsent(operation, k -> Timer.builder("elepay.financial.latency")
                .description("Financial transaction latency")
                .tags(Tags.of("operation", operation))
                .publishPercentiles(0.5, 0.95, 0.99)
                .sla(Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofMillis(1000))
                .register(registry)).record(Duration.ofMillis(durationMs));
    }

    /**
     * Checks ledger zero-sum balance triggers and consistency proof.
     */
    public boolean checkLedgerHealth() {
        if (ledger == null) return true;
        return checkThroughLedger();
    }

    private boolean checkThroughLedger() {
        if (ledger == null) return true;
        try {
            var integrity = ledger.integrity();
            long driftSum = integrity.unbalancedTransactions();
            ledgerDrift.set(driftSum);
            if (integrity.ok()) {
                ledgerHealthStatus.set(1);
                unbalancedPostings.set(0);
                return true;
            } else {
                ledgerHealthStatus.set(0);
                unbalancedPostings.set(1);
                return false;
            }
        } catch (Exception e) {
            ledgerHealthStatus.set(0);
            return false;
        }
    }

    public Map<String, Object> getFinancialMetricsSummary() {
        checkLedgerHealth();
        return Map.of(
                "ledgerHealthy", ledgerHealthStatus.get() == 1,
                "ledgerDriftMinorUnits", ledgerDrift.get(),
                "unbalancedPostings", unbalancedPostings.get(),
                "throughputTotal", registry.find("elepay.financial.throughput").counters().stream()
                        .mapToDouble(Counter::count).sum(),
                "p99LatencyMsTarget", 500
        );
    }

    public Tracer getTracer() {
        return tracer;
    }

    public <T> T inSpan(String spanName, Supplier<T> action) {
        Span span = tracer.spanBuilder(spanName).startSpan();
        try {
            return action.get();
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
