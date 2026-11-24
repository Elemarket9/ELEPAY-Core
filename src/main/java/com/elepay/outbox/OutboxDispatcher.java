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

    @Scheduled(fixedDelayString = "${elepay.outbox.poll-ms:1000}")
    void poll() {
        if (enabled) dispatchBatch(100);
    }

    /** Delivers up to {@code limit} events; returns the number marked published. */
    public int dispatchBatch(int limit) {
        List<Outbox.Event> batch = claimTx.execute(status -> {
            List<Outbox.Event> claimed = db.sql("""
                    SELECT id, sequence, aggregate_type, aggregate_id, event_type, payload::text, merchant_ids
                    FROM outbox_event
                    WHERE published_at IS NULL AND next_attempt_at <= now()
                    ORDER BY sequence LIMIT :limit FOR NO KEY UPDATE SKIP LOCKED""")
                    .param("limit", limit)
                    .query((rs, i) -> {
                        Array array = rs.getArray(7);
                        UUID[] merchants = array == null ? new UUID[0] : (UUID[]) array.getArray();
                        return new Outbox.Event(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), rs.getString(6), merchants);
                    }).list();
            for (Outbox.Event event : claimed) {
                db.sql("UPDATE outbox_event SET next_attempt_at = now() + interval '60 seconds' WHERE id = :id")
                        .param("id", event.id()).update();
            }
            return claimed;
        });
        if (batch == null || batch.isEmpty()) return 0;

        int done = 0;
        for (Outbox.Event event : batch) {
            try {
                deliver(event);
                claimTx.executeWithoutResult(status ->
                        db.sql("UPDATE outbox_event SET published_at = now(), attempts = attempts + 1, last_error = NULL, next_attempt_at = now() WHERE id = :id")
                                .param("id", event.id()).update());
                done++;
            } catch (RuntimeException e) {
                log.warn("Outbox event {} delivery failed: {}", event.id(), e.toString());
                claimTx.executeWithoutResult(status ->
                        db.sql("""
                                UPDATE outbox_event SET attempts = attempts + 1, last_error = :err,
                                       next_attempt_at = now() + make_interval(secs => least(300, power(2, attempts)))
                                WHERE id = :id""")
                                .param("id", event.id()).param("err", String.valueOf(e.getMessage())).update());
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
