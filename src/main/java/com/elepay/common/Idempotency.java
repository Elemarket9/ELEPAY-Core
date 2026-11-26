package com.elepay.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * API-level idempotency. The first request under a key claims it inside the
 * caller's transaction; concurrent duplicates block on the primary key until
 * the first commits, then receive the stored response. A different request
 * under the same key is rejected.
 */
@Component
public class Idempotency {
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final JdbcClient db;
    private final ObjectMapper json;

    public Idempotency(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public static String hash(Object request) {
        try {
            JsonNode tree = CANONICAL.valueToTree(request);
            byte[] bytes = CANONICAL.writeValueAsBytes(sort(tree));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            var sorted = CANONICAL.createObjectNode();
            node.propertyNames().stream().sorted().forEach(name -> sorted.set(name, sort(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            var array = CANONICAL.createArrayNode();
            node.forEach(child -> array.add(sort(child)));
            return array;
        }
        return node;
    }

    /**
     * Runs {@code action} once per (scope, key). Must be called inside the
     * transaction that performs the side effects so that claim and effects
     * commit or roll back together.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T execute(String scope, String key, Object request, Class<T> type, Supplier<T> action) {
        if (key == null || key.isBlank() || key.length() > 200) {
            throw ApiException.badRequest("idempotency_key_required", "An Idempotency-Key of 1 to 200 characters is required");
        }
        String hash = hash(request);
        int claimed = db.sql("""
                INSERT INTO idempotent_request (scope, key, request_hash) VALUES (:scope, :key, :hash)
                ON CONFLICT DO NOTHING""")
                .param("scope", scope).param("key", key).param("hash", hash).update();
        if (claimed == 0) {
            record Stored(String hash, String response) {}
            Stored stored = db.sql("""
                    SELECT r.request_hash, coalesce(r.response, a.response)::text
                    FROM idempotent_request r LEFT JOIN idempotency_result_archive a USING (scope, key)
                    WHERE r.scope = :scope AND r.key = :key""")
                    .param("scope", scope).param("key", key)
                    .query((rs, i) -> new Stored(rs.getString(1), rs.getString(2))).single();
            if (!stored.hash().equals(hash)) throw ApiException.idempotencyConflict();
            return json.readValue(stored.response(), type);
        }
        T result = action.get();
        db.sql("UPDATE idempotent_request SET response = CAST(:response AS jsonb) WHERE scope = :scope AND key = :key")
                .param("response", json.writeValueAsString(result)).param("scope", scope).param("key", key).update();
        return result;
    }

    public Optional<String> storedHash(String scope, String key) {
        return db.sql("SELECT request_hash FROM idempotent_request WHERE scope = :scope AND key = :key")
                .param("scope", scope).param("key", key).query(String.class).optional();
    }
}
