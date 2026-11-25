package com.elepay.identity;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    private final JdbcClient db;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, UserSession> memorySessions = new ConcurrentHashMap<>();

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
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.db = db;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
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
                throw new IllegalStateException("Failed to store session in Redis", e);
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
        if (sessionId == null) return;
        memorySessions.remove(sessionId);

        if (redisTemplate != null) {
            try {
                redisTemplate.delete(REDIS_PREFIX + sessionId);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to revoke session in Redis", e);
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
        if (userId == null) return;

        memorySessions.entrySet().removeIf(e -> userId.equals(e.getValue().userId()));

        if (redisTemplate != null) {
            try {
                Set<String> sessionIds = redisTemplate.opsForSet().members(REDIS_USER_PREFIX + userId);
                if (sessionIds != null && !sessionIds.isEmpty()) {
                    List<String> keys = sessionIds.stream().map(sid -> REDIS_PREFIX + sid).toList();
                    redisTemplate.delete(keys);
                    redisTemplate.delete(REDIS_USER_PREFIX + userId);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to revoke user sessions in Redis", e);
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
}
