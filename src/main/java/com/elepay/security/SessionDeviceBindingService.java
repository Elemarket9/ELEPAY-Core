package com.elepay.security;

import com.elepay.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Session and device binding service (Algorithm 082).
 * Implements strict refresh token rotation with token family tracking,
 * automatic family-wide revocation on token reuse detection,
 * and cryptographic device fingerprint binding.
 */
public class SessionDeviceBindingService {

    public record SessionRecord(
            UUID sessionId,
            UUID userId,
            UUID familyId,
            String currentRefreshTokenHash,
            String deviceId,
            String deviceFingerprint,
            boolean isRevoked,
            String revokedReason,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt
    ) {}

    public record TokenPair(
            String accessToken,
            String refreshToken,
            UUID sessionId,
            UUID familyId,
            Instant expiresAt
    ) {}

    private final JdbcClient db;
    private final Map<UUID, SessionRecord> memorySessions = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionDeviceBindingService(JdbcClient db) {
        this.db = db;
    }

    public TokenPair createSession(UUID userId, String deviceId, String deviceFingerprint, Duration ttl) {
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(deviceId, "deviceId cannot be null");

        UUID sessionId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String refreshToken = generateSecureToken();
        String refreshTokenHash = hashToken(refreshToken);
        String accessToken = generateSecureToken();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl != null ? ttl : Duration.ofDays(30));

        SessionRecord session = new SessionRecord(
                sessionId,
                userId,
                familyId,
                refreshTokenHash,
                deviceId,
                deviceFingerprint != null ? deviceFingerprint : "",
                false,
                null,
                now,
                now,
                expiresAt
        );

        memorySessions.put(sessionId, session);
        persistSession(session);

        return new TokenPair(accessToken, refreshToken, sessionId, familyId, expiresAt);
    }

    public TokenPair rotateRefreshToken(UUID sessionId, String presentedRefreshToken, String currentDeviceId, String currentFingerprint) {
        SessionRecord session = getSession(sessionId);
        if (session == null) {
            throw ApiException.notFound("Session not found: " + sessionId);
        }

        // 1. Check if session was already revoked
        if (session.isRevoked()) {
            throw ApiException.forbidden("Session is revoked: " + session.revokedReason());
        }

        // 2. Check expiration
        if (Instant.now().isAfter(session.expiresAt())) {
            revokeSession(sessionId, "SESSION_EXPIRED");
            throw ApiException.forbidden("Session has expired");
        }

        // 3. Check Device Binding
        if (!session.deviceId().equals(currentDeviceId)) {
            throw ApiException.forbidden("Device ID mismatch: session bound to " + session.deviceId() + " but presented by " + currentDeviceId);
        }

        if (session.deviceFingerprint() != null && !session.deviceFingerprint().isEmpty()) {
            if (currentFingerprint == null || currentFingerprint.isBlank()
                    || !session.deviceFingerprint().equals(currentFingerprint)) {
                throw ApiException.forbidden("Device fingerprint mismatch");
            }
        }

        // 4. Token verification & Reuse Detection
        String presentedHash = hashToken(presentedRefreshToken);
        if (!session.currentRefreshTokenHash().equals(presentedHash)) {
            // CRITICAL: Token reuse detected! A previously invalidated or stolen token was presented.
            // Revoke the entire token family immediately to protect the user account.
            revokeFamily(session.familyId(), "REFRESH_TOKEN_REUSE_DETECTED");
            throw ApiException.forbidden("Refresh token reuse detected; entire token family has been revoked");
        }

        // 5. Successful rotation: issue new refresh token and access token
        String newRefreshToken = generateSecureToken();
        String newRefreshTokenHash = hashToken(newRefreshToken);
        String newAccessToken = generateSecureToken();
        Instant now = Instant.now();

        SessionRecord updated = new SessionRecord(
                session.sessionId(),
                session.userId(),
                session.familyId(),
                newRefreshTokenHash,
                session.deviceId(),
                session.deviceFingerprint(),
                false,
                null,
                session.createdAt(),
                now,
                session.expiresAt()
        );

        memorySessions.put(sessionId, updated);
        updateSession(updated);

        return new TokenPair(newAccessToken, newRefreshToken, sessionId, session.familyId(), session.expiresAt());
    }

    public void revokeSession(UUID sessionId, String reason) {
        SessionRecord session = getSession(sessionId);
        if (session != null) {
            SessionRecord revoked = new SessionRecord(
                    session.sessionId(),
                    session.userId(),
                    session.familyId(),
                    session.currentRefreshTokenHash(),
                    session.deviceId(),
                    session.deviceFingerprint(),
                    true,
                    reason,
                    session.createdAt(),
                    Instant.now(),
                    session.expiresAt()
            );
            memorySessions.put(sessionId, revoked);
            updateSession(revoked);
        }
    }

    public void revokeFamily(UUID familyId, String reason) {
        memorySessions.values().stream()
                .filter(s -> s.familyId().equals(familyId))
                .forEach(s -> revokeSession(s.sessionId(), reason));

        if (db != null) {
            try {
                db.sql("UPDATE user_session_device SET is_revoked = true, revoked_reason = ?, updated_at = now() WHERE refresh_token_family = ?")
                        .params(reason, familyId)
                        .update();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to revoke session family", e);
            }
        }
    }

    public SessionRecord getSession(UUID sessionId) {
        SessionRecord mem = memorySessions.get(sessionId);
        if (mem != null) {
            return mem;
        }
        if (db != null) {
            try {
                return db.sql("SELECT * FROM user_session_device WHERE session_id = ?")
                        .params(sessionId.toString())
                        .query((rs, rowNum) -> new SessionRecord(
                                UUID.fromString(rs.getString("session_id")),
                                UUID.fromString(rs.getString("user_id")),
                                UUID.fromString(rs.getString("refresh_token_family")),
                                rs.getString("current_refresh_token_hash"),
                                rs.getString("device_id"),
                                rs.getString("device_fingerprint"),
                                rs.getBoolean("is_revoked"),
                                rs.getString("revoked_reason"),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("updated_at").toInstant(),
                                rs.getTimestamp("expires_at").toInstant()
                        ))
                        .optional().orElse(null);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load device session", e);
            }
        }
        return null;
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not supported", e);
        }
    }

    private void persistSession(SessionRecord s) {
        if (db != null) {
            try {
                db.sql("""
                    INSERT INTO user_session_device
                    (id, session_id, user_id, refresh_token_family, current_refresh_token_hash, device_id, device_fingerprint, is_revoked, revoked_reason, created_at, updated_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(UUID.randomUUID(), s.sessionId().toString(), s.userId(), s.familyId(), s.currentRefreshTokenHash(), s.deviceId(), s.deviceFingerprint(), s.isRevoked(), s.revokedReason(), s.createdAt(), s.updatedAt(), s.expiresAt())
                    .update();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to persist device session", e);
            }
        }
    }

    private void updateSession(SessionRecord s) {
        if (db != null) {
            try {
                db.sql("""
                    UPDATE user_session_device
                    SET current_refresh_token_hash = ?, is_revoked = ?, revoked_reason = ?, updated_at = ?
                    WHERE session_id = ?
                    """)
                    .params(s.currentRefreshTokenHash(), s.isRevoked(), s.revokedReason(), s.updatedAt(), s.sessionId().toString())
                    .update();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to update device session", e);
            }
        }
    }
}
