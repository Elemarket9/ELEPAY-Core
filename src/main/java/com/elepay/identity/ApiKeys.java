package com.elepay.identity;

import com.elepay.common.Idempotency;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merchant developer API keys. Only a SHA-256 hash of the secret is stored;
 * the plaintext is returned once at creation along with its dynamic signing secret.
 */
@Service
public class ApiKeys {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcClient db;

    public ApiKeys(JdbcClient db) {
        this.db = db;
    }

    public record Created(UUID id, String prefix, String secret, List<String> scopes, String signingSecret) {
        public Created(UUID id, String prefix, String secret, List<String> scopes) {
            this(id, prefix, secret, scopes, null);
        }

        @com.fasterxml.jackson.annotation.JsonProperty("apiKey")
        public String apiKey() {
            return secret;
        }
    }

    public record Listed(UUID id, String prefix, String label, List<String> scopes,
                         java.time.OffsetDateTime createdAt, java.time.OffsetDateTime revokedAt) {}

    public static final java.util.Set<String> SCOPES = java.util.Set.of("view", "collect", "refund", "withdraw", "payouts");

    @Transactional
    public Created create(UUID merchantId, String label, List<String> scopes, String createdBy) {
        if (scopes == null || scopes.isEmpty()) scopes = List.of("view", "collect", "refund");
        if (!SCOPES.containsAll(scopes)) {
            throw com.elepay.common.ApiException.badRequest("invalid_scope", "Scopes: view, collect, refund, withdraw, payouts");
        }
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String prefix = "ek_" + HexFormat.of().formatHex(bytes, 0, 4);
        String secret = prefix + "_" + HexFormat.of().formatHex(bytes, 4, 24);

        // Dynamic Cryptographically Secure Signing Secret (32 bytes = 256 bits of entropy)
        byte[] signingBytes = new byte[32];
        RANDOM.nextBytes(signingBytes);
        String signingSecret = "sec_" + HexFormat.of().formatHex(signingBytes);

        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO api_credential (id, prefix, secret_hash, merchant_id, label, created_by, scopes, signing_secret)
                VALUES (:id, :prefix, :hash, :merchant, :label, :by, :scopes, :signing_secret)""")
                .param("id", id).param("prefix", prefix).param("hash", Idempotency.sha256(secret))
                .param("merchant", merchantId).param("label", label).param("by", createdBy)
                .param("scopes", scopes.stream().distinct().sorted().toArray(String[]::new))
                .param("signing_secret", signingSecret).update();
        return new Created(id, prefix, secret, scopes.stream().distinct().sorted().toList(), signingSecret);
    }

    // ---- developer portal: live and test keys ------------------------------------------------

    public record EnvironmentKey(UUID id, String prefix, String secret, String environment, List<String> scopes) {}

    public record EnvironmentListed(UUID id, String prefix, String environment, String label, List<String> scopes,
                                    java.time.OffsetDateTime createdAt, java.time.OffsetDateTime revokedAt,
                                    java.time.OffsetDateTime lastUsedAt) {}

    /** A key tagged with its environment ({@code ek_live_…} or {@code ek_test_…}). Scopes are already validated. */
    @Transactional
    public EnvironmentKey createForEnvironment(UUID merchantId, String label, String environment, List<String> scopes,
                                               String createdBy) {
        String env = "test".equalsIgnoreCase(environment) ? "test" : "live";
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String randomHex = HexFormat.of().formatHex(bytes);
        String prefix = "ek_" + env + "_" + randomHex.substring(0, 8);
        String secret = prefix + "_" + randomHex.substring(8);
        UUID id = UUID.randomUUID();
        List<String> sorted = scopes.stream().distinct().sorted().toList();
        db.sql("""
                INSERT INTO api_credential (id, prefix, secret_hash, merchant_id, label, created_by, scopes, environment)
                VALUES (:id, :prefix, :hash, :merchant, :label, :by, :scopes, :env)""")
                .param("id", id).param("prefix", prefix).param("hash", Idempotency.sha256(secret))
                .param("merchant", merchantId).param("label", label).param("by", createdBy)
                .param("scopes", sorted.toArray(String[]::new)).param("env", env).update();
        return new EnvironmentKey(id, prefix, secret, env, sorted);
    }

    public List<EnvironmentListed> listWithEnvironment(UUID merchantId, String environment) {
        boolean filter = environment != null && !environment.isBlank();
        var client = db.sql("""
                SELECT id, prefix, COALESCE(environment, 'live'), label, scopes, created_at, revoked_at, last_used_at
                FROM api_credential WHERE merchant_id = :m""" + (filter ? " AND environment = :env" : "")
                + " ORDER BY created_at DESC").param("m", merchantId);
        if (filter) client = client.param("env", environment.toLowerCase());
        return client.query((rs, i) -> new EnvironmentListed(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getString(4), List.of((String[]) rs.getArray(5).getArray()),
                rs.getObject(6, java.time.OffsetDateTime.class), rs.getObject(7, java.time.OffsetDateTime.class),
                rs.getObject(8, java.time.OffsetDateTime.class))).list();
    }

    /** Environment and scopes of an active key, for rotation. */
    public Optional<Map.Entry<String, List<String>>> activeKey(UUID merchantId, UUID id) {
        return db.sql("""
                SELECT COALESCE(environment, 'live'), scopes FROM api_credential
                WHERE id = :id AND merchant_id = :m AND revoked_at IS NULL""")
                .param("id", id).param("m", merchantId)
                .query((rs, i) -> Map.entry(rs.getString(1), List.of((String[]) rs.getArray(2).getArray())))
                .optional();
    }

    public List<Listed> list(UUID merchantId) {
        return db.sql("SELECT id, prefix, label, scopes, created_at, revoked_at FROM api_credential WHERE merchant_id = :m ORDER BY created_at")
                .param("m", merchantId)
                .query((rs, i) -> new Listed(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        List.of((String[]) rs.getArray(4).getArray()),
                        rs.getObject(5, java.time.OffsetDateTime.class), rs.getObject(6, java.time.OffsetDateTime.class)))
                .list();
    }

    @Transactional
    public boolean revoke(UUID merchantId, UUID id) {
        return db.sql("UPDATE api_credential SET revoked_at = now() WHERE id = :id AND merchant_id = :m AND revoked_at IS NULL")
                .param("id", id).param("m", merchantId).update() == 1;
    }

    public Optional<ApiKeyAuthentication> authenticate(String secret) {
        if (secret == null || !secret.startsWith("ek_") || secret.length() > 100) return Optional.empty();
        return db.sql("SELECT id, merchant_id, scopes, signing_secret FROM api_credential WHERE secret_hash = :hash AND revoked_at IS NULL")
                .param("hash", Idempotency.sha256(secret))
                .query((rs, i) -> new ApiKeyAuthentication(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        java.util.Set.of((String[]) rs.getArray(3).getArray()), rs.getString(4)))
                .optional();
    }

    public Optional<String> getSigningSecretForCredential(UUID credentialId) {
        if (credentialId == null) return Optional.empty();
        return db.sql("SELECT signing_secret FROM api_credential WHERE id = :id AND revoked_at IS NULL")
                .param("id", credentialId)
                .query((rs, i) -> rs.getString(1))
                .optional();
    }

    public Optional<String> getSigningSecretForApiKey(String apiKey) {
        if (apiKey == null || !apiKey.startsWith("ek_") || apiKey.length() > 100) return Optional.empty();
        return db.sql("SELECT signing_secret FROM api_credential WHERE secret_hash = :hash AND revoked_at IS NULL")
                .param("hash", Idempotency.sha256(apiKey))
                .query((rs, i) -> rs.getString(1))
                .optional();
    }
}
