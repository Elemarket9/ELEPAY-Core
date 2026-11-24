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
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public OpsQueue(JdbcClient db, ObjectMapper json) {
        this(db, json, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OpsQueue(
            JdbcClient db,
            ObjectMapper json,
            @org.springframework.beans.factory.annotation.Autowired(required = false) org.springframework.jdbc.core.JdbcTemplate jdbcTemplate
    ) {
        this.db = db;
        this.json = json;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Item(UUID id, String kind, String severity, String subject, String details, String status,
                       java.time.OffsetDateTime createdAt, String resolvedBy, String resolution) {}

    public record BatchEntry(String kind, String severity, String subject, String detailsJson) {}

    @Transactional
    public void raise(String kind, String severity, String subject, Map<String, ?> details) {
        String normalizedSeverity = "critical".equalsIgnoreCase(severity) ? "high" : severity;
        db.sql("""
                INSERT INTO ops_item (id, kind, severity, subject, details)
                VALUES (:id, :kind, :severity, :subject, CAST(:details AS jsonb))
                ON CONFLICT (kind, subject) WHERE status = 'open'
                DO UPDATE SET details = ops_item.details || EXCLUDED.details""")
                .param("id", UUID.randomUUID()).param("kind", kind).param("severity", normalizedSeverity)
                .param("subject", subject).param("details", json.writeValueAsString(details)).update();
    }

    @Transactional
    public void raiseBatch(List<BatchEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        if (jdbcTemplate == null) {
            for (BatchEntry e : entries) {
                String normalizedSeverity = "critical".equalsIgnoreCase(e.severity()) ? "high" : e.severity();
                db.sql("""
                        INSERT INTO ops_item (id, kind, severity, subject, details)
                        VALUES (:id, :kind, :severity, :subject, CAST(:details AS jsonb))
                        ON CONFLICT (kind, subject) WHERE status = 'open'
                        DO UPDATE SET details = ops_item.details || EXCLUDED.details""")
                        .param("id", UUID.randomUUID()).param("kind", e.kind()).param("severity", normalizedSeverity)
                        .param("subject", e.subject()).param("details", e.detailsJson()).update();
            }
            return;
        }
        int chunkSize = 50_000;
        for (int start = 0; start < entries.size(); start += chunkSize) {
            List<BatchEntry> chunk = entries.subList(start, Math.min(start + chunkSize, entries.size()));
            int n = chunk.size();
            UUID[] ids = new UUID[n];
            String[] kinds = new String[n];
            String[] severities = new String[n];
            String[] subjects = new String[n];
            String[] details = new String[n];
            for (int i = 0; i < n; i++) {
                BatchEntry e = chunk.get(i);
                ids[i] = UUID.randomUUID();
                kinds[i] = e.kind();
                severities[i] = "critical".equalsIgnoreCase(e.severity()) ? "high" : e.severity();
                subjects[i] = e.subject();
                details[i] = e.detailsJson();
            }
            jdbcTemplate.execute((java.sql.Connection conn) -> {
                try (var ps = conn.prepareStatement("""
                        INSERT INTO ops_item (id, kind, severity, subject, details)
                        SELECT u.id, u.kind, u.severity, u.subject, u.details
                        FROM unnest(?::uuid[], ?::text[], ?::text[], ?::text[], ?::jsonb[])
                             AS u(id, kind, severity, subject, details)
                        ON CONFLICT (kind, subject) WHERE status = 'open'
                        DO UPDATE SET details = ops_item.details || EXCLUDED.details""")) {
                    ps.setArray(1, conn.createArrayOf("uuid", ids));
                    ps.setArray(2, conn.createArrayOf("text", kinds));
                    ps.setArray(3, conn.createArrayOf("text", severities));
                    ps.setArray(4, conn.createArrayOf("text", subjects));
                    ps.setArray(5, conn.createArrayOf("jsonb", details));
                    ps.executeUpdate();
                }
                return null;
            });
        }
    }

    public List<Item> list(String status) {
        return db.sql("""
                SELECT id, kind, severity, subject, details::text, status, created_at, resolved_by, resolution
                FROM ops_item WHERE status = :status ORDER BY
                  CASE severity WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END, created_at
                LIMIT 200""")
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

    public java.util.Optional<Item> find(UUID id) {
        return db.sql("""
                SELECT id, kind, severity, subject, details::text, status, created_at, resolved_by, resolution
                FROM ops_item WHERE id = :id""")
                .param("id", id)
                .query((rs, i) -> new Item(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getObject(7, java.time.OffsetDateTime.class), rs.getString(8), rs.getString(9)))
                .optional();
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
