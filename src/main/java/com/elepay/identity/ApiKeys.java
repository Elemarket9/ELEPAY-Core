package com.elepay.identity;

import com.elepay.common.Idempotency;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merchant developer API keys. Only a SHA-256 hash of the secret is stored;
 * the plaintext is returned once at creation.
 */
@Service
public class ApiKeys {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcClient db;

    public ApiKeys(JdbcClient db) {
        this.db = db;
    }

    public record Created(UUID id, String prefix, String secret) {}
    public record Listed(UUID id, String prefix, String label, java.time.OffsetDateTime createdAt,
                         java.time.OffsetDateTime revokedAt) {}

    @Transactional
    public Created create(UUID merchantId, String label, String createdBy) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String prefix = "ek_" + HexFormat.of().formatHex(bytes, 0, 4);
        String secret = prefix + "_" + HexFormat.of().formatHex(bytes, 4, 24);
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO api_credential (id, prefix, secret_hash, merchant_id, label, created_by)
                VALUES (:id, :prefix, :hash, :merchant, :label, :by)""")
                .param("id", id).param("prefix", prefix).param("hash", Idempotency.sha256(secret))
                .param("merchant", merchantId).param("label", label).param("by", createdBy).update();
        return new Created(id, prefix, secret);
    }

    public List<Listed> list(UUID merchantId) {
        return db.sql("SELECT id, prefix, label, created_at, revoked_at FROM api_credential WHERE merchant_id = :m ORDER BY created_at")
                .param("m", merchantId)
                .query((rs, i) -> new Listed(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, java.time.OffsetDateTime.class), rs.getObject(5, java.time.OffsetDateTime.class)))
                .list();
    }

    @Transactional
    public boolean revoke(UUID merchantId, UUID id) {
        return db.sql("UPDATE api_credential SET revoked_at = now() WHERE id = :id AND merchant_id = :m AND revoked_at IS NULL")
                .param("id", id).param("m", merchantId).update() == 1;
    }

    Optional<ApiKeyAuthentication> authenticate(String secret) {
        if (secret == null || !secret.startsWith("ek_") || secret.length() > 100) return Optional.empty();
        return db.sql("SELECT id, merchant_id FROM api_credential WHERE secret_hash = :hash AND revoked_at IS NULL")
                .param("hash", Idempotency.sha256(secret))
                .query((rs, i) -> new ApiKeyAuthentication(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .optional();
    }
}
