package com.elepay.identity;

import com.elepay.common.Idempotency;
import com.elepay.common.RedisBreaker;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merchant developer API keys. Only a SHA-256 hash of the secret is stored;
 * the plaintext is returned once at creation along with its dynamic signing secret.
 */
@Service
public class ApiKeys {
    public static final String REDIS_REVOKED_PREFIX = "elepay:apikey:revoked:";
    public static final String REDIS_REVOKED_HASH_PREFIX = "elepay:apikey:revoked_hash:";

    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcClient db;
    private final StringRedisTemplate redis;
    private final RedisBreaker redisBreaker;
    private final Set<UUID> revokedKeyIds = ConcurrentHashMap.newKeySet();
    private final Set<String> revokedKeyHashes = ConcurrentHashMap.newKeySet();

    @org.springframework.beans.factory.annotation.Value("${elepay.rails.sandbox.enabled:false}")
    private boolean testEnvironment;

    public ApiKeys(JdbcClient db) {
        this(db, null);
    }

    @Autowired
    public ApiKeys(JdbcClient db,
                   @Autowired(required = false) StringRedisTemplate redis) {
        this.db = db;
        this.redis = redis;
        this.redisBreaker = new RedisBreaker(5_000L);
    }

    public record Created(UUID id, String prefix, String secret, List<String> scopes, String signingSecret,
                          String keyType, List<String> allowedIps) {
        public Created(UUID id, String prefix, String secret, List<String> scopes) {
            this(id, prefix, secret, scopes, null, "secret", List.of());
        }

        public Created(UUID id, String prefix, String secret, List<String> scopes, String signingSecret) {
            this(id, prefix, secret, scopes, signingSecret, "secret", List.of());
        }

        @com.fasterxml.jackson.annotation.JsonProperty("apiKey")
        public String apiKey() {
            return secret;
        }
    }

    public record Listed(UUID id, String prefix, String label, List<String> scopes,
                         java.time.OffsetDateTime createdAt, java.time.OffsetDateTime revokedAt,
                         String keyType, List<String> allowedIps) {
        public Listed(UUID id, String prefix, String label, List<String> scopes,
                      java.time.OffsetDateTime createdAt, java.time.OffsetDateTime revokedAt) {
            this(id, prefix, label, scopes, createdAt, revokedAt, "secret", List.of());
        }
    }

    public static final java.util.Set<String> SCOPES = java.util.Set.of("view", "collect", "refund", "withdraw", "payouts");
    public static final java.util.Set<String> KEY_TYPES = java.util.Set.of("publishable", "secret", "payout");

    @Transactional
    public Created create(UUID merchantId, String label, List<String> scopes, String createdBy) {
        return create(merchantId, label, scopes, null, null, createdBy);
    }

    @Transactional
    public Created create(UUID merchantId, String label, List<String> scopes, String keyType, List<String> allowedIps,
                          String createdBy) {
        String normalizedType = normalizeKeyType(keyType);
        List<String> resolvedScopes = resolveScopesForType(normalizedType, scopes);
        List<String> normalizedIps = validateAndNormalizeIps(allowedIps);

        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String prefix;
        if (keyType == null || keyType.isBlank()) {
            // Backward compatibility: legacy create calls without keyType get ek_ prefix
            prefix = "ek_" + HexFormat.of().formatHex(bytes, 0, 4);
        } else {
            String typePrefix = switch (normalizedType) {
                case "publishable" -> "pk_live_";
                case "payout" -> "payout_live_";
                default -> "sk_live_";
            };
            prefix = typePrefix + HexFormat.of().formatHex(bytes, 0, 4);
        }
        String secret = prefix + "_" + HexFormat.of().formatHex(bytes, 4, 24);

        // Dynamic Cryptographically Secure Signing Secret (32 bytes = 256 bits of entropy)
        byte[] signingBytes = new byte[32];
        RANDOM.nextBytes(signingBytes);
        String signingSecret = "sec_" + HexFormat.of().formatHex(signingBytes);

        UUID id = UUID.randomUUID();
        List<String> sortedScopes = resolvedScopes.stream().distinct().sorted().toList();
        db.sql("""
                INSERT INTO api_credential (id, prefix, secret_hash, merchant_id, label, created_by, scopes,
                    signing_secret, key_type, allowed_ips)
                VALUES (:id, :prefix, :hash, :merchant, :label, :by, :scopes, :signing_secret, :key_type, :allowed_ips)""")
                .param("id", id).param("prefix", prefix).param("hash", Idempotency.sha256(secret))
                .param("merchant", merchantId).param("label", label).param("by", createdBy)
                .param("scopes", sortedScopes.toArray(String[]::new))
                .param("signing_secret", signingSecret)
                .param("key_type", normalizedType)
                .param("allowed_ips", normalizedIps.toArray(String[]::new))
                .update();
        return new Created(id, prefix, secret, sortedScopes, signingSecret, normalizedType, normalizedIps);
    }

    // ---- developer portal: live and test keys ------------------------------------------------

    public record EnvironmentKey(UUID id, String prefix, String secret, String environment, List<String> scopes,
                                 String signingSecret, String keyType, List<String> allowedIps) {
        public EnvironmentKey(UUID id, String prefix, String secret, String environment, List<String> scopes, String signingSecret) {
            this(id, prefix, secret, environment, scopes, signingSecret, "secret", List.of());
        }
    }

    public record EnvironmentListed(UUID id, String prefix, String environment, String label, List<String> scopes,
                                    java.time.OffsetDateTime createdAt, java.time.OffsetDateTime revokedAt,
                                    java.time.OffsetDateTime lastUsedAt) {}

    /** A key tagged with its environment ({@code ek_live_…}, {@code pk_live_…}, {@code sk_live_…}, or {@code payout_live_…}). */
    @Transactional
    public EnvironmentKey createForEnvironment(UUID merchantId, String label, String environment, List<String> scopes,
                                               String createdBy) {
        return createForEnvironment(merchantId, label, environment, scopes, null, null, createdBy);
    }

    @Transactional
    public EnvironmentKey createForEnvironment(UUID merchantId, String label, String environment, List<String> scopes,
                                               String keyType, List<String> allowedIps, String createdBy) {
        if (!"test".equalsIgnoreCase(environment) && !"live".equalsIgnoreCase(environment))
            throw com.elepay.common.ApiException.badRequest("invalid_environment", "Use live or test");
        String env = environment.toLowerCase(java.util.Locale.ROOT);
        if ("test".equals(env) && !testEnvironment) throw com.elepay.common.ApiException.unprocessable("test_environment_disabled", "Test credentials require an isolated test deployment");
        String normalizedType = normalizeKeyType(keyType);
        List<String> resolvedScopes = resolveScopesForType(normalizedType, scopes);
        List<String> normalizedIps = validateAndNormalizeIps(allowedIps);

        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String randomHex = HexFormat.of().formatHex(bytes);
        String lead = (keyType == null || keyType.isBlank()) ? "ek" : switch (normalizedType) {
            case "publishable" -> "pk";
            case "payout" -> "payout";
            default -> "sk";
        };
        String prefix = lead + "_" + env + "_" + randomHex.substring(0, 8);
        String secret = prefix + "_" + randomHex.substring(8);
        UUID id = UUID.randomUUID();
        List<String> sorted = resolvedScopes.stream().distinct().sorted().toList();
        byte[] signingBytes = new byte[32];
        RANDOM.nextBytes(signingBytes);
        String signingSecret = "sec_" + HexFormat.of().formatHex(signingBytes);
        db.sql("""
                INSERT INTO api_credential (id, prefix, secret_hash, merchant_id, label, created_by, scopes,
                    environment, signing_secret, key_type, allowed_ips)
                VALUES (:id, :prefix, :hash, :merchant, :label, :by, :scopes, :env, :signing, :key_type, :allowed_ips)""")
                .param("id", id).param("prefix", prefix).param("hash", Idempotency.sha256(secret))
                .param("merchant", merchantId).param("label", label).param("by", createdBy)
                .param("scopes", sorted.toArray(String[]::new)).param("env", env).param("signing", signingSecret)
                .param("key_type", normalizedType)
                .param("allowed_ips", normalizedIps.toArray(String[]::new))
                .update();
        return new EnvironmentKey(id, prefix, secret, env, sorted, signingSecret, normalizedType, normalizedIps);
    }

    private static String normalizeKeyType(String keyType) {
        if (keyType == null || keyType.isBlank()) return "secret";
        String lower = keyType.trim().toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "publishable", "pk", "pk_live", "pk_test" -> "publishable";
            case "payout", "payout_live", "payout_test" -> "payout";
            case "secret", "sk", "sk_live", "sk_test" -> "secret";
            default -> throw com.elepay.common.ApiException.badRequest("invalid_key_type", "Use publishable, secret, or payout");
        };
    }

    private static List<String> resolveScopesForType(String normalizedType, List<String> requestedScopes) {
        if ("publishable".equals(normalizedType)) {
            return List.of("collect");
        }
        if ("payout".equals(normalizedType)) {
            if (requestedScopes == null || requestedScopes.isEmpty()) {
                return List.of("view", "withdraw", "payouts");
            }
            if (!SCOPES.containsAll(requestedScopes)) {
                throw com.elepay.common.ApiException.badRequest("invalid_scope", "Scopes: view, collect, refund, withdraw, payouts");
            }
            return requestedScopes;
        }
        // secret (or legacy default)
        if (requestedScopes == null || requestedScopes.isEmpty()) {
            return List.of("view", "collect", "refund");
        }
        if (!SCOPES.containsAll(requestedScopes)) {
            throw com.elepay.common.ApiException.badRequest("invalid_scope", "Scopes: view, collect, refund, withdraw, payouts");
        }
        return requestedScopes;
    }

    private static List<String> validateAndNormalizeIps(List<String> allowedIps) {
        if (allowedIps == null || allowedIps.isEmpty()) return List.of();
        List<String> out = new java.util.ArrayList<>();
        for (String ip : allowedIps) {
            if (ip == null || ip.isBlank()) continue;
            try {
                CidrBlock.parse(ip.trim());
                out.add(ip.trim());
            } catch (IllegalArgumentException e) {
                throw com.elepay.common.ApiException.badRequest("invalid_allowed_ip", e.getMessage());
            }
        }
        return List.copyOf(out);
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
        return db.sql("""
                SELECT id, prefix, label, scopes, created_at, revoked_at, COALESCE(key_type, 'secret'), COALESCE(allowed_ips, '{}')
                FROM api_credential WHERE merchant_id = :m ORDER BY created_at""")
                .param("m", merchantId)
                .query((rs, i) -> new Listed(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        List.of((String[]) rs.getArray(4).getArray()),
                        rs.getObject(5, java.time.OffsetDateTime.class), rs.getObject(6, java.time.OffsetDateTime.class),
                        rs.getString(7), List.of((String[]) rs.getArray(8).getArray())))
                .list();
    }

    @Transactional
    public boolean revoke(UUID merchantId, UUID id) {
        var rowOpt = db.sql("SELECT secret_hash FROM api_credential WHERE id = :id AND merchant_id = :m AND revoked_at IS NULL")
                .param("id", id).param("m", merchantId).query(String.class).optional();
        int updated = db.sql("UPDATE api_credential SET revoked_at = now() WHERE id = :id AND merchant_id = :m AND revoked_at IS NULL")
                .param("id", id).param("m", merchantId).update();
        if (updated == 1) {
            cacheRevocation(id, rowOpt.orElse(null));
            return true;
        }
        return false;
    }

    @Transactional
    public boolean emergencyRevokeById(UUID id, String actor) {
        var rowOpt = db.sql("SELECT secret_hash FROM api_credential WHERE id = :id AND revoked_at IS NULL")
                .param("id", id).query(String.class).optional();
        int updated = db.sql("UPDATE api_credential SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL")
                .param("id", id).update();
        cacheRevocation(id, rowOpt.orElse(null));
        return updated == 1;
    }

    private void cacheRevocation(UUID id, String secretHash) {
        if (id != null) {
            revokedKeyIds.add(id);
            writeRedisRevocation(REDIS_REVOKED_PREFIX + id, "revoked");
        }
        if (secretHash != null) {
            revokedKeyHashes.add(secretHash);
            writeRedisRevocation(REDIS_REVOKED_HASH_PREFIX + secretHash, "revoked");
        }
    }

    public boolean isRevokedInCache(UUID id, String secretHash) {
        if (id != null && revokedKeyIds.contains(id)) return true;
        if (secretHash != null && revokedKeyHashes.contains(secretHash)) return true;
        if (id != null && isRevokedInRedis(REDIS_REVOKED_PREFIX + id)) {
            revokedKeyIds.add(id);
            return true;
        }
        if (secretHash != null && isRevokedInRedis(REDIS_REVOKED_HASH_PREFIX + secretHash)) {
            revokedKeyHashes.add(secretHash);
            return true;
        }
        return false;
    }

    private static boolean hasValidKeyPrefix(String secret) {
        return secret.startsWith("ek_") || secret.startsWith("pk_") || secret.startsWith("sk_") || secret.startsWith("payout_");
    }

    private static boolean isTestKey(String secret) {
        return secret.startsWith("ek_test_") || secret.startsWith("pk_test_")
                || secret.startsWith("sk_test_") || secret.startsWith("payout_test_");
    }

    public Optional<ApiKeyAuthentication> authenticate(String secret) {
        if (secret == null || !hasValidKeyPrefix(secret) || secret.length() > 120 || (!testEnvironment && isTestKey(secret))) {
            return Optional.empty();
        }
        String hash = Idempotency.sha256(secret);
        if (isRevokedInCache(null, hash)) {
            return Optional.empty();
        }
        var authOpt = db.sql("""
                SELECT id, merchant_id, scopes, signing_secret, COALESCE(key_type, 'secret'), COALESCE(allowed_ips, '{}')
                FROM api_credential
                WHERE secret_hash = :hash AND revoked_at IS NULL AND (environment = 'live' OR :test)""")
                .param("hash", hash).param("test", testEnvironment)
                .query((rs, i) -> {
                    String dbKeyType = rs.getString(5);
                    String effectiveKeyType = resolveEffectiveKeyType(secret, dbKeyType);
                    List<String> ips = rs.getArray(6) != null ? List.of((String[]) rs.getArray(6).getArray()) : List.of();
                    return new ApiKeyAuthentication(
                            rs.getObject(1, UUID.class),
                            rs.getObject(2, UUID.class),
                            java.util.Set.of((String[]) rs.getArray(3).getArray()),
                            rs.getString(4),
                            effectiveKeyType,
                            ips
                    );
                })
                .optional();
        if (authOpt.isPresent() && isRevokedInCache(authOpt.get().credentialId(), hash)) {
            return Optional.empty();
        }
        return authOpt;
    }

    private static String resolveEffectiveKeyType(String secret, String dbKeyType) {
        if (secret.startsWith("pk_")) return "publishable";
        if (secret.startsWith("payout_")) return "payout";
        if (secret.startsWith("sk_")) return "secret";
        if (secret.startsWith("ek_")) return "legacy";
        return dbKeyType != null ? dbKeyType : "secret";
    }

    public boolean isIpAllowed(ApiKeyAuthentication auth, String callerIp) {
        List<String> allowedIps = auth.allowedIps();
        if ("payout".equalsIgnoreCase(auth.keyType())) {
            // payout_live_* keys require a registered server IP allowlist
            if (allowedIps == null || allowedIps.isEmpty()) {
                return false;
            }
        } else if (allowedIps == null || allowedIps.isEmpty()) {
            return true;
        }
        if (callerIp == null || callerIp.isBlank()) return false;
        for (String spec : allowedIps) {
            try {
                if (CidrBlock.parse(spec).contains(callerIp.trim())) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {}
        }
        return false;
    }

    public Optional<String> getSigningSecretForCredential(UUID credentialId) {
        if (credentialId == null) return Optional.empty();
        return db.sql("SELECT signing_secret FROM api_credential WHERE id = :id AND revoked_at IS NULL")
                .param("id", credentialId)
                .query((rs, i) -> rs.getString(1))
                .optional();
    }

    public Optional<String> getSigningSecretForApiKey(String apiKey) {
        if (apiKey == null || !hasValidKeyPrefix(apiKey) || apiKey.length() > 120) return Optional.empty();
        String hash = Idempotency.sha256(apiKey);
        if (isRevokedInCache(null, hash)) return Optional.empty();
        return db.sql("SELECT signing_secret FROM api_credential WHERE secret_hash = :hash AND revoked_at IS NULL AND (environment = 'live' OR :test)")
                .param("hash", hash).param("test", testEnvironment)
                .query((rs, i) -> rs.getString(1))
                .optional();
    }

    private void writeRedisRevocation(String key, String value) {
        if (redis == null || !redisBreaker.allow()) return;
        try {
            redis.opsForValue().set(key, value, Duration.ofHours(24));
        } catch (Exception e) {
            redisBreaker.failure();
        }
    }

    private boolean isRevokedInRedis(String key) {
        if (redis == null || !redisBreaker.allow()) return false;
        try {
            String val = redis.opsForValue().get(key);
            return val != null;
        } catch (Exception e) {
            redisBreaker.failure();
            return false;
        }
    }
}
