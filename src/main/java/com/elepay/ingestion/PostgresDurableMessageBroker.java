package com.elepay.ingestion;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider-Neutral Durable Message Broker Implementation (Requirement 3).
 *
 * <p>Backed by ELEPAY's WAL-durable PostgreSQL group-commit {@link IngestionBatchWriter} with optional
 * {@link RedisIngestionStream} acceleration. Supports configurable provider mode
 * ({@code elepay.broker.provider=postgres-wal | redis-streams}), publisher confirmation after commit,
 * explicit consumer {@code acknowledge}/{@code negativeAcknowledge}, ordering keys, poison-message
 * DLQ isolation, and Micrometer queue-depth / consumer-lag gauges.
 */
@Service
public class PostgresDurableMessageBroker implements DurableMessageBroker {

    private final IngestionQueue ingestionQueue;
    private final JdbcClient db;
    private final RedisIngestionStream redisStream;
    private final String configuredProvider;
    private final AtomicLong pendingDepthGauge = new AtomicLong(0);
    private final AtomicLong consumerLagMsGauge = new AtomicLong(0);

    @Autowired
    public PostgresDurableMessageBroker(
            IngestionQueue ingestionQueue,
            JdbcClient db,
            @Autowired(required = false) RedisIngestionStream redisStream,
            @Autowired(required = false) MeterRegistry meterRegistry,
            @Value("${elepay.broker.provider:postgres-wal}") String configuredProvider) {
        this.ingestionQueue = ingestionQueue;
        this.db = db;
        this.redisStream = redisStream;
        this.configuredProvider = (configuredProvider == null || configuredProvider.isBlank())
                ? "postgres-wal" : configuredProvider.trim();

        if (meterRegistry != null) {
            meterRegistry.gauge("elepay.broker.queue.depth", pendingDepthGauge);
            meterRegistry.gauge("elepay.broker.consumer.lag.ms", consumerLagMsGauge);
        }
    }

    @Override
    public String providerName() {
        if (redisStream != null) {
            return "redis-streams+" + configuredProvider;
        }
        return configuredProvider;
    }

    @Override
    public PublishConfirmation publishDurable(BrokerMessage message) {
        String provider = providerName();
        IngestionCommand cmd = new IngestionCommand(
                message.tenantId(),
                message.topic(),
                message.idempotencyScope(),
                message.idempotencyKey(),
                message.payloadJson(),
                message.orderingKey(),
                provider,
                message.messageId() != null ? message.messageId().toString() : null
        );
        IngestionReceipt receipt = ingestionQueue.enqueue(cmd);
        return new PublishConfirmation(
                receipt.ingestionId(),
                provider,
                receipt.status(),
                true,
                receipt.duplicate(),
                false, // Queue acceptance is NEVER treated as completed payment settlement
                Instant.now()
        );
    }

    @Override
    public List<PublishConfirmation> publishBatchDurable(List<BrokerMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        String provider = providerName();
        List<IngestionCommand> cmds = messages.stream()
                .map(m -> new IngestionCommand(
                        m.tenantId(),
                        m.topic(),
                        m.idempotencyScope(),
                        m.idempotencyKey(),
                        m.payloadJson(),
                        m.orderingKey(),
                        provider,
                        m.messageId() != null ? m.messageId().toString() : null))
                .toList();
        List<IngestionReceipt> receipts = ingestionQueue.enqueueBatch(cmds);
        Instant now = Instant.now();
        List<PublishConfirmation> confirmations = new ArrayList<>(receipts.size());
        for (IngestionReceipt r : receipts) {
            confirmations.add(new PublishConfirmation(
                    r.ingestionId(),
                    provider,
                    r.status(),
                    true,
                    r.duplicate(),
                    false,
                    now
            ));
        }
        return confirmations;
    }

    @Override
    @Transactional
    public void acknowledge(UUID messageId, String resultJson) {
        String safeJson = (resultJson == null || resultJson.isBlank()) ? "{\"acknowledged\":true}" : resultJson;
        db.sql("""
                UPDATE ingestion_event
                SET status = 'COMPLETED',
                    result = CAST(:res AS jsonb),
                    acked_at = now(),
                    completed_at = COALESCE(completed_at, now()),
                    updated_at = now(),
                    locked_by = NULL,
                    locked_at = NULL,
                    lease_expires_at = NULL,
                    last_error = NULL
                WHERE id = :id
                  AND status IN ('PENDING', 'PROCESSING')""")
                .param("res", safeJson)
                .param("id", messageId)
                .update();
    }

    @Override
    @Transactional
    public void negativeAcknowledge(UUID messageId, String reason, boolean poison) {
        String safeReason = (reason == null || reason.isBlank()) ? "nack" : reason;
        if (safeReason.length() > 1000) safeReason = safeReason.substring(0, 1000);
        if (poison) {
            db.sql("""
                    UPDATE ingestion_event
                    SET status = 'DEAD',
                        attempts = CASE WHEN status = 'PENDING' THEN attempts + 1 ELSE attempts END,
                        last_error = :err,
                        updated_at = now(),
                        locked_by = NULL,
                        locked_at = NULL,
                        lease_expires_at = NULL
                    WHERE id = :id
                      AND status IN ('PENDING', 'PROCESSING')""")
                    .param("err", "POISON_MESSAGE: " + safeReason)
                    .param("id", messageId)
                    .update();
            return;
        }
        db.sql("""
                UPDATE ingestion_event
                SET attempts = CASE WHEN status = 'PENDING' THEN attempts + 1 ELSE attempts END,
                    status = CASE
                        WHEN (CASE WHEN status = 'PENDING' THEN attempts + 1 ELSE attempts END) >= max_attempts
                        THEN 'DEAD'
                        ELSE 'PENDING'
                    END,
                    last_error = :err,
                    next_attempt_at = now() + interval '1 second',
                    updated_at = now(),
                    locked_by = NULL,
                    locked_at = NULL,
                    lease_expires_at = NULL
                WHERE id = :id
                  AND status IN ('PENDING', 'PROCESSING')""")
                .param("err", safeReason)
                .param("id", messageId)
                .update();
    }

    @Override
    public IngestionStatus replayDeadLetter(UUID messageId) {
        return ingestionQueue.replay(messageId);
    }

    @Override
    public ConsumerLagSnapshot inspectLag() {
        record Row(long pending, long processing, long dead, Instant oldestPending) {}
        Row row = db.sql("""
                SELECT
                    (SELECT count(*) FROM ingestion_event WHERE status = 'PENDING') AS pending,
                    (SELECT count(*) FROM ingestion_event WHERE status = 'PROCESSING') AS processing,
                    (SELECT count(*) FROM ingestion_event WHERE status = 'DEAD') AS dead,
                    (SELECT min(created_at) FROM ingestion_event WHERE status = 'PENDING') AS oldest_pending""")
                .query((rs, i) -> {
                    var ts = rs.getTimestamp("oldest_pending");
                    return new Row(
                            rs.getLong("pending"),
                            rs.getLong("processing"),
                            rs.getLong("dead"),
                            ts != null ? ts.toInstant() : null
                    );
                })
                .single();
        long lagMs = row.oldestPending() == null
                ? 0L
                : Math.max(0L, Duration.between(row.oldestPending(), Instant.now()).toMillis());
        pendingDepthGauge.set(row.pending());
        consumerLagMsGauge.set(lagMs);
        return new ConsumerLagSnapshot(
                providerName(),
                row.pending(),
                row.processing(),
                row.dead(),
                lagMs
        );
    }
}
