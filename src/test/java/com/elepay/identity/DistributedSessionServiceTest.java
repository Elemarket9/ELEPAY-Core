package com.elepay.identity;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedSessionServiceTest {

    private DistributedSessionService sessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        sessionService = new DistributedSessionService(null, null, objectMapper);
    }

    @Test
    @DisplayName("Create, retrieve, touch, and revoke session")
    void sessionLifecycle() {
        String userId = "usr_999";
        Map<String, Object> attrs = Map.of("role", "merchant_admin", "ip", "192.168.1.1");

        // 1. Create
        DistributedSessionService.UserSession session = sessionService.createSession(userId, attrs, Duration.ofMinutes(15));
        assertThat(session).isNotNull();
        assertThat(session.sessionId()).startsWith("sess_");
        assertThat(session.userId()).isEqualTo(userId);
        assertThat(session.attributes()).containsEntry("role", "merchant_admin");

        // 2. Retrieve
        Optional<DistributedSessionService.UserSession> fetched = sessionService.getSession(session.sessionId());
        assertThat(fetched).isPresent();
        assertThat(fetched.get().sessionId()).isEqualTo(session.sessionId());

        // 3. Touch
        boolean touched = sessionService.touchSession(session.sessionId(), Duration.ofMinutes(30));
        assertThat(touched).isTrue();

        Optional<DistributedSessionService.UserSession> afterTouch = sessionService.getSession(session.sessionId());
        assertThat(afterTouch).isPresent();
        assertThat(afterTouch.get().expiresAt()).isAfter(session.expiresAt());

        // 4. Revoke
        sessionService.revokeSession(session.sessionId());
        Optional<DistributedSessionService.UserSession> revoked = sessionService.getSession(session.sessionId());
        assertThat(revoked).isEmpty();
    }

    @Test
    @DisplayName("Revoke all sessions for a user invalidates all user sessions")
    void revokeAllForUser() {
        String userA = "usr_A";
        String userB = "usr_B";

        var sessA1 = sessionService.createSession(userA, Map.of(), Duration.ofHours(1));
        var sessA2 = sessionService.createSession(userA, Map.of(), Duration.ofHours(1));
        var sessB1 = sessionService.createSession(userB, Map.of(), Duration.ofHours(1));

        sessionService.revokeAllSessionsForUser(userA);

        assertThat(sessionService.getSession(sessA1.sessionId())).isEmpty();
        assertThat(sessionService.getSession(sessA2.sessionId())).isEmpty();
        // userB's session remains active
        assertThat(sessionService.getSession(sessB1.sessionId())).isPresent();
    }
}
