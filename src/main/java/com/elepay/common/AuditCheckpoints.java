package com.elepay.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signed checkpoints over the audit chain head, using hybrid Ed25519 + ML-DSA-65 signatures. A checkpoint
 * commits to the last entry id, its hash and the number of entries; anyone holding the published public
 * keys can verify it, and the chain up to it can no longer be rewritten without breaking the signature.
 * Export checkpoints to write-once storage (object lock / WORM) to keep them out of an attacker's reach.
 */
@Component
public class AuditCheckpoints {
    private final JdbcClient db;
    private final IntegrityKeys keys;
    private final Audit audit;
    private final boolean scheduled;

    public AuditCheckpoints(JdbcClient db, IntegrityKeys keys, Audit audit,
                            @Value("${elepay.integrity.checkpoints-enabled:true}") boolean scheduled) {
        this.db = db;
        this.keys = keys;
        this.audit = audit;
        this.scheduled = scheduled;
    }

    public record Checkpoint(UUID id, long lastEntryId, String lastEntryHash, long entries, OffsetDateTime createdAt,
                             String keyId, String algorithm, String ed25519Signature, String mldsa65Signature) {}

    public record CheckpointCheck(UUID id, long lastEntryId, boolean signatureValid, boolean chainMatches) {}

    public record Report(boolean valid, long entriesVerified, String chainHead, String failure,
                         List<CheckpointCheck> checkpoints) {}

    @EventListener(ApplicationReadyEvent.class)
    void registerKeys() {
        keys.register(db);
    }

    public static byte[] message(long lastEntryId, String lastEntryHash, long entries, OffsetDateTime createdAt) {
        return ("elepay-audit-checkpoint:v1\n" + lastEntryId + "\n" + lastEntryHash + "\n" + entries + "\n"
                + createdAt.toInstant()).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Verifies a checkpoint's hybrid signature against provided public keys without requiring database access.
     */
    public static boolean verifyCheckpoint(IntegrityKeys.PublicKeys keys, Checkpoint checkpoint) {
        if (keys == null || checkpoint == null) return false;
        byte[] msg = message(checkpoint.lastEntryId(), checkpoint.lastEntryHash(), checkpoint.entries(), checkpoint.createdAt());
        return IntegrityKeys.verify(keys, msg, checkpoint.ed25519Signature(), checkpoint.mldsa65Signature());
    }

    /** Signs the current chain head. Returns empty when nothing new has been logged since the last checkpoint. */
    @Transactional
    public Optional<Checkpoint> create() {
        keys.register(db);
        record Head(long id, String hash, long count) {}
        var head = db.sql("""
                SELECT id, entry_hash, (SELECT count(*) FROM audit_log) FROM audit_log
                WHERE entry_hash IS NOT NULL ORDER BY id DESC LIMIT 1""")
                .query((rs, i) -> new Head(rs.getLong(1), rs.getString(2), rs.getLong(3))).optional();
        if (head.isEmpty()) return Optional.empty();
        long last = db.sql("SELECT coalesce(max(last_entry_id), 0) FROM audit_checkpoint").query(Long.class).single();
        if (last >= head.get().id()) return Optional.empty();
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        var sig = keys.sign(message(head.get().id(), head.get().hash(), head.get().count(), now));
        var cp = new Checkpoint(UUID.randomUUID(), head.get().id(), head.get().hash(), head.get().count(), now,
                sig.keyId(), sig.algorithm(), sig.ed25519(), sig.mldsa65());
        db.sql("""
                INSERT INTO audit_checkpoint (id, last_entry_id, last_entry_hash, entries, created_at, key_id, algorithm,
                    ed25519_signature, mldsa65_signature)
                VALUES (:id, :last, :hash, :n, :at, :key, :alg, :ed, :ml)""")
                .param("id", cp.id()).param("last", cp.lastEntryId()).param("hash", cp.lastEntryHash())
                .param("n", cp.entries()).param("at", cp.createdAt()).param("key", cp.keyId())
                .param("alg", cp.algorithm()).param("ed", cp.ed25519Signature()).param("ml", cp.mldsa65Signature())
                .update();
        return Optional.of(cp);
    }

    @Scheduled(fixedDelayString = "${elepay.integrity.checkpoint-ms:3600000}",
            initialDelayString = "${elepay.integrity.checkpoint-ms:3600000}")
    void scheduledCheckpoint() {
        if (scheduled) create();
    }

    /** Every signing key's public half, oldest first, for auditors. */
    public List<Map<String, Object>> publicKeyRecords() {
        return db.sql("""
                SELECT key_id, algorithm, ed25519_public, mldsa65_public, created_at FROM integrity_key
                ORDER BY created_at""").query().listOfRows();
    }

    /**
     * Verifies the whole chain and every checkpoint's hybrid signature against the recorded public keys. Not
     * wrapped in one transaction: a detected tampering must produce a report, not roll back the request.
     */
    public Report verify() {
        Audit.VerificationResult chain;
        try {
            chain = audit.verifyChain();
        } catch (Audit.AuditTamperedException e) {
            return new Report(false, 0, null, e.getMessage(), List.of());
        }
        Map<String, IntegrityKeys.PublicKeys> publicKeys = new HashMap<>();
        db.sql("SELECT key_id, algorithm, ed25519_public, mldsa65_public FROM integrity_key")
                .query((rs, i) -> new IntegrityKeys.PublicKeys(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4))).list().forEach(k -> publicKeys.put(k.keyId(), k));
        List<Checkpoint> all = db.sql("""
                SELECT id, last_entry_id, last_entry_hash, entries, created_at, key_id, algorithm, ed25519_signature,
                       mldsa65_signature FROM audit_checkpoint ORDER BY last_entry_id""")
                .query((rs, i) -> new Checkpoint(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3),
                        rs.getLong(4), rs.getObject(5, OffsetDateTime.class), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9))).list();
        List<CheckpointCheck> checks = new ArrayList<>();
        boolean ok = true;
        for (Checkpoint cp : all) {
            var k = publicKeys.get(cp.keyId());
            boolean sig = k != null && IntegrityKeys.verify(k,
                    message(cp.lastEntryId(), cp.lastEntryHash(), cp.entries(), cp.createdAt()),
                    cp.ed25519Signature(), cp.mldsa65Signature());
            String stored = db.sql("SELECT entry_hash FROM audit_log WHERE id = :id").param("id", cp.lastEntryId())
                    .query(String.class).optional().orElse(null);
            long count = db.sql("SELECT count(*) FROM audit_log WHERE id <= :id").param("id", cp.lastEntryId())
                    .query(Long.class).single();
            boolean matches = cp.lastEntryHash().equals(stored) && count == cp.entries();
            checks.add(new CheckpointCheck(cp.id(), cp.lastEntryId(), sig, matches));
            ok &= sig && matches;
        }
        return new Report(ok, chain.entriesVerified(), chain.latestHash(), ok ? null : "A checkpoint does not verify",
                checks);
    }
}
