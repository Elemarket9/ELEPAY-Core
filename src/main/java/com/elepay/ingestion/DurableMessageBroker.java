package com.elepay.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Provider-Neutral Durable Message Broker Abstraction (Requirement 3).
 *
 * <p>Decouples asynchronous financial transaction ingestion from any single cloud or infrastructure
 * broker. Implementations (PostgreSQL WAL-durable group-commit queue, Redis Streams + PostgreSQL
 * durable store, or future Kafka/RabbitMQ adapters) guarantee:
 * <ul>
 *   <li>Publisher confirmation ONLY after durable persistence (never treating queue acceptance as
 *       completed payment settlement).</li>
 *   <li>Consumer acknowledgement ({@code ack}) and negative acknowledgement ({@code nack}) with
 *       poison-message isolation into a dead-letter queue (DLQ).</li>
 *   <li>Optional {@code orderingKey} preservation for transactions requiring strict sequential processing.</li>
 *   <li>Queue-depth and consumer-lag observability.</li>
 * </ul>
 */
public interface DurableMessageBroker {

    record BrokerMessage(
            UUID messageId,
            UUID tenantId,
            String topic,
            String idempotencyScope,
            String idempotencyKey,
            String orderingKey,
            String payloadJson,
            Map<String, String> headers
    ) {
        public BrokerMessage {
            Objects.requireNonNull(topic, "topic must not be null");
            Objects.requireNonNull(idempotencyScope, "idempotencyScope must not be null");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
            Objects.requireNonNull(payloadJson, "payloadJson must not be null");
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        public static BrokerMessage of(UUID tenantId, String topic, String scope, String key,
                                       String orderingKey, String payloadJson) {
            return new BrokerMessage(UUID.randomUUID(), tenantId, topic, scope, key, orderingKey, payloadJson, Map.of());
        }
    }

    /**
     * Publisher confirmation returned ONLY after the message is durably persisted to disk/WAL.
     * Note: {@code paymentCompleted} is always {@code false} at broker acceptance time.
     */
    record PublishConfirmation(
            UUID messageId,
            String provider,
            String status,
            boolean durableAccepted,
            boolean duplicate,
            boolean paymentCompleted,
            Instant confirmedAt
    ) {}

    record ConsumerLagSnapshot(
            String provider,
            long pendingDepth,
            long inFlightProcessing,
            long deadLetterCount,
            long oldestPendingLagMs
    ) {}

    /** Returns the active broker provider identifier (e.g. {@code "postgres-wal"}, {@code "redis-streams+postgres"}). */
    String providerName();

    /** Durably publishes a single financial message and returns publisher confirmation. */
    PublishConfirmation publishDurable(BrokerMessage message);

    /** Durably publishes a batch of financial messages in a single group commit. */
    List<PublishConfirmation> publishBatchDurable(List<BrokerMessage> messages);

    /** Records explicit consumer acknowledgement for a processed message. */
    void acknowledge(UUID messageId, String resultJson);

    /** Records negative acknowledgement; isolates into DLQ if retries are exhausted or {@code poison == true}. */
    void negativeAcknowledge(UUID messageId, String reason, boolean poison);

    /** Replays a dead-lettered message without duplicating financial effects (guarded by idempotency keys). */
    IngestionStatus replayDeadLetter(UUID messageId);

    /** Returns current queue depth, in-flight count, DLQ size, and oldest message consumer lag in milliseconds. */
    ConsumerLagSnapshot inspectLag();
}
