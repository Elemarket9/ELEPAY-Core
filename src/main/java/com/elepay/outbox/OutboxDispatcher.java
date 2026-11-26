package com.elepay.outbox;

import java.sql.Array;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls unpublished outbox rows and delivers them to every consumer.
 * Multiple instances can run concurrently: rows are claimed with
 * {@code FOR NO KEY UPDATE SKIP LOCKED}, which still lets consumers insert
 * rows that reference the event (foreign keys take a key-share lock). A crash after a consumer commits but before
 * the row is marked published causes redelivery, which the inbox absorbs.
 */
@Component
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final JdbcClient db;
    private final List<OutboxConsumer> consumers;
    private final TransactionTemplate claimTx;
    private final TransactionTemplate consumerTx;
    private final boolean enabled;

    OutboxDispatcher(JdbcClient db, List<OutboxConsumer> consumers, PlatformTransactionManager tm,
                     @Value("${elepay.outbox.enabled:true}") boolean enabled) {
        this.db = db;
        this.consumers = consumers;
        this.claimTx = new TransactionTemplate(tm);
        this.consumerTx = new TransactionTemplate(tm);
        this.consumerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.enabled = enabled;
    }

    @Value("${elepay.outbox.batch-size:100}")
    private int batchSize = 100;

    @Value("${elepay.outbox.max-attempts:12}")
    private int maxAttempts = 12;

    @Scheduled(fixedDelayString = "${elepay.outbox.poll-ms:1000}")
    void poll() {
        if (enabled) dispatchBatch(batchSize);
    }

    @Value("${elepay.outbox.archive-after-days:0}")
    private int archiveAfterDays;

    @Scheduled(fixedDelayString = "${elepay.outbox.archive-poll-ms:60000}")
    void archivePublished() {
        if (enabled && archiveAfterDays > 0) {
            db.sql("SELECT archive_published_outbox(:days, :batch)")
                    .param("days", archiveAfterDays).param("batch", Math.clamp(batchSize, 1, 1000))
                    .query(Long.class).single();
        }
    }

    /** Delivers up to {@code limit} events; returns the number marked published. */
    public int dispatchBatch(int limit) {
        UUID claim = UUID.randomUUID();
        List<Outbox.Event> batch = claimTx.execute(status -> {
            return db.sql("""
                    WITH due AS (
                        SELECT e.id FROM outbox_event e
                        WHERE e.published_at IS NULL AND e.dead_at IS NULL AND e.next_attempt_at <= now()
                          AND NOT EXISTS (
                              SELECT 1 FROM outbox_event earlier
                              WHERE earlier.aggregate_type = e.aggregate_type AND earlier.aggregate_id = e.aggregate_id
                                AND earlier.published_at IS NULL AND earlier.sequence < e.sequence)
                        ORDER BY e.sequence LIMIT :limit FOR NO KEY UPDATE OF e SKIP LOCKED
                    )
                    UPDATE outbox_event e SET claim_token = :claim, next_attempt_at = now() + interval '60 seconds'
                    FROM due WHERE e.id = due.id
                    RETURNING e.id, e.sequence, e.aggregate_type, e.aggregate_id, e.event_type, e.payload::text, e.merchant_ids""")
                    .param("claim", claim).param("limit", Math.clamp(limit, 1, 1000))
                    .query((rs, i) -> {
                        Array array = rs.getArray(7);
                        UUID[] merchants = array == null ? new UUID[0] : (UUID[]) array.getArray();
                        return new Outbox.Event(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), rs.getString(6), merchants);
                    }).list();
        });
        if (batch == null || batch.isEmpty()) return 0;

        int done = 0;
        for (Outbox.Event event : batch) {
            try {
                deliver(event);
                Integer updated = claimTx.execute(status ->
                        db.sql("""
                                UPDATE outbox_event SET published_at = now(), attempts = attempts + 1,
                                    last_error = NULL, claim_token = NULL, next_attempt_at = now()
                                WHERE id = :id AND claim_token = :claim AND published_at IS NULL""")
                                .param("id", event.id()).param("claim", claim).update());
                if (updated != null) done += updated;
            } catch (RuntimeException e) {
                String failureType = e.getClass().getSimpleName();
                log.warn("Outbox delivery failed for event {} ({})", event.id(), failureType);
                claimTx.executeWithoutResult(status ->
                        db.sql("""
                                UPDATE outbox_event SET attempts = attempts + 1, last_error = :err, claim_token = NULL,
                                       dead_at = CASE WHEN attempts + 1 >= :max THEN now() END,
                                       next_attempt_at = now() + make_interval(secs =>
                                           (0.5 + random() / 2) * least(300, power(2, least(attempts, 9))))
                                WHERE id = :id AND claim_token = :claim AND published_at IS NULL""")
                                .param("claim", claim).param("max", Math.max(1, maxAttempts))
                                .param("id", event.id()).param("err", failureType).update());
            }
        }
        return done;
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
