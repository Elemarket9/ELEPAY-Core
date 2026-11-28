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
 * <p>Each log entry seals its predecessor. Version 2 entries (current) use a keyed MAC over every field,
 * {@code entry_hash = HMAC-SHA-512(key, len-prefixed(prev_hash, id, action, actor, subject, payload, timestamp))},
 * so someone with database access but without the key cannot rewrite history and recompute the chain.
 * Version 1 entries (unkeyed SHA-256, subject not covered) written by earlier releases still verify.
 * {@link AuditCheckpoints} signs the chain head with Ed25519 and ML-DSA-65.
 */
@Component
public class Audit {
    public static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    public static final int HASH_VERSION = 2;

    private final JdbcClient db;
    private final ObjectMapper json;
    private final IntegrityKeys keys;

    public Audit(JdbcClient db, ObjectMapper json, IntegrityKeys keys) {
        this.db = Objects.requireNonNull(db);
        this.json = Objects.requireNonNull(json);
        this.keys = Objects.requireNonNull(keys);
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
        db.sql("SELECT pg_advisory_xact_lock(hashtext('elepay_audit_log_chain'))")
                .query((rs, i) -> (Object) null)
                .optional();

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
        String entryHash = keys.mac(canonicalV2(prevHash, nextId, action, actor, subject, canonicalPayload, timestampStr));

        db.sql("""
                INSERT INTO audit_log (id, actor, action, subject, details, created_at, prev_hash, entry_hash,
                    hash_version, hash_key_id)
                OVERRIDING SYSTEM VALUE
                VALUES (:id, :actor, :action, :subject, CAST(:details AS jsonb), :created_at, :prev_hash, :entry_hash,
                    :version, :key_id)
                """)
                .param("version", HASH_VERSION)
                .param("key_id", keys.macKeyId())
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

    private static final int VERIFY_BATCH_SIZE = 5_000;

    /**
     * Streams the entire audit trail in bounded keyset batches and validates cryptographic integrity
     * of the hash chain in constant memory. Prevents {@code version=1} SHA-256 downgrade once a
     * keyed {@code version=2} HMAC chain is active.
     * Throws {@link AuditTamperedException} if any corruption, gap, downgrade, or hash mismatch is detected.
     */
    @Transactional(readOnly = true)
    public VerificationResult verifyChain() {
        record Row(long id, String actor, String action, String subject, String details,
                   OffsetDateTime createdAt, String prevHash, String entryHash, int version, String keyId) {}

        String expectedPrevHash = GENESIS_HASH;
        long verified = 0;
        long lastId = Long.MIN_VALUE;
        boolean seenV2 = false;

        while (true) {
            List<Row> batch = db.sql("""
                    SELECT id, actor, action, subject, details::text, created_at, prev_hash, entry_hash,
                           hash_version, hash_key_id
                    FROM audit_log
                    WHERE id > :lastId
                    ORDER BY id ASC
                    LIMIT :limit""")
                    .param("lastId", lastId)
                    .param("limit", VERIFY_BATCH_SIZE)
                    .query((rs, i) -> new Row(
                            rs.getLong("id"),
                            rs.getString("actor"),
                            rs.getString("action"),
                            rs.getString("subject"),
                            rs.getString("details"),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getString("prev_hash"),
                            rs.getString("entry_hash"),
                            rs.getInt("hash_version"),
                            rs.getString("hash_key_id")
                    )).list();

            if (batch.isEmpty()) {
                break;
            }

            for (Row entry : batch) {
                if (entry.entryHash() == null) {
                    throw new AuditTamperedException(String.format(
                            "Audit chain broken at entry id %d: null entry_hash detected", entry.id()));
                }

                if (verified > 0 && entry.prevHash() == null) {
                    throw new AuditTamperedException(String.format(
                            "Audit chain broken at entry id %d: null prev_hash detected after genesis", entry.id()));
                }

                if (entry.version() < 1 || entry.version() > HASH_VERSION) {
                    throw new AuditTamperedException(String.format(
                            "Audit entry at id %d has unsupported hash_version %d", entry.id(), entry.version()));
                }

                if (seenV2 && entry.version() < 2) {
                    throw new AuditTamperedException(String.format(
                            "Audit hash version downgrade detected at id %d (version %d after v2 HMAC chain)",
                            entry.id(), entry.version()));
                }

                String actualPrevHash = entry.prevHash() != null ? entry.prevHash() : GENESIS_HASH;
                if (!MessageDigest.isEqual(actualPrevHash.getBytes(StandardCharsets.UTF_8),
                                          expectedPrevHash.getBytes(StandardCharsets.UTF_8))) {
                    throw new AuditTamperedException(String.format(
                            "Audit chain broken at entry id %d: expected prev_hash %s but found %s",
                            entry.id(), expectedPrevHash, entry.prevHash()));
                }

                String timestampStr = entry.createdAt().toString();
                boolean intact;
                if (entry.version() >= 2) {
                    seenV2 = true;
                    intact = keys.verifyMac(entry.keyId(), canonicalV2(entry.prevHash(), entry.id(), entry.action(),
                            entry.actor(), entry.subject(), entry.details(), timestampStr), entry.entryHash());
                } else {
                    String computed = computeHash(entry.prevHash(), entry.id(), entry.action(),
                            entry.actor(), entry.details(), timestampStr);
                    intact = MessageDigest.isEqual(entry.entryHash().getBytes(StandardCharsets.UTF_8),
                            computed.getBytes(StandardCharsets.UTF_8));
                }
                if (!intact) {
                    throw new AuditTamperedException(String.format(
                            "Audit entry tampered at id %d (hash version %d)", entry.id(), entry.version()));
                }

                expectedPrevHash = entry.entryHash();
                lastId = entry.id();
                verified++;
            }

            if (batch.size() < VERIFY_BATCH_SIZE) {
                break;
            }
        }

        return new VerificationResult(true, verified, expectedPrevHash);
    }

    /** Unambiguous encoding: each field is prefixed with its length, so no two field sets collide. */
    static String canonicalV2(String prevHash, long id, String action, String actor, String subject, String payload,
                              String timestamp) {
        StringBuilder b = new StringBuilder("elepay-audit:v2\n");
        for (String f : new String[]{prevHash != null ? prevHash : GENESIS_HASH, Long.toString(id), action, actor,
                subject, payload, timestamp}) {
            String v = f == null ? "" : f;
            b.append(v.length()).append(':').append(v).append('\n');
        }
        return b.toString();
    }

    /** Version 1 (legacy, unkeyed) entry hash. */
    public static String computeHash(String prevHash, long id, String eventType, String actor, String payload, String timestamp) {
        String input = (prevHash != null ? prevHash : GENESIS_HASH) + id + eventType + actor + payload + timestamp;
        return Idempotency.sha256(input);
    }
}
