package com.elepay.security;

import com.elepay.common.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Key rotation engine (Algorithm 076).
 * Supports re-encryption workflows that allow active master key rollover
 * while preserving decryption access to historical data.
 *
 * <p>Key <b>metadata</b> (id, version, status, activation/rotation timestamps) is persisted to the
 * {@code key_rotation_metadata} table (migration {@code V55__key_rotation_persistence.sql}) when a
 * {@link JdbcClient} is supplied, so rotation history and "rotation due" decisions survive restarts.
 * Secret key <b>material</b> is NEVER written to the database: it is supplied at startup from the
 * {@link KmsSecretProvider.KeyManagementBackend} (KMS/HSM/env) and held only in memory.
 *
 * <p>Scheduling: {@link #rotateDue()} is a pure, side-effect-light check a scheduler should invoke
 * (e.g. a {@code @Scheduled} bean in a config class this module cannot edit). It reports whether the
 * active KEK is past its rotation interval; minting the new KEK remains a KMS/operator action that
 * calls {@link #rotateKey(String, int, SecretKey)}.
 */
public class KeyRotationService {

    /** Default rotation interval for the active KEK when none is persisted. */
    public static final Duration DEFAULT_ROTATION_INTERVAL = Duration.ofDays(90);

    public record KeyMetadata(
            String keyId,
            int version,
            SecretKey secretKey,
            KeyStatus status
    ) {}

    public enum KeyStatus {
        ACTIVE,
        RETIRED,
        REVOKED
    }

    /** Result of a {@link #rotateDue()} check for a scheduler to act on. */
    public record RotationCheck(
            boolean dueForRotation,
            String activeKeyId,
            int activeVersion,
            Instant activatedAt,
            Instant rotationDueAt
    ) {}

    private final Map<String, KeyMetadata> keyStore = new ConcurrentHashMap<>();
    private volatile KeyMetadata activeKey;
    private volatile Instant activeActivatedAt;
    private final JdbcClient db;
    private final Duration rotationInterval;

    public KeyRotationService() {
        this(null, DEFAULT_ROTATION_INTERVAL);
    }

    /** Persists rotation metadata via {@code db} (may be null for a pure in-memory instance). */
    public KeyRotationService(JdbcClient db) {
        this(db, DEFAULT_ROTATION_INTERVAL);
    }

    public KeyRotationService(JdbcClient db, Duration rotationInterval) {
        this.db = db;
        this.rotationInterval = rotationInterval != null ? rotationInterval : DEFAULT_ROTATION_INTERVAL;
    }

    public synchronized void registerKey(String keyId, int version, SecretKey key, boolean makeActive) {
        KeyMetadata meta = new KeyMetadata(keyId, version, key, makeActive ? KeyStatus.ACTIVE : KeyStatus.RETIRED);
        keyStore.put(keyId, meta);
        keyStore.put(keyId + ":v" + version, meta);
        if (makeActive) {
            if (activeKey != null) {
                // Retire current active key
                keyStore.put(activeKey.keyId(), new KeyMetadata(activeKey.keyId(), activeKey.version(), activeKey.secretKey(), KeyStatus.RETIRED));
                keyStore.put(activeKey.keyId() + ":v" + activeKey.version(), new KeyMetadata(activeKey.keyId(), activeKey.version(), activeKey.secretKey(), KeyStatus.RETIRED));
                persistStatus(activeKey.keyId(), activeKey.version(), KeyStatus.RETIRED);
            }
            activeKey = meta;
            activeActivatedAt = Instant.now();
        }
        persistMetadata(meta, makeActive);
    }

    /**
     * Persists key metadata (never the key material). Failures are swallowed so an unavailable metadata
     * store never blocks a security-critical key registration/rotation held in memory.
     */
    private void persistMetadata(KeyMetadata meta, boolean makeActive) {
        if (db == null) return;
        try {
            Instant now = Instant.now();
            Instant dueAt = makeActive ? now.plus(rotationInterval) : null;
            db.sql("""
                    INSERT INTO key_rotation_metadata
                        (key_id, key_version, status, created_at, activated_at, retired_at, rotation_due_at)
                    VALUES (?, ?, ?, now(), ?, ?, ?)
                    ON CONFLICT (key_id, key_version) DO UPDATE SET
                        status = EXCLUDED.status,
                        activated_at = COALESCE(EXCLUDED.activated_at, key_rotation_metadata.activated_at),
                        retired_at = EXCLUDED.retired_at,
                        rotation_due_at = COALESCE(EXCLUDED.rotation_due_at, key_rotation_metadata.rotation_due_at)
                    """)
                    .param(meta.keyId())
                    .param(meta.version())
                    .param(meta.status().name())
                    .param(makeActive ? java.sql.Timestamp.from(now) : null)
                    .param(makeActive ? null : java.sql.Timestamp.from(now))
                    .param(dueAt != null ? java.sql.Timestamp.from(dueAt) : null)
                    .update();
        } catch (Exception e) {
            // Metadata persistence is best-effort; the authoritative key state is the in-memory + KMS source.
        }
    }

    private void persistStatus(String keyId, int version, KeyStatus status) {
        if (db == null) return;
        try {
            db.sql("UPDATE key_rotation_metadata SET status = ?, retired_at = now() WHERE key_id = ? AND key_version = ?")
                    .param(status.name()).param(keyId).param(version).update();
        } catch (Exception e) {
            // best-effort
        }
    }

    /**
     * Reports whether the active KEK is past its rotation interval. A {@code @Scheduled} trigger should
     * call this periodically; when {@link RotationCheck#dueForRotation()} is true, operations/KMS must
     * mint a new KEK and call {@link #rotateKey(String, int, SecretKey)}. Prefers persisted timestamps
     * (surviving restarts) and falls back to the in-memory activation time.
     */
    public RotationCheck rotateDue() {
        KeyMetadata current = activeKey;
        if (current == null) {
            return new RotationCheck(false, null, 0, null, null);
        }
        Instant activatedAt = activeActivatedAt;
        Instant dueAt = null;
        if (db != null) {
            try {
                var row = db.sql("SELECT activated_at, rotation_due_at FROM key_rotation_metadata WHERE key_id = ? AND key_version = ?")
                        .param(current.keyId()).param(current.version())
                        .query((rs, n) -> new Instant[]{
                                rs.getTimestamp("activated_at") != null ? rs.getTimestamp("activated_at").toInstant() : null,
                                rs.getTimestamp("rotation_due_at") != null ? rs.getTimestamp("rotation_due_at").toInstant() : null
                        })
                        .optional();
                if (row.isPresent()) {
                    if (row.get()[0] != null) activatedAt = row.get()[0];
                    dueAt = row.get()[1];
                }
            } catch (Exception e) {
                // fall back to in-memory timestamps
            }
        }
        if (dueAt == null && activatedAt != null) {
            dueAt = activatedAt.plus(rotationInterval);
        }
        boolean due = dueAt != null && !Instant.now().isBefore(dueAt);
        return new RotationCheck(due, current.keyId(), current.version(), activatedAt, dueAt);
    }

    public KeyMetadata getActiveKey() {
        if (activeKey == null) {
            throw ApiException.badRequest("no_active_key", "No active KMS key configured");
        }
        return activeKey;
    }

    public KeyMetadata getKey(String keyId, int version) {
        KeyMetadata meta = keyStore.get(keyId + ":v" + version);
        if (meta == null) {
            meta = keyStore.get(keyId);
        }
        if (meta == null) {
            throw ApiException.notFound("KMS key not found: " + keyId + " version " + version);
        }
        if (meta.status() == KeyStatus.REVOKED) {
            throw ApiException.forbidden("KMS key has been revoked: " + keyId);
        }
        return meta;
    }

    public synchronized void rotateKey(String newKeyId, int newVersion, SecretKey newKey) {
        registerKey(newKeyId, newVersion, newKey, true);
    }

    public EnvelopeEncryption.EncryptedField reEncrypt(EnvelopeEncryption.EncryptedField oldField) {
        if (oldField == null) {
            return null;
        }
        KeyMetadata oldKeyMeta = getKey(oldField.keyId(), oldField.keyVersion());
        // Decrypt using historical key
        String plaintext = EnvelopeEncryption.decrypt(oldField, oldKeyMeta.secretKey());

        // Re-encrypt under active key
        KeyMetadata currentActive = getActiveKey();
        return EnvelopeEncryption.encrypt(
                plaintext,
                oldField.aad(),
                currentActive.keyId(),
                currentActive.version(),
                currentActive.secretKey()
        );
    }

    public BatchReEncryptionResult batchReEncrypt(java.util.List<EnvelopeEncryption.EncryptedField> fields) {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        java.util.List<EnvelopeEncryption.EncryptedField> updated = new java.util.ArrayList<>();

        for (EnvelopeEncryption.EncryptedField field : fields) {
            try {
                updated.add(reEncrypt(field));
                successCount.incrementAndGet();
            } catch (Exception e) {
                failureCount.incrementAndGet();
                updated.add(field); // Keep old if failed
            }
        }
        return new BatchReEncryptionResult(successCount.get(), failureCount.get(), updated);
    }

    public record BatchReEncryptionResult(
            int migratedCount,
            int failedCount,
            java.util.List<EnvelopeEncryption.EncryptedField> results
    ) {}
}
