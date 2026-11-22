package com.elepay.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Append-only, tamper-evident hash-chained audit trail (Algorithm 080).
 *
 * <p>Each log entry cryptographically seals its predecessor:
 * {@code entry_hash = sha256(prev_hash + id + event_type + actor + payload + timestamp)}.
 */
@Component
public class Audit {
    public static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    private final JdbcClient db;
    private final ObjectMapper json;

    public Audit(JdbcClient db, ObjectMapper json) {
        this.db = Objects.requireNonNull(db);
        this.json = Objects.requireNonNull(json);
    }

    public record Entry(long id, String actor, String action, String subject, String payload,
                        OffsetDateTime createdAt, String prevHash, String entryHash) {}

    public record VerificationResult(boolean valid, long entriesVerified, String latestHash) {}

    public static class AuditTamperedException extends RuntimeException {
        public AuditTamperedException(String message) {
            super(message);
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String actor, String action, String subject, Map<String, ?> details) {
        recordEntry(actor, action, subject, details);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Entry recordEntry(String actor, String action, String subject, Map<String, ?> details) {
        long nextId = db.sql("SELECT nextval('audit_log_id_seq')").query(Long.class).single();

        String prevHash = db.sql("""
                SELECT entry_hash FROM audit_log
                WHERE entry_hash IS NOT NULL
                ORDER BY id DESC LIMIT 1 FOR UPDATE""")
                .query(String.class).optional().orElse(GENESIS_HASH);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        String rawJson = json.writeValueAsString(details != null ? details : Map.of());

        // Normalize payload through jsonb representation to guarantee exact string match on read
        String canonicalPayload = db.sql("SELECT CAST(:d AS jsonb)::text")
                .param("d", rawJson).query(String.class).single();

        String timestampStr = createdAt.toString();
        String entryHash = computeHash(prevHash, nextId, action, actor, canonicalPayload, timestampStr);

        db.sql("""
                INSERT INTO audit_log (id, actor, action, subject, details, created_at, prev_hash, entry_hash)
                OVERRIDING SYSTEM VALUE
                VALUES (:id, :actor, :action, :subject, CAST(:details AS jsonb), :created_at, :prev_hash, :entry_hash)
                """)
                .param("id", nextId)
                .param("actor", actor)
                .param("action", action)
                .param("subject", subject)
                .param("details", canonicalPayload)
                .param("created_at", createdAt)
                .param("prev_hash", prevHash)
                .param("entry_hash", entryHash)
                .update();

        return new Entry(nextId, actor, action, subject, canonicalPayload, createdAt, prevHash, entryHash);
    }

    /**
     * Iterates through the entire audit trail in sequence and validates cryptographic integrity of the hash chain.
     * Throws {@link AuditTamperedException} if any corruption, gap, or hash mismatch is detected.
     */
    @Transactional(readOnly = true)
    public VerificationResult verifyChain() {
        record Row(long id, String actor, String action, String subject, String details,
                   OffsetDateTime createdAt, String prevHash, String entryHash) {}

        List<Row> entries = db.sql("""
                SELECT id, actor, action, subject, details::text, created_at, prev_hash, entry_hash
                FROM audit_log
                ORDER BY id ASC""")
                .query((rs, i) -> new Row(
                        rs.getLong("id"),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("subject"),
                        rs.getString("details"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("prev_hash"),
                        rs.getString("entry_hash")
                )).list();

        String expectedPrevHash = GENESIS_HASH;
        long verified = 0;
        for (Row entry : entries) {
            if (entry.entryHash() == null) {
                throw new AuditTamperedException(String.format(
                        "Audit chain broken at entry id %d: null entry_hash detected", entry.id()));
            }

            if (verified > 0 && entry.prevHash() == null) {
                throw new AuditTamperedException(String.format(
                        "Audit chain broken at entry id %d: null prev_hash detected after genesis", entry.id()));
            }

            String actualPrevHash = entry.prevHash() != null ? entry.prevHash() : GENESIS_HASH;
            if (!MessageDigest.isEqual(actualPrevHash.getBytes(StandardCharsets.UTF_8),
                                      expectedPrevHash.getBytes(StandardCharsets.UTF_8))) {
                throw new AuditTamperedException(String.format(
                        "Audit chain broken at entry id %d: expected prev_hash %s but found %s",
                        entry.id(), expectedPrevHash, entry.prevHash()));
            }

            String timestampStr = entry.createdAt().toString();
            String computed = computeHash(entry.prevHash(), entry.id(), entry.action(),
                    entry.actor(), entry.details(), timestampStr);

            if (!MessageDigest.isEqual(entry.entryHash().getBytes(StandardCharsets.UTF_8),
                                      computed.getBytes(StandardCharsets.UTF_8))) {
                throw new AuditTamperedException(String.format(
                        "Audit entry tampered at id %d: expected hash %s but found %s",
                        entry.id(), computed, entry.entryHash()));
            }

            expectedPrevHash = entry.entryHash();
            verified++;
        }

        return new VerificationResult(true, verified, expectedPrevHash);
    }

    public static String computeHash(String prevHash, long id, String eventType, String actor, String payload, String timestamp) {
        String input = (prevHash != null ? prevHash : GENESIS_HASH) + id + eventType + actor + payload + timestamp;
        return Idempotency.sha256(input);
    }
}
