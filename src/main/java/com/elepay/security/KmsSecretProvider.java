package com.elepay.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Cloud KMS / HSM Secret Provider Abstraction (Dead-End Gate 3).
 *
 * <p>Wraps {@link EnvelopeEncryption} (Algorithm 075) and {@link KeyRotationService} (Algorithm 076)
 * so sensitive signing secrets, vault keys, and partner credentials can be stored and rotated as
 * KMS-wrapped envelope ciphertexts ({@code ENC:v1:...}) rather than plaintext files on disk.
 */
@Service
public class KmsSecretProvider {
    private static final List<String> FORBIDDEN_DISK_PREFIXES = List.of(
            "file:", "/etc/", "/tmp/", "/var/", "/home/", "/root/"
    );
    private static final List<String> FORBIDDEN_DISK_SUFFIXES = List.of(
            ".pem", ".key", ".p12", ".jks", ".pfx"
    );

    private final KeyRotationService keyRotationService;

    public KmsSecretProvider(@Value("${elepay.vault.master-key:}") String masterKeyConfig) {
        this.keyRotationService = new KeyRotationService();
        SecretKey initialKek = deriveKek(masterKeyConfig);
        this.keyRotationService.registerKey("kms-master-kek", 1, initialKek, true);
    }

    public KeyRotationService keyRotationService() {
        return keyRotationService;
    }

    /**
     * Returns true if {@code value} looks like a local disk file path or key file reference,
     * which is forbidden in production so compromised app servers never hold secret files on disk.
     */
    public static boolean isDiskFileSecret(String value) {
        if (value == null || value.isBlank()) return false;
        String trimmed = value.trim().toLowerCase();
        for (String prefix : FORBIDDEN_DISK_PREFIXES) {
            if (trimmed.startsWith(prefix)) return true;
        }
        for (String suffix : FORBIDDEN_DISK_SUFFIXES) {
            if (trimmed.endsWith(suffix)) return true;
        }
        return false;
    }

    public String wrapSecret(String plaintext, String aad) {
        if (plaintext == null) return null;
        if (plaintext.startsWith("ENC:v1:")) return plaintext;
        var active = keyRotationService.getActiveKey();
        return EnvelopeEncryption.encrypt(plaintext, aad, active.keyId(), active.version(), active.secretKey())
                .serialize();
    }

    public String unwrapSecret(String configuredOrStoredValue, String expectedAad) {
        if (configuredOrStoredValue == null) return null;
        if (isDiskFileSecret(configuredOrStoredValue)) {
            throw new IllegalArgumentException("Disk secret file paths are forbidden by KmsSecretProvider: " + configuredOrStoredValue);
        }
        if (!configuredOrStoredValue.startsWith("ENC:v1:")) {
            return configuredOrStoredValue;
        }
        EnvelopeEncryption.EncryptedField field = EnvelopeEncryption.EncryptedField.deserialize(configuredOrStoredValue);
        var keyMeta = keyRotationService.getKey(field.keyId(), field.keyVersion());
        return EnvelopeEncryption.decrypt(field, keyMeta.secretKey());
    }

    public void rotateMasterKey(String newKeyId, int newVersion, SecretKey newKek) {
        keyRotationService.rotateKey(newKeyId, newVersion, newKek);
    }

    private static SecretKey deriveKek(String rawKey) {
        try {
            if (rawKey != null && !rawKey.isBlank()) {
                try {
                    byte[] decoded = Base64.getDecoder().decode(rawKey.trim());
                    if (decoded.length == 32) {
                        return new SecretKeySpec(decoded, "AES");
                    }
                } catch (IllegalArgumentException ignored) {}
                byte[] sha = MessageDigest.getInstance("SHA-256").digest(rawKey.trim().getBytes(StandardCharsets.UTF_8));
                return new SecretKeySpec(sha, "AES");
            }
            byte[] fallback = MessageDigest.getInstance("SHA-256")
                    .digest("elepay-ephemeral-kms-kek-seed".getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(fallback, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize KMS KEK", e);
        }
    }
}
