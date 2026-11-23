package com.elepay.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Envelope-encryption secret provider with a pluggable key-management backend (Dead-End Gate 3).
 *
 * <p>Wraps {@link EnvelopeEncryption} (Algorithm 075) and {@link KeyRotationService} (Algorithm 076)
 * so sensitive signing secrets, vault keys, and partner credentials can be stored and rotated as
 * KMS-wrapped envelope ciphertexts ({@code ENC:v1:...}) rather than plaintext files on disk.
 *
 * <p><b>This class is NOT itself a cloud KMS.</b> The key-encryption-key (KEK) is obtained from a
 * {@link KeyManagementBackend}. The bundled {@link EnvVarKeyManagementBackend} reads a base64 32-byte
 * KEK from configuration ({@code elepay.vault.master-key}, typically injected from a secrets manager
 * via an environment variable). It is the local/env-var backend and makes no network calls.
 *
 * <p><b>Extension point for a real cloud KMS / HSM.</b> To delegate KEK custody to AWS KMS, GCP KMS,
 * or CloudHSM, implement {@link KeyManagementBackend#loadKek()} to call that provider's
 * {@code GenerateDataKey}/{@code Decrypt} (or equivalent PKCS#11) API and return the unwrapped KEK,
 * then construct {@code new KmsSecretProvider(yourBackend)} (or provide it as a Spring bean). No cloud
 * SDK is bundled here on purpose — wiring one is an infrastructure decision, not a code default.
 *
 * <p><b>Fail-closed.</b> There is no hardcoded fallback seed. In a production (non-test) context a
 * missing, blank, or invalid KEK makes construction throw {@link IllegalStateException} rather than
 * deriving a predictable key. Only an explicitly non-production profile (test/sandbox/dev) may fall
 * back to a per-process random ephemeral KEK when none is configured, so local runs still work without
 * ever producing a predictable or shared key.
 */
@Service
public class KmsSecretProvider {
    private static final List<String> FORBIDDEN_DISK_PREFIXES = List.of(
            "file:", "/etc/", "/tmp/", "/var/", "/home/", "/root/"
    );
    private static final List<String> FORBIDDEN_DISK_SUFFIXES = List.of(
            ".pem", ".key", ".p12", ".jks", ".pfx"
    );

    /**
     * Pluggable source of the AES-256 key-encryption-key (KEK). Implement this to front a real cloud
     * KMS or HSM; the default {@link EnvVarKeyManagementBackend} reads a base64 32-byte key from config.
     */
    public interface KeyManagementBackend {
        /** Returns the AES-256 KEK used to wrap per-record data keys. Must never return a predictable key. */
        SecretKey loadKek();

        /** Short human-readable backend name for logs/ops dashboards. Never returns key material. */
        String description();
    }

    /**
     * Default local/env-var backend: decodes a base64 32-byte KEK from configuration. This is the
     * replacement for a real KMS/HSM when secrets are injected as environment variables. It preserves
     * the historical behavior (base64-decoded 32-byte KEK) but fails closed instead of SHA-256'ing an
     * arbitrary string or using a hardcoded seed.
     */
    public static final class EnvVarKeyManagementBackend implements KeyManagementBackend {
        private final String masterKeyConfig;
        private final boolean allowEphemeralFallback;

        public EnvVarKeyManagementBackend(String masterKeyConfig, boolean allowEphemeralFallback) {
            this.masterKeyConfig = masterKeyConfig;
            this.allowEphemeralFallback = allowEphemeralFallback;
        }

        @Override
        public SecretKey loadKek() {
            if (masterKeyConfig != null && !masterKeyConfig.isBlank()) {
                byte[] decoded;
                try {
                    decoded = Base64.getDecoder().decode(masterKeyConfig.trim());
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException(
                            "elepay.vault.master-key must be base64 of 32 random bytes (openssl rand -base64 32); "
                                    + "it is not valid base64. Refusing to start with an unusable KEK.");
                }
                if (decoded.length != 32) {
                    throw new IllegalStateException(
                            "elepay.vault.master-key must decode to exactly 32 bytes for AES-256; got "
                                    + decoded.length + " bytes. Refusing to derive a predictable key.");
                }
                return new SecretKeySpec(decoded, "AES");
            }
            if (allowEphemeralFallback) {
                // Non-production only: a fresh per-process random key. Not predictable, not shared.
                byte[] ephemeral = new byte[32];
                new SecureRandom().nextBytes(ephemeral);
                return new SecretKeySpec(ephemeral, "AES");
            }
            throw new IllegalStateException(
                    "elepay.vault.master-key is absent/blank. A production KEK must be supplied via a secrets "
                            + "manager/KMS (base64 of 32 random bytes). Refusing to start fail-closed.");
        }

        @Override
        public String description() {
            return "env-var KEK (base64 32-byte; not a cloud KMS)";
        }
    }

    private final KeyManagementBackend backend;
    private final KeyRotationService keyRotationService;

    @Autowired
    public KmsSecretProvider(@Value("${elepay.vault.master-key:}") String masterKeyConfig,
                             Environment env,
                             @Autowired(required = false) JdbcClient db) {
        this(new EnvVarKeyManagementBackend(masterKeyConfig, isNonProductionContext(env)), db);
    }

    /** Constructs with an explicit key-management backend (e.g. a cloud KMS/HSM implementation). */
    public KmsSecretProvider(KeyManagementBackend backend) {
        this(backend, null);
    }

    /** Constructs with an explicit backend and an optional JDBC-backed rotation store. */
    public KmsSecretProvider(KeyManagementBackend backend, JdbcClient db) {
        this.backend = backend;
        this.keyRotationService = db != null ? new KeyRotationService(db) : new KeyRotationService();
        SecretKey initialKek = backend.loadKek();
        this.keyRotationService.registerKey("kms-master-kek", 1, initialKek, true);
    }

    /** True only for an explicit non-production profile (test/sandbox/dev), where an ephemeral KEK is allowed. */
    private static boolean isNonProductionContext(Environment env) {
        if (env == null) return false;
        for (String profile : env.getActiveProfiles()) {
            if (profile.equalsIgnoreCase("test") || profile.equalsIgnoreCase("sandbox")
                    || profile.equalsIgnoreCase("dev")) {
                return true;
            }
        }
        return false;
    }

    public KeyManagementBackend backend() {
        return backend;
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
}
