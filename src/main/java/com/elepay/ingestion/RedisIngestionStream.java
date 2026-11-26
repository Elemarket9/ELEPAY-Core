package com.elepay.ingestion;

import com.elepay.common.RedisBreaker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Optional Redis Streams fast-ingest front-end for {@link IngestionQueue}.
 *
 * <p>Disabled by default ({@code elepay.ingestion.redis.enabled=false}). When enabled,
 * guarded by {@link RedisBreaker} so any Redis outage trips the circuit breaker and falls
 * back transparently to the PostgreSQL {@link IngestionBatchWriter} without failing traffic.
 */
@Component
@ConditionalOnProperty(name = "elepay.ingestion.redis.enabled", havingValue = "true")
public class RedisIngestionStream {

    private static final Logger log = LoggerFactory.getLogger(RedisIngestionStream.class);

    private static final String FIELD_TENANT_ID = "tenantId";
    private static final String FIELD_EVENT_TYPE = "eventType";
    private static final String FIELD_SCOPE = "scope";
    private static final String FIELD_IDEMPOTENCY_KEY = "idempotencyKey";
    private static final String FIELD_PAYLOAD_JSON = "payloadJson";

    private final StringRedisTemplate redis;
    private final IngestionProperties properties;
    private final RedisBreaker breaker;
    private final String consumerName;
    private final AtomicBoolean groupInitialized = new AtomicBoolean(false);

    public RedisIngestionStream(
            @Autowired(required = false) StringRedisTemplate redis,
            IngestionProperties properties) {
        this.redis = redis;
        this.properties = properties;
        this.breaker = new RedisBreaker(properties.getRedis().getBreakerCooldownMs());
        String configuredConsumer = properties.getRedis().getConsumerName();
        this.consumerName = configuredConsumer.isBlank()
                ? "consumer-" + UUID.randomUUID().toString().substring(0, 8)
                : configuredConsumer;
    }

    /**
     * Returns {@code true} when a Redis template is present and the circuit breaker is closed.
     */
    public boolean isAvailable() {
        return redis != null && breaker.allow();
    }

    /**
     * Appends an ingestion command to the configured Redis Stream.
     * Returns {@link Optional#empty()} if Redis is unavailable or fails, signaling the caller
     * to fall back directly to PostgreSQL {@link IngestionQueue}.
     */
    public Optional<String> publish(IngestionCommand cmd) {
        if (cmd == null || !isAvailable()) {
            return Optional.empty();
        }
        try {
            IngestionProperties.Redis cfg = properties.getRedis();
            Map<String, String> fields = new LinkedHashMap<>(5);
            if (cmd.tenantId() != null) {
                fields.put(FIELD_TENANT_ID, cmd.tenantId().toString());
            }
            fields.put(FIELD_EVENT_TYPE, cmd.eventType());
            fields.put(FIELD_SCOPE, cmd.scope());
            fields.put(FIELD_IDEMPOTENCY_KEY, cmd.idempotencyKey());
            fields.put(FIELD_PAYLOAD_JSON, cmd.payloadJson());

            RecordId recordId = redis.opsForStream()
                    .add(StreamRecords.string(fields).withStreamKey(cfg.getStreamKey()));
            if (recordId == null) {
                return Optional.empty();
            }
            try {
                redis.opsForStream().trim(cfg.getStreamKey(), cfg.getMaxStreamLength(), true);
            } catch (Exception trimEx) {
                log.debug("Redis stream approximate trim skipped: {}", trimEx.getMessage());
            }
            return Optional.of(recordId.getValue());
        } catch (Exception e) {
            breaker.failure();
            log.warn("Redis ingestion stream publish failed; opening circuit breaker for {}ms: {}",
                    properties.getRedis().getBreakerCooldownMs(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Drains up to {@code elepay.ingestion.redis.read-batch-size} messages from the Redis Stream
     * into the durable PostgreSQL {@link IngestionQueue}, acknowledging stream entries only after
     * PostgreSQL commit succeeds.
     *
     * @return number of stream messages durably drained and acknowledged
     */
    public int drainToQueue(IngestionQueue queue) {
        if (queue == null || !isAvailable()) {
            return 0;
        }
        IngestionProperties.Redis cfg = properties.getRedis();
        try {
            ensureConsumerGroup(cfg.getStreamKey(), cfg.getConsumerGroup());
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    Consumer.from(cfg.getConsumerGroup(), consumerName),
                    StreamReadOptions.empty().count(cfg.getReadBatchSize()),
                    StreamOffset.create(cfg.getStreamKey(), ReadOffset.lastConsumed()));
            if (records == null || records.isEmpty()) {
                return 0;
            }
            List<IngestionCommand> commands = new ArrayList<>(records.size());
            List<RecordId> validIds = new ArrayList<>(records.size());
            for (MapRecord<String, Object, Object> rec : records) {
                Optional<IngestionCommand> parsed = parseRecord(rec);
                if (parsed.isPresent()) {
                    commands.add(parsed.get());
                    validIds.add(rec.getId());
                } else {
                    // Acknowledge malformed entries so they do not block the consumer group forever.
                    redis.opsForStream().acknowledge(cfg.getStreamKey(), cfg.getConsumerGroup(), rec.getId());
                }
            }
            if (commands.isEmpty()) {
                return 0;
            }
            queue.enqueueBatch(commands);
            redis.opsForStream().acknowledge(
                    cfg.getStreamKey(),
                    cfg.getConsumerGroup(),
                    validIds.toArray(RecordId[]::new));
            return validIds.size();
        } catch (Exception e) {
            breaker.failure();
            log.warn("Redis ingestion stream drain failed; opening circuit breaker for {}ms: {}",
                    properties.getRedis().getBreakerCooldownMs(), e.getMessage());
            return 0;
        }
    }

    private void ensureConsumerGroup(String streamKey, String consumerGroup) {
        if (groupInitialized.get()) {
            return;
        }
        try {
            redis.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), consumerGroup);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || !msg.contains("BUSYGROUP")) {
                // Stream may not exist yet; create group after verifying or ignore BUSYGROUP.
                log.debug("Redis consumer group init note for {}/{}: {}", streamKey, consumerGroup, msg);
            }
        }
        groupInitialized.set(true);
    }

    private static Optional<IngestionCommand> parseRecord(MapRecord<String, Object, Object> rec) {
        try {
            Map<Object, Object> raw = rec.getValue();
            String tenantRaw = raw.get(FIELD_TENANT_ID) != null ? raw.get(FIELD_TENANT_ID).toString() : null;
            UUID tenantId = (tenantRaw == null || tenantRaw.isBlank()) ? null : UUID.fromString(tenantRaw);
            String eventType = raw.get(FIELD_EVENT_TYPE) != null ? raw.get(FIELD_EVENT_TYPE).toString() : null;
            String scope = raw.get(FIELD_SCOPE) != null ? raw.get(FIELD_SCOPE).toString() : null;
            String idempotencyKey = raw.get(FIELD_IDEMPOTENCY_KEY) != null ? raw.get(FIELD_IDEMPOTENCY_KEY).toString() : null;
            String payloadJson = raw.get(FIELD_PAYLOAD_JSON) != null ? raw.get(FIELD_PAYLOAD_JSON).toString() : null;
            return Optional.of(new IngestionCommand(tenantId, eventType, scope, idempotencyKey, payloadJson));
        } catch (Exception e) {
            log.warn("Skipping malformed Redis ingestion stream record {}: {}", rec.getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
