package com.elepay.security;

import com.elepay.common.ApiException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;

/**
 * Key rotation engine (Algorithm 076).
 * Supports re-encryption workflows that allow active master key rollover
 * while preserving decryption access to historical data.
 */
public class KeyRotationService {

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

    private final Map<String, KeyMetadata> keyStore = new ConcurrentHashMap<>();
    private volatile KeyMetadata activeKey;

    public KeyRotationService() {
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
            }
            activeKey = meta;
        }
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
