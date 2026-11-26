package com.elepay.outbox;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Production Transactional Outbox (Requirement 2).
 *
 * <p>Persists financial integration events inside the caller's mandatory database transaction,
 * supporting schema versioning, strict per-aggregate sequence ordering, dead-letter visibility,
 * and controlled replay.
 */
@Service
public class Outbox {
    public static final int DEFAULT_SCHEMA_VERSION = 1;

    private final JdbcClient db;
    private final ObjectMapper json;

    public Outbox(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record Event(UUID id, long sequence, String aggregateType, String aggregateId, String type,
                        int schemaVersion, String payload, UUID[] merchantIds, java.time.OffsetDateTime createdAt) {
        public Event(UUID id, long sequence, String aggregateType, String aggregateId, String type,
                     String payload, UUID[] merchantIds) {
            this(id, sequence, aggregateType, aggregateId, type, DEFAULT_SCHEMA_VERSION, payload, merchantIds, null);
        }
    }

    /** A merchant's events in delivery order after {@code after}, for integrations recovering missed webhooks. */
    public record MerchantEvent(UUID id, long sequence, String type, String aggregateId,
                                java.time.OffsetDateTime createdAt, String payload) {}

    public record DeadLetterEvent(UUID id, long sequence, String aggregateType, String aggregateId,
                                  String type, int schemaVersion, int attempts, String lastError,
                                  java.time.OffsetDateTime createdAt, java.time.OffsetDateTime deadAt) {}

    public List<MerchantEvent> eventsFor(UUID merchant, long after, int limit) {
        return db.sql("""
                SELECT id, sequence, event_type, aggregate_id, created_at, payload::text FROM outbox_event
                WHERE :m = ANY(merchant_ids) AND sequence > :after ORDER BY sequence LIMIT :limit""")
                .param("m", merchant).param("after", after).param("limit", Math.min(Math.max(limit, 1), 500))
                .query((rs, i) -> new MerchantEvent(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, java.time.OffsetDateTime.class), rs.getString(6))).list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String aggregateType, Object aggregateId, String type, Map<String, ?> payload,
                        Collection<UUID> merchants) {
        return publish(aggregateType, aggregateId, type, DEFAULT_SCHEMA_VERSION, payload, merchants);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String aggregateType, Object aggregateId, String type, int schemaVersion,
                        Map<String, ?> payload, Collection<UUID> merchants) {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1");
        }
        UUID id = UUID.randomUUID();
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("eventId", id.toString());
        body.put("type", type);
        body.put("schemaVersion", schemaVersion);
        body.put("data", payload);
        db.sql("""
                INSERT INTO outbox_event
                    (id, aggregate_type, aggregate_id, event_type, schema_version, status, payload, merchant_ids)
                VALUES (:id, :at, :aid, :type, :ver, 'PENDING', CAST(:payload AS jsonb), :merchants)""")
                .param("id", id)
                .param("at", aggregateType)
                .param("aid", String.valueOf(aggregateId))
                .param("type", type)
                .param("ver", schemaVersion)
                .param("payload", json.writeValueAsString(body))
                .param("merchants", merchants == null ? new UUID[0] : merchants.toArray(UUID[]::new))
                .update();
        return id;
    }

    public List<DeadLetterEvent> listDeadLetters(int limit) {
        int clamped = Math.min(Math.max(limit, 1), 500);
        return db.sql("""
                SELECT id, sequence, aggregate_type, aggregate_id, event_type, schema_version,
                       attempts, last_error, created_at, dead_at
                FROM outbox_event
                WHERE status = 'DEAD'
                ORDER BY dead_at DESC NULLS LAST, sequence DESC
                LIMIT :limit""")
                .param("limit", clamped)
                .query((rs, i) -> new DeadLetterEvent(
                        rs.getObject("id", UUID.class),
                        rs.getLong("sequence"),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getInt("schema_version"),
                        rs.getInt("attempts"),
                        rs.getString("last_error"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class),
                        rs.getObject("dead_at", java.time.OffsetDateTime.class)))
                .list();
    }

    @Transactional
    public boolean replayDeadLetter(UUID eventId) {
        int updated = db.sql("""
                UPDATE outbox_event
                SET status = 'PENDING',
                    attempts = 0,
                    dead_at = NULL,
                    last_error = NULL,
                    next_attempt_at = now()
                WHERE id = :id AND status = 'DEAD'""")
                .param("id", eventId)
                .update();
        return updated == 1;
    }
}
