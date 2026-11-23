package com.elepay.emtech;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Transactional outbox for Emtech. The row is written in the caller's transaction.
 * The idempotency key makes a repeated business change a no-op, so a report is queued once.
 */
@Service
public class EmtechOutbox {
    private final JdbcClient db;
    private final ObjectMapper json;

    public EmtechOutbox(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record Row(UUID id, long sequence, String idempotencyKey, String resource, String sourceType, String sourceId,
                      String fact, String status, int attempts, String lastError, Integer lastStatus, String lastCode) {}

    @Transactional
    public boolean enqueue(String resource, String idempotencyKey, String sourceType, String sourceId, Object fact) {
        int inserted = db.sql("""
                INSERT INTO emtech_submission (id, idempotency_key, resource, source_type, source_id, fact)
                VALUES (:id, :key, :resource, :sourceType, :sourceId, CAST(:fact AS jsonb))
                ON CONFLICT (idempotency_key) DO NOTHING""")
                .param("id", UUID.randomUUID()).param("key", idempotencyKey).param("resource", resource)
                .param("sourceType", sourceType).param("sourceId", sourceId)
                .param("fact", json.writeValueAsString(fact)).update();
        return inserted == 1;
    }

    @Transactional
    public List<UUID> claim(int limit, java.time.Duration lease) {
        db.sql("""
                UPDATE emtech_submission SET status = 'PENDING', locked_at = NULL
                WHERE status = 'SENDING' AND locked_at < now() - make_interval(secs => :lease)""")
                .param("lease", Math.max(1, (int) lease.toSeconds())).update();
        return db.sql("""
                UPDATE emtech_submission SET status = 'SENDING', locked_at = now()
                WHERE id IN (
                    SELECT id FROM emtech_submission
                    WHERE status = 'PENDING' AND next_attempt_at <= now()
                    ORDER BY sequence
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
                RETURNING id""")
                .param("limit", limit)
                .query(UUID.class).list();
    }

    public Optional<Row> find(UUID id) {
        return db.sql("""
                SELECT id, sequence, idempotency_key, resource, source_type, source_id, fact::text, status,
                       attempts, last_error, last_status, last_code
                FROM emtech_submission WHERE id = :id""")
                .param("id", id).query(this::map).optional();
    }

    @Transactional
    public void markSubmitted(UUID id, int attempts) {
        db.sql("""
                UPDATE emtech_submission
                SET status = 'SUBMITTED', attempts = :attempts, submitted_at = now(), locked_at = NULL,
                    last_error = NULL, last_status = 200, last_code = NULL
                WHERE id = :id""")
                .param("id", id).param("attempts", attempts).update();
    }

    @Transactional
    public void markRejected(UUID id, int attempts, int httpStatus, String code, String message) {
        db.sql("""
                UPDATE emtech_submission
                SET status = 'DEAD', attempts = :attempts, locked_at = NULL,
                    last_status = :status, last_code = :code, last_error = :message
                WHERE id = :id""")
                .param("id", id).param("attempts", attempts).param("status", httpStatus)
                .param("code", code).param("message", clip(message)).update();
    }

    @Transactional
    public void markRetry(UUID id, int attempts, int httpStatus, String code, String message, long delayMs, int maxAttempts) {
        if (attempts >= maxAttempts) {
            markRejected(id, attempts, httpStatus, code == null ? "retry_exhausted" : code, message);
            return;
        }
        db.sql("""
                UPDATE emtech_submission
                SET status = 'PENDING', attempts = :attempts, locked_at = NULL,
                    next_attempt_at = now() + make_interval(secs => :delay),
                    last_status = :status, last_code = :code, last_error = :message
                WHERE id = :id""")
                .param("id", id).param("attempts", attempts)
                .param("delay", Math.max(0, delayMs / 1000.0))
                .param("status", httpStatus).param("code", code).param("message", clip(message)).update();
    }

    /** Ops replay. Only a dead letter is queued again, so an accepted report is not sent twice. */
    @Transactional
    public Row replay(UUID id) {
        Row row = find(id).orElseThrow(() -> com.elepay.common.ApiException.notFound("Submission not found"));
        if (!"DEAD".equals(row.status())) {
            throw com.elepay.common.ApiException.conflict("not_dead",
                    "Only a dead-lettered Emtech submission can be replayed");
        }
        db.sql("""
                UPDATE emtech_submission
                SET status = 'PENDING', attempts = 0, next_attempt_at = now(), locked_at = NULL,
                    last_error = NULL, last_status = NULL, last_code = NULL, submitted_at = NULL
                WHERE id = :id AND status = 'DEAD'""")
                .param("id", id).update();
        return find(id).orElseThrow();
    }

    public List<Row> list(String status, int limit) {
        String sql = """
                SELECT id, sequence, idempotency_key, resource, source_type, source_id, fact::text, status,
                       attempts, last_error, last_status, last_code
                FROM emtech_submission
                """ + (status == null || status.isBlank() ? "" : "WHERE status = :status ")
                + "ORDER BY sequence DESC LIMIT :limit";
        var query = db.sql(sql).param("limit", Math.min(Math.max(limit, 1), 500));
        if (status != null && !status.isBlank()) query.param("status", status);
        return query.query(this::map).list();
    }

    private Row map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Row(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9),
                rs.getString(10), (Integer) rs.getObject(11), rs.getString(12));
    }

    private static String clip(String message) {
        if (message == null) return null;
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    public OffsetDateTime now() {
        return OffsetDateTime.now();
    }
}
