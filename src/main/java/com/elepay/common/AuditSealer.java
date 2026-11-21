package com.elepay.common;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled sealing and independent full-chain verification. Audit inserts never wait for sealing. */
@Component
class AuditSealer {
    private final JdbcClient db;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final AtomicLong violations = new AtomicLong(-1);
    private final AtomicLong lastVerifiedEpoch = new AtomicLong(0);

    AuditSealer(JdbcClient db, MeterRegistry metrics,
                 @Value("${elepay.audit.sealing-enabled:true}") boolean enabled) {
        this.db = db;
        this.metrics = metrics;
        this.enabled = enabled;
        metrics.gauge("elepay.audit.chain_violations", violations);
        metrics.gauge("elepay.audit.last_verified_epoch_seconds", lastVerifiedEpoch);
    }

    @Scheduled(fixedDelayString = "${elepay.audit.sealing-ms:1000}", initialDelay = 5000)
    void scheduledSeal() {
        if (!enabled) return;
        try {
            int count = seal(1000);
            metrics.counter("elepay.audit.sealed").increment(count);
        } catch (RuntimeException e) {
            metrics.counter("elepay.audit.seal_errors").increment();
            // Never include raw audit payloads in an error or metric tag.
            throw new IllegalStateException("Audit sealing failed");
        }
    }

    int seal(int batch) {
        return db.sql("SELECT elepay_seal_audit(:batch)").param("batch", batch).query(Integer.class).single();
    }

    @Scheduled(fixedDelayString = "${elepay.audit.verify-ms:3600000}", initialDelay = 30000)
    void scheduledVerify() {
        if (!enabled) return;
        try {
            violations.set(db.sql("SELECT count(*) FROM audit_chain_violations").query(Long.class).single());
            lastVerifiedEpoch.set(java.time.Instant.now().getEpochSecond());
        } catch (RuntimeException e) {
            violations.set(-1);
            metrics.counter("elepay.audit.verification_errors").increment();
            throw new IllegalStateException("Audit verification failed");
        }
    }
}
