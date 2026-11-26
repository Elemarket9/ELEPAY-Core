package com.elepay.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Array;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Production Transactional Outbox Dispatcher (Requirement 2).
 *
 * <ul>
 *   <li>Claims unpublished rows with {@code FOR NO KEY UPDATE SKIP LOCKED}.</li>
 *   <li>Enforces strict per-aggregate ordering: an event is only claimed if no earlier unpublished
 *       sequence exists for the same {@code (aggregate_type, aggregate_id)}.</li>
 *   <li>Configurable batch size ({@code elepay.outbox.batch-size}) and poll interval ({@code elepay.outbox.poll-ms}).</li>
 *   <li>Exponential backoff with equal jitter on transient failure, moving to {@code DEAD} after
 *       {@code max_attempts} and supporting controlled replay.</li>
 *   <li>Automatic retention archival of published events not referenced by active webhook delivery logs
 *       into partitioned {@code outbox_event_archive}.</li>
 *   <li>Micrometer latency timer and pending/published/failed/dead/archived metrics.</li>
 * </ul>
 */
@Component
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final JdbcClient db;
    private final List<OutboxConsumer> consumers;
    private final TransactionTemplate claimTx;
    private final TransactionTemplate consumerTx;
    private final boolean enabled;
    private final int batchSize;
    private final int retentionDays;
    private final MeterRegistry meterRegistry;

    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final Counter deadCounter;
    private final Counter archivedCounter;
    private final Timer publishLatencyTimer;
    private final AtomicLong pendingGauge = new AtomicLong(0);
    private final AtomicLong deadGauge = new AtomicLong(0);

    public OutboxDispatcher(JdbcClient db, List<OutboxConsumer> consumers, PlatformTransactionManager tm,
                            @Value("${elepay.outbox.enabled:true}") boolean enabled) {
        this(db, consumers, tm, enabled, 100, 30, null);
    }

    @Autowired
    public OutboxDispatcher(JdbcClient db, List<OutboxConsumer> consumers, PlatformTransactionManager tm,
                            @Value("${elepay.outbox.enabled:true}") boolean enabled,
                            @Value("${elepay.outbox.batch-size:100}") int batchSize,
                            @Value("${elepay.outbox.retention-days:30}") int retentionDays,
                            @Autowired(required = false) MeterRegistry meterRegistry) {
        this.db = db;
        this.consumers = new java.util.concurrent.CopyOnWriteArrayList<>(consumers == null ? List.of() : consumers);
        this.claimTx = new TransactionTemplate(tm);
        this.consumerTx = new TransactionTemplate(tm);
        this.consumerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.retentionDays = Math.max(1, retentionDays);
        this.meterRegistry = meterRegistry;

        if (meterRegistry != null) {
            this.publishedCounter = meterRegistry.counter("elepay.outbox.published");
            this.failedCounter = meterRegistry.counter("elepay.outbox.failed");
            this.deadCounter = meterRegistry.counter("elepay.outbox.dead");
            this.archivedCounter = meterRegistry.counter("elepay.outbox.archived");
            this.publishLatencyTimer = Timer.builder("elepay.outbox.publish.latency")
                    .description("End-to-end latency from outbox event creation to publish completion")
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry);
            meterRegistry.gauge("elepay.outbox.pending.count", pendingGauge);
            meterRegistry.gauge("elepay.outbox.dead.count", deadGauge);
        } else {
            this.publishedCounter = null;
            this.failedCounter = null;
            this.deadCounter = null;
            this.archivedCounter = null;
            this.publishLatencyTimer = null;
        }
    }

    public void registerConsumer(OutboxConsumer consumer) {
        if (consumer != null) {
            this.consumers.add(consumer);
        }
    }

    public void unregisterConsumer(OutboxConsumer consumer) {
        if (consumer != null) {
            this.consumers.remove(consumer);
        }
    }

    @Scheduled(fixedDelayString = "${elepay.outbox.poll-ms:1000}")
    void poll() {
        if (enabled) {
            dispatchBatch(batchSize);
            refreshQueueGauges();
        }
    }

    @Scheduled(fixedDelayString = "${elepay.outbox.archive-poll-ms:300000}")
    void archiveJob() {
        if (enabled) {
            archivePublishedEvents(retentionDays, batchSize * 5);
        }
    }

    /** Delivers up to {@code limit} events; returns the number marked published. */
    public int dispatchBatch(int limit) {
        int effectiveLimit = Math.max(1, limit);
        List<Outbox.Event> batch = claimTx.execute(status -> {
            List<Outbox.Event> claimed = db.sql("""
                    SELECT e.id, e.sequence, e.aggregate_type, e.aggregate_id, e.event_type,
                           e.schema_version, e.payload::text, e.merchant_ids, e.created_at
                    FROM outbox_event e
                    WHERE e.published_at IS NULL
                      AND e.status <> 'DEAD'
                      AND e.next_attempt_at <= now()
                      AND NOT EXISTS (
                          SELECT 1 FROM outbox_event earlier
                          WHERE earlier.aggregate_type = e.aggregate_type
                            AND earlier.aggregate_id = e.aggregate_id
                            AND earlier.published_at IS NULL
                            AND earlier.status <> 'DEAD'
                            AND earlier.sequence < e.sequence
                      )
                    ORDER BY e.sequence
                    LIMIT :limit
                    FOR NO KEY UPDATE OF e SKIP LOCKED""")
                    .param("limit", effectiveLimit)
                    .query((rs, i) -> {
                        Array array = rs.getArray(8);
                        UUID[] merchants = array == null ? new UUID[0] : (UUID[]) array.getArray();
                        return new Outbox.Event(
                                rs.getObject(1, UUID.class),
                                rs.getLong(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getInt(6),
                                rs.getString(7),
                                merchants,
                                rs.getObject(9, OffsetDateTime.class));
                    }).list();
            for (Outbox.Event event : claimed) {
                db.sql("""
                        UPDATE outbox_event
                        SET next_attempt_at = now() + interval '60 seconds'
                        WHERE id = :id
                          AND status <> 'DEAD'""")
                        .param("id", event.id()).update();
            }
            return claimed;
        });
        if (batch == null || batch.isEmpty()) return 0;

        int done = 0;
        for (Outbox.Event event : batch) {
            try {
                deliver(event);
                Integer updated = claimTx.execute(status ->
                        db.sql("""
                                UPDATE outbox_event
                                SET published_at = now(),
                                    status = 'PUBLISHED',
                                    attempts = attempts + 1,
                                    last_error = NULL,
                                    next_attempt_at = now()
                                WHERE id = :id
                                  AND status <> 'DEAD'""")
                                .param("id", event.id()).update());
                if (updated != null && updated == 1) {
                    done++;
                    if (publishedCounter != null) publishedCounter.increment();
                    if (publishLatencyTimer != null && event.createdAt() != null) {
                        long elapsedMs = Math.max(0L, Duration.between(event.createdAt(), OffsetDateTime.now()).toMillis());
                        publishLatencyTimer.record(Duration.ofMillis(elapsedMs));
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Outbox event {} delivery failed: {}", event.id(), e.toString());
                if (failedCounter != null) failedCounter.increment();
                handleFailure(event, e);
            }
        }
        return done;
    }

    private void handleFailure(Outbox.Event event, RuntimeException e) {
        String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (err.length() > 1000) err = err.substring(0, 1000);
        final String truncatedErr = err;
        Boolean movedToDead = claimTx.execute(status -> {
            record AttemptRow(int attempts, int maxAttempts, String status) {}
            var rowOpt = db.sql("SELECT attempts, max_attempts, status FROM outbox_event WHERE id = :id FOR NO KEY UPDATE")
                    .param("id", event.id())
                    .query((rs, i) -> new AttemptRow(rs.getInt(1), rs.getInt(2), rs.getString(3)))
                    .optional();
            if (rowOpt.isEmpty() || !"PENDING".equals(rowOpt.get().status())) {
                return false;
            }
            AttemptRow row = rowOpt.get();
            int nextAttempts = row.attempts() + 1;
            if (nextAttempts >= row.maxAttempts()) {
                int updated = db.sql("""
                        UPDATE outbox_event
                        SET attempts = :att,
                            status = 'DEAD',
                            dead_at = now(),
                            last_error = :err
                        WHERE id = :id
                          AND status = 'PENDING'""")
                        .param("att", nextAttempts)
                        .param("err", truncatedErr)
                        .param("id", event.id())
                        .update();
                return updated == 1;
            }
            long backoffMs = computeBackoffWithJitterMs(nextAttempts);
            db.sql("""
                    UPDATE outbox_event
                    SET attempts = :att,
                        last_error = :err,
                        next_attempt_at = now() + (:backoffMs * interval '1 millisecond')
                    WHERE id = :id
                      AND status = 'PENDING'""")
                    .param("att", nextAttempts)
                    .param("err", truncatedErr)
                    .param("backoffMs", backoffMs)
                    .param("id", event.id())
                    .update();
            return false;
        });
        if (Boolean.TRUE.equals(movedToDead) && deadCounter != null) {
            deadCounter.increment();
        }
    }

    static long computeBackoffWithJitterMs(int attempt) {
        int clampedExp = Math.min(Math.max(attempt, 1), 10);
        long capMs = Math.min(300_000L, 1_000L * (1L << clampedExp));
        long half = Math.max(100L, capMs / 2);
        return half + ThreadLocalRandom.current().nextLong(half + 1);
    }

    /**
     * Archives published outbox events older than {@code olderThanDays} into {@code outbox_event_archive},
     * skipping events still referenced by {@code webhook_delivery} or {@code webhook_delivery_log}.
     */
    public int archivePublishedEvents(int olderThanDays, int batchLimit) {
        int effectiveBatch = Math.min(Math.max(batchLimit, 1), 5000);
        Integer archived = claimTx.execute(status -> db.sql("SELECT archive_published_outbox_events(:days, :limit)")
                .param("days", Math.max(0, olderThanDays))
                .param("limit", effectiveBatch)
                .query(Integer.class)
                .single());
        int count = archived == null ? 0 : archived;
        if (count > 0 && archivedCounter != null) {
            archivedCounter.increment(count);
        }
        return count;
    }

    public void refreshQueueGauges() {
        try {
            Long pending = db.sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL AND status = 'PENDING'")
                    .query(Long.class).single();
            Long dead = db.sql("SELECT count(*) FROM outbox_event WHERE status = 'DEAD'")
                    .query(Long.class).single();
            pendingGauge.set(pending == null ? 0L : pending);
            deadGauge.set(dead == null ? 0L : dead);
        } catch (Exception ignored) {
        }
    }

    public long pendingCount() {
        refreshQueueGauges();
        return pendingGauge.get();
    }

    public long deadCount() {
        refreshQueueGauges();
        return deadGauge.get();
    }

    /** Delivers to every consumer; each consumer commits independently with inbox dedupe. */
    public void deliver(Outbox.Event event) {
        for (OutboxConsumer consumer : consumers) {
            if (!consumer.accepts(event.type())) continue;
            consumerTx.executeWithoutResult(status -> {
                int fresh = db.sql("INSERT INTO outbox_consumer_inbox (consumer, event_id) VALUES (:c, :e) ON CONFLICT DO NOTHING")
                        .param("c", consumer.name()).param("e", event.id()).update();
                if (fresh == 1) consumer.handle(event);
            });
        }
    }
}
