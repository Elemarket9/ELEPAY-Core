package com.elepay.identity;

import com.elepay.common.ApiException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distributed Session Management (Algorithm 082 / Domain 15).
 * Coordinates user session lifecycle across instances using Redis with PostgreSQL fallback.
 */
@Service
public class DistributedSessionService {
    private static final String REDIS_PREFIX = "elepay:session:";
    private static final String REDIS_USER_PREFIX = "elepay:user_sessions:";
    private static final String REDIS_REVOKED_JTI_PREFIX = "elepay:revoked:jti:";
    private static final String REDIS_REVOKED_USER_PREFIX = "elepay:revoked:user:";
    private static final Duration DEFAULT_REVOCATION_TTL = Duration.ofHours(24);

    private final JdbcClient db;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean requireRedis;
    private final ConcurrentHashMap<String, UserSession> memorySessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> revokedJtis = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> revokedUsersBefore = new ConcurrentHashMap<>();

    public record UserSession(
            String sessionId,
            String userId,
            Instant createdAt,
            Instant expiresAt,
            Instant lastAccessedAt,
            Map<String, Object> attributes,
            boolean revoked
    ) {}

    public DistributedSessionService(
            JdbcClient db,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this(db, redisTemplate, objectMapper, true);
    }

    @Autowired
    public DistributedSessionService(
            JdbcClient db,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) ObjectMapper objectMapper,
            @Autowired(required = false) Environment env,
            @Value("${elepay.rate-limits.shared-required:false}") boolean sharedRequired) {
        this(db, redisTemplate, objectMapper,
                sharedRequired || (env != null && env.acceptsProfiles(Profiles.of("prod", "production"))
                        && !env.acceptsProfiles(Profiles.of("test", "dev", "sandbox"))));
    }

    private DistributedSessionService(
            JdbcClient db,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            boolean requireRedis) {
        this.db = db;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.requireRedis = requireRedis;
    }

    @Transactional
    public UserSession createSession(String userId, Map<String, Object> attributes, Duration ttl) {
        String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        Map<String, Object> attrs = attributes != null ? new HashMap<>(attributes) : new HashMap<>();

        UserSession session = new UserSession(sessionId, userId, now, expiresAt, now, attrs, false);

        // 1. In-memory
        memorySessions.put(sessionId, session);

        // 2. Redis
        if (redisTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(session);
                redisTemplate.opsForValue().set(REDIS_PREFIX + sessionId, json, ttl);
                redisTemplate.opsForSet().add(REDIS_USER_PREFIX + userId, sessionId);
                redisTemplate.expire(REDIS_USER_PREFIX + userId, ttl.multipliedBy(2));
            } catch (Exception e) {
                if (requireRedis) {
                    throw new IllegalStateException("Failed to store session in Redis", e);
                }
            }
        }

        // 3. PostgreSQL
        if (db != null) {
            try {
                String attrJson = objectMapper.writeValueAsString(attrs);
                db.sql("""
                        INSERT INTO user_session (session_id, user_id, created_at, expires_at, last_accessed_at, attributes, revoked)
                        VALUES (:sid, :uid, :cat, :eat, :lat, :attr::jsonb, false)
                        """)
                        .param("sid", sessionId)
                        .param("uid", userId)
                        .param("cat", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .param("eat", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                        .param("lat", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .param("attr", attrJson)
                        .update();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to persist session in PostgreSQL", e);
            }
        }

        return session;
    }

    public Optional<UserSession> getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();

        // 1. Try Redis
        if (redisTemplate != null) {
            try {
                String json = redisTemplate.opsForValue().get(REDIS_PREFIX + sessionId);
                if (json != null) {
                    UserSession session = objectMapper.readValue(json, UserSession.class);
                    if (!session.revoked() && session.expiresAt().isAfter(Instant.now())) {
                        return Optional.of(session);
                    }
                    return Optional.empty();
                }
            } catch (Exception ignored) {}
        }

        // 2. Try Memory
        UserSession mem = memorySessions.get(sessionId);
        if (mem != null) {
            if (!mem.revoked() && mem.expiresAt().isAfter(Instant.now())) {
                return Optional.of(mem);
            }
            memorySessions.remove(sessionId);
            return Optional.empty();
        }

        // 3. Try PostgreSQL
        if (db != null) {
            try {
                return db.sql("""
                        SELECT session_id, user_id, created_at, expires_at, last_accessed_at, attributes::text, revoked
                        FROM user_session
                        WHERE session_id = :sid AND revoked = false AND expires_at > now()
                        """)
                        .param("sid", sessionId)
                        .query((rs, i) -> {
                            try {
                                Map<String, Object> attrs = rs.getString(6) != null
                                        ? objectMapper.readValue(rs.getString(6), new TypeReference<Map<String, Object>>() {})
                                        : Map.of();
                                return new UserSession(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getObject(3, OffsetDateTime.class).toInstant(),
                                        rs.getObject(4, OffsetDateTime.class).toInstant(),
                                        rs.getObject(5, OffsetDateTime.class).toInstant(),
                                        attrs,
                                        rs.getBoolean(7)
                                );
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }).optional();
            } catch (Exception ignored) {}
        }

        return Optional.empty();
    }

    public boolean touchSession(String sessionId, Duration ttl) {
        Optional<UserSession> existing = getSession(sessionId);
        if (existing.isEmpty()) return false;

        UserSession current = existing.get();
        Instant now = Instant.now();
        Instant newExpiry = now.plus(ttl);
        UserSession updated = new UserSession(
                current.sessionId(), current.userId(), current.createdAt(), newExpiry, now, current.attributes(), false
        );

        memorySessions.put(sessionId, updated);

        if (redisTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(updated);
                redisTemplate.opsForValue().set(REDIS_PREFIX + sessionId, json, ttl);
            } catch (Exception ignored) {}
        }

        if (db != null) {
            try {
                db.sql("""
                        UPDATE user_session
                        SET last_accessed_at = :lat, expires_at = :eat
                        WHERE session_id = :sid AND revoked = false
                        """)
                        .param("lat", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .param("eat", OffsetDateTime.ofInstant(newExpiry, ZoneOffset.UTC))
                        .param("sid", sessionId)
                        .update();
            } catch (Exception ignored) {}
        }

        return true;
    }

    @Transactional
    public void revokeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        memorySessions.remove(sessionId);
        revokedJtis.put(sessionId, Instant.now().plus(DEFAULT_REVOCATION_TTL));

        if (redisTemplate != null) {
            try {
                redisTemplate.delete(REDIS_PREFIX + sessionId);
                redisTemplate.opsForValue().set(REDIS_REVOKED_JTI_PREFIX + sessionId, "1", DEFAULT_REVOCATION_TTL);
            } catch (Exception e) {
                if (requireRedis) {
                    throw new IllegalStateException("Failed to revoke session in Redis", e);
                }
            }
        }

        if (db != null) {
            try {
                db.sql("UPDATE user_session SET revoked = true WHERE session_id = :sid")
                        .param("sid", sessionId)
                        .update();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to revoke session in PostgreSQL", e);
            }
        }
    }

    @Transactional
    public void revokeAllSessionsForUser(String userId) {
        if (userId == null || userId.isBlank()) return;

        memorySessions.entrySet().removeIf(e -> userId.equals(e.getValue().userId()));
        Instant now = Instant.now();
        revokedUsersBefore.put(userId, now);

        if (redisTemplate != null) {
            try {
                Set<String> sessionIds = redisTemplate.opsForSet().members(REDIS_USER_PREFIX + userId);
                if (sessionIds != null && !sessionIds.isEmpty()) {
                    List<String> keys = sessionIds.stream().map(sid -> REDIS_PREFIX + sid).toList();
                    redisTemplate.delete(keys);
                    redisTemplate.delete(REDIS_USER_PREFIX + userId);
                }
                redisTemplate.opsForValue().set(REDIS_REVOKED_USER_PREFIX + userId, String.valueOf(now.toEpochMilli()), DEFAULT_REVOCATION_TTL);
            } catch (Exception e) {
                if (requireRedis) {
                    throw new IllegalStateException("Failed to revoke user sessions in Redis", e);
                }
            }
        }

        if (db != null) {
            try {
                db.sql("UPDATE user_session SET revoked = true WHERE user_id = :uid")
                        .param("uid", userId)
                        .update();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to revoke user sessions in PostgreSQL", e);
            }
        }
    }

    /** Revokes a specific JWT by its {@code jti} claim. */
    @Transactional
    public void revokeToken(String jti, Duration ttl) {
        if (jti == null || jti.isBlank()) return;
        Duration effectiveTtl = (ttl != null && !ttl.isNegative() && !ttl.isZero()) ? ttl : DEFAULT_REVOCATION_TTL;
        memorySessions.remove(jti);
        revokedJtis.put(jti, Instant.now().plus(effectiveTtl));

        if (redisTemplate != null) {
            try {
                redisTemplate.delete(REDIS_PREFIX + jti);
                redisTemplate.opsForValue().set(REDIS_REVOKED_JTI_PREFIX + jti, "1", effectiveTtl);
            } catch (Exception e) {
                if (requireRedis) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                            "revocation_store_unavailable",
                            "Failed to revoke token in Redis");
                }
            }
        }
        if (db != null) {
            try {
                db.sql("UPDATE user_session SET revoked = true WHERE session_id = :sid")
                        .param("sid", jti)
                        .update();
            } catch (Exception ignored) {
                // Non-session JWTs may not have a user_session row
            }
        }
    }

    /** Revokes all active sessions and tokens for a user (by userId and optional subject/email). */
    @Transactional
    public void revokeAllForUser(String userId, String subject) {
        Instant now = Instant.now();
        if (userId != null && !userId.isBlank()) {
            revokeAllSessionsForUser(userId);
        }
        if (subject != null && !subject.isBlank() && !subject.equals(userId)) {
            String normalized = subject.trim().toLowerCase(Locale.ROOT);
            revokedUsersBefore.put(normalized, now);
            if (redisTemplate != null) {
                try {
                    redisTemplate.opsForValue().set(
                            REDIS_REVOKED_USER_PREFIX + normalized,
                            String.valueOf(now.toEpochMilli()),
                            DEFAULT_REVOCATION_TTL);
                } catch (Exception e) {
                    if (requireRedis) {
                        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                                "revocation_store_unavailable",
                                "Failed to revoke user sessions in Redis");
                    }
                }
            }
        }
    }

    /**
     * Checks whether a JWT (identified by {@code jti}, {@code userId}, {@code subject}, and {@code issuedAt})
     * has been revoked via logout, suspension, role downgrade, or password reset.
     */
    public boolean isTokenRevoked(String jti, String userId, String subject, Instant issuedAt) {
        Instant now = Instant.now();
        Instant effectiveIssuedAt = issuedAt;
        if (jti != null && !jti.isBlank()) {
            Instant memExp = revokedJtis.get(jti);
            if (memExp != null) {
                if (memExp.isAfter(now)) {
                    return true;
                }
                revokedJtis.remove(jti);
            }
            if (redisTemplate != null) {
                try {
                    if (Boolean.TRUE.equals(redisTemplate.hasKey(REDIS_REVOKED_JTI_PREFIX + jti))) {
                        return true;
                    }
                } catch (Exception e) {
                    if (requireRedis) {
                        return true;
                    }
                }
            }
            if (jti.startsWith("sess_")) {
                boolean resolvedFromDb = false;
                if (db != null) {
                    try {
                        record SessionState(boolean revoked, Instant createdAt) {}
                        Optional<SessionState> row = db.sql(
                                "SELECT revoked, created_at FROM user_session WHERE session_id = :sid")
                                .param("sid", jti)
                                .query((rs, i) -> new SessionState(
                                        rs.getBoolean(1),
                                        rs.getObject(2, OffsetDateTime.class).toInstant()))
                                .optional();
                        if (row.isPresent()) {
                            resolvedFromDb = true;
                            if (row.get().revoked()) {
                                memorySessions.remove(jti);
                                return true;
                            }
                            effectiveIssuedAt = row.get().createdAt();
                        }
                    } catch (Exception ignored) {
                        // Fall through to Redis/user-level check
                    }
                }
                if (!resolvedFromDb) {
                    UserSession activeMem = memorySessions.get(jti);
                    if (activeMem != null) {
                        if (activeMem.revoked()) {
                            return true;
                        }
                        effectiveIssuedAt = activeMem.createdAt();
                    }
                }
            }
        }

        if (isUserRevokedAt(userId, effectiveIssuedAt)) {
            return true;
        }
        if (subject != null && !subject.isBlank()) {
            if (isUserRevokedAt(subject.trim().toLowerCase(Locale.ROOT), effectiveIssuedAt)) {
                return true;
            }
        }
        return false;
    }

    private boolean isUserRevokedAt(String key, Instant issuedAt) {
        if (key == null || key.isBlank()) return false;
        Instant localTs = revokedUsersBefore.get(key);
        Instant redisTs = null;
        if (redisTemplate != null) {
            try {
                String raw = redisTemplate.opsForValue().get(REDIS_REVOKED_USER_PREFIX + key);
                if (raw != null && !raw.isBlank()) {
                    redisTs = Instant.ofEpochMilli(Long.parseLong(raw.trim()));
                }
            } catch (Exception e) {
                if (requireRedis) {
                    return true;
                }
            }
        }
        Instant revokedBefore = localTs;
        if (redisTs != null && (revokedBefore == null || redisTs.isAfter(revokedBefore))) {
            revokedBefore = redisTs;
            revokedUsersBefore.put(key, redisTs);
        }
        if (revokedBefore == null) {
            return false;
        }
        // JWT iat has 1-second resolution; treat tokens issued at or before the revocation second as revoked.
        return issuedAt == null || !issuedAt.isAfter(revokedBefore);
    }
}
