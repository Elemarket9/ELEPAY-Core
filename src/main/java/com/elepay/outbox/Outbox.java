package com.elepay.outbox;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Writes integration events in the caller's transaction. */
@Service
public class Outbox {
    private final JdbcClient db;
    private final ObjectMapper json;

    public Outbox(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record Event(UUID id, long sequence, String aggregateType, String aggregateId, String type,
                        String payload, UUID[] merchantIds) {}

    /** A merchant's events in delivery order after {@code after}, for integrations recovering missed webhooks. */
    public record MerchantEvent(UUID id, long sequence, String type, String aggregateId,
                                java.time.OffsetDateTime createdAt, String payload) {}

    public java.util.List<MerchantEvent> eventsFor(UUID merchant, long after, int limit) {
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
        UUID id = UUID.randomUUID();
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("eventId", id.toString());
        body.put("type", type);
        body.put("data", payload);
        db.sql("""
                INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, payload, merchant_ids)
                VALUES (:id, :at, :aid, :type, CAST(:payload AS jsonb), :merchants)""")
                .param("id", id).param("at", aggregateType).param("aid", String.valueOf(aggregateId))
                .param("type", type).param("payload", json.writeValueAsString(body))
                .param("merchants", merchants.toArray(UUID[]::new))
                .update();
        return id;
    }
}
