package com.elepay.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Centralized Financial Idempotency Registry (Requirement 4).
 *
 * <p>Provides database-enforced, concurrency-safe, multi-instance financial idempotency across all
 * 11 financial domains (payment initiation, wallet transfers, multi-merchant split payments,
 * refunds, withdrawals, merchant settlements, escrow releases, provider callbacks, webhooks,
 * POS payments, and cross-cell financial operations).
 *
 * <ul>
 *   <li>Uses PostgreSQL {@code PRIMARY KEY (scope, key)} on hash-partitioned {@code idempotent_request}
 *       with canonical SHA-256 request fingerprinting.</li>
 *   <li>Never depends on Redis for financial idempotency.</li>
 *   <li>Protects transactions with unknown/ambiguous external payment outcomes
 *       ({@code status = 'AMBIGUOUS_OUTCOME'}) from blind re-execution.</li>
 *   <li>Prevents expired idempotency keys associated with a permanent {@code financial_reference}
 *       from being pruned or re-executed.</li>
 * </ul>
 */
@Component
public class Idempotency {
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public enum FinancialDomain {
        PAYMENT_INITIATION("payment-init", 365),
        WALLET_TRANSFER("wallet-transfer", 365),
        MULTI_MERCHANT_SPLIT("multi-merchant-split", 365),
        REFUND("refund", 365),
        WITHDRAWAL("withdrawal", 365),
        MERCHANT_SETTLEMENT("merchant-settlement", 730),
        ESCROW_RELEASE("escrow-release", 365),
        PROVIDER_CALLBACK("provider-callback", 180),
        WEBHOOK_DELIVERY("webhook-delivery", 90),
        POS_PAYMENT("pos-payment", 365),
        CROSS_CELL_OPERATION("cross-cell-op", 730);

        private final String prefix;
        private final int defaultRetentionDays;

        FinancialDomain(String prefix, int defaultRetentionDays) {
            this.prefix = prefix;
            this.defaultRetentionDays = defaultRetentionDays;
        }

        public String prefix() {
            return prefix;
        }

        public int defaultRetentionDays() {
            return defaultRetentionDays;
        }

        public String scopedKey(UUID tenantId, String financialIdentity) {
            String tenantPart = tenantId == null ? "global" : tenantId.toString();
            String idPart = (financialIdentity == null || financialIdentity.isBlank()) ? "default" : financialIdentity.trim();
            return prefix + ":" + tenantPart + ":" + idPart;
        }
    }

    public record RegistryRecord(
            String scope,
            String key,
            UUID tenantId,
            String operationType,
            String financialReference,
            String requestHash,
            String status,
            String responseJson,
            Instant createdAt,
            Instant expiresAt
    ) {}

    private final JdbcClient db;
    private final ObjectMapper json;
    private final int configuredRetentionDays;

    public Idempotency(JdbcClient db, ObjectMapper json) {
        this(db, json, 90);
    }

    @Autowired
    public Idempotency(JdbcClient db, ObjectMapper json,
                       @Value("${elepay.idempotency.retention-days:90}") int configuredRetentionDays) {
        this.db = db;
        this.json = json;
        this.configuredRetentionDays = Math.max(1, configuredRetentionDays);
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
        return executeFinancial(null, "API_REQUEST", scope, key, null, configuredRetentionDays, request, type, action);
    }

    /**
     * Domain-scoped financial idempotency execution with explicit tenant, operation type,
     * globally unique financial reference, and regulatory retention period.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T executeDomain(FinancialDomain domain, UUID tenantId, String financialIdentity,
                               String key, String financialReference, Object request,
                               Class<T> type, Supplier<T> action) {
        Objects.requireNonNull(domain, "domain must not be null");
        String scope = domain.scopedKey(tenantId, financialIdentity);
        int retentionDays = Math.max(configuredRetentionDays, domain.defaultRetentionDays());
        return executeFinancial(tenantId, domain.name(), scope, key, financialReference, retentionDays, request, type, action);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T executeFinancial(UUID tenantId, String operationType, String scope, String key,
                                  String financialReference, int retentionDays,
                                  Object request, Class<T> type, Supplier<T> action) {
        if (key == null || key.isBlank() || key.length() > 200) {
            throw ApiException.badRequest("idempotency_key_required", "An Idempotency-Key of 1 to 200 characters is required");
        }
        String hash = hash(request);
        String opType = (operationType == null || operationType.isBlank()) ? "API_REQUEST" : operationType;
        int effectiveRetention = Math.max(1, retentionDays);
        int claimed = db.sql("""
                INSERT INTO idempotent_request
                    (scope, key, tenant_id, operation_type, financial_reference, request_hash, status, expires_at)
                VALUES (:scope, :key, :tenantId, :opType, :finRef, :hash, 'IN_PROGRESS',
                        now() + make_interval(days => :days))
                ON CONFLICT (scope, key) DO NOTHING""")
                .param("scope", scope)
                .param("key", key)
                .param("tenantId", tenantId)
                .param("opType", opType)
                .param("finRef", financialReference)
                .param("hash", hash)
                .param("days", effectiveRetention)
                .update();
        if (claimed == 0) {
            record Stored(String hash, String response, String status, String finRef) {}
            Stored stored = db.sql("""
                    SELECT request_hash, response::text, status, financial_reference
                    FROM idempotent_request
                    WHERE scope = :scope AND key = :key""")
                    .param("scope", scope).param("key", key)
                    .query((rs, i) -> new Stored(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4))).single();
            if (!stored.hash().equals(hash)) {
                throw ApiException.idempotencyConflict();
            }
            if ("AMBIGUOUS_OUTCOME".equals(stored.status())) {
                throw ApiException.conflict("ambiguous_payment_outcome",
                        "External payment outcome is ambiguous/unknown for key '" + key
                                + "'; automatic re-execution is blocked until reconciliation resolves the outcome");
            }
            if (stored.response() == null) {
                throw ApiException.conflict("idempotency_in_progress",
                        "Operation with key '" + key + "' is currently in progress or awaits recovery");
            }
            return json.readValue(stored.response(), type);
        }
        T result = action.get();
        db.sql("""
                UPDATE idempotent_request
                SET response = CAST(:response AS jsonb),
                    status = 'COMPLETED',
                    financial_reference = COALESCE(:finRef, financial_reference)
                WHERE scope = :scope AND key = :key""")
                .param("response", json.writeValueAsString(result))
                .param("finRef", financialReference)
                .param("scope", scope)
                .param("key", key)
                .update();
        return result;
    }

    /**
     * Records an ambiguous external payment outcome so retries under the same key fail closed
     * with {@code 409 ambiguous_payment_outcome} instead of blindly re-submitting to the rail.
     */
    @Transactional
    public void markAmbiguousOutcome(String scope, String key, Object request, String financialReference) {
        String hash = hash(request);
        db.sql("""
                INSERT INTO idempotent_request
                    (scope, key, operation_type, financial_reference, request_hash, status, expires_at)
                VALUES (:scope, :key, 'EXTERNAL_PAYMENT', :finRef, :hash, 'AMBIGUOUS_OUTCOME',
                        now() + interval '365 days')
                ON CONFLICT (scope, key) DO UPDATE SET
                    status = 'AMBIGUOUS_OUTCOME',
                    financial_reference = COALESCE(EXCLUDED.financial_reference, idempotent_request.financial_reference)""")
                .param("scope", scope)
                .param("key", key)
                .param("finRef", financialReference)
                .param("hash", hash)
                .update();
    }

    /**
     * Resolves a previously ambiguous operation once reconciliation confirms the final response.
     */
    @Transactional
    public void resolveAmbiguousOutcome(String scope, String key, Object confirmedResponse) {
        db.sql("""
                UPDATE idempotent_request
                SET status = 'COMPLETED',
                    response = CAST(:response AS jsonb)
                WHERE scope = :scope AND key = :key""")
                .param("response", json.writeValueAsString(confirmedResponse))
                .param("scope", scope)
                .param("key", key)
                .update();
    }

    public Optional<RegistryRecord> inspect(String scope, String key) {
        return db.sql("""
                SELECT scope, key, tenant_id, operation_type, financial_reference,
                       request_hash, status, response::text, created_at, expires_at
                FROM idempotent_request
                WHERE scope = :scope AND key = :key""")
                .param("scope", scope).param("key", key)
                .query((rs, i) -> new RegistryRecord(
                        rs.getString("scope"),
                        rs.getString("key"),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("operation_type"),
                        rs.getString("financial_reference"),
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        rs.getString("response"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    public Optional<String> storedHash(String scope, String key) {
        return db.sql("SELECT request_hash FROM idempotent_request WHERE scope = :scope AND key = :key")
                .param("scope", scope).param("key", key).query(String.class).optional();
    }
}
