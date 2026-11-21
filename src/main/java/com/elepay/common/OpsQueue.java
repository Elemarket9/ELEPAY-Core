package com.elepay.common;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Operations queue for differences that need a human decision. One open item per (kind, subject). */
@Service
public class OpsQueue {
    private final JdbcClient db;
    private final ObjectMapper json;

    public OpsQueue(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record Item(UUID id, String kind, String severity, String subject, String details, String status,
                       java.time.OffsetDateTime createdAt, String resolvedBy, String resolution) {}

    @Transactional
    public void raise(String kind, String severity, String subject, Map<String, ?> details) {
        db.sql("""
                INSERT INTO ops_item (id, kind, severity, subject, details)
                VALUES (:id, :kind, :severity, :subject, CAST(:details AS jsonb))
                ON CONFLICT (kind, subject) WHERE status = 'open'
                DO UPDATE SET details = ops_item.details || EXCLUDED.details""")
                .param("id", UUID.randomUUID()).param("kind", kind).param("severity", severity)
                .param("subject", subject).param("details", json.writeValueAsString(details)).update();
    }

    public List<Item> list(String status) {
        return db.sql("""
                SELECT id, kind, severity, subject, details::text, status, created_at, resolved_by, resolution
                FROM ops_item WHERE status = :status ORDER BY
                  CASE severity WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END, created_at""")
                .param("status", status)
                .query((rs, i) -> new Item(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getObject(7, java.time.OffsetDateTime.class), rs.getString(8), rs.getString(9)))
                .list();
    }

    public long openCount(String kind, String subject) {
        return db.sql("SELECT count(*) FROM ops_item WHERE status = 'open' AND kind = :k AND subject = :s")
                .param("k", kind).param("s", subject).query(Long.class).single();
    }

    @Transactional
    public void resolve(UUID id, String actor, String resolution) {
        int updated = db.sql("""
                UPDATE ops_item SET status = 'resolved', resolved_by = :actor, resolved_at = now(), resolution = :r
                WHERE id = :id AND status = 'open'""")
                .param("id", id).param("actor", actor).param("r", resolution).update();
        if (updated == 0) throw ApiException.conflict("not_open", "Item is not open");
    }
}
