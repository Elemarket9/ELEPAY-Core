package com.elepay.rails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.elepay.common.ApiException;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@DisplayName("Algorithm 018: Risk-Based 3-D Secure (3DS 2.x)")
class ThreeDSecureTest {

    private JdbcClient db;
    private ThreeDSecureService threeDs;
    private final Map<String, ThreeDSecureService.ThreeDSSession> sessions = new HashMap<>();

    @BeforeEach
    void setUp() {
        db = mock(JdbcClient.class, RETURNS_DEEP_STUBS);

        // Store sessions in map on INSERT
        when(db.sql(contains("INSERT INTO three_d_secure_session")).param(anyString(), any()).update()).thenAnswer(inv -> {
            return 1;
        });

        threeDs = new ThreeDSecureService(db, "http://localhost:8080");
    }

    @Test
    @DisplayName("Low risk transaction executes frictionless flow with liability shift")
    void frictionlessFlowLowRisk() {
        // Frictionless threshold is <= 100 GHS (10000 pesewas)
        when(db.sql(contains("SELECT * FROM three_d_secure_session WHERE session_id = :sid"))
                .param(eq("sid"), anyString())
                .query(any(org.springframework.jdbc.core.RowMapper.class))
                .optional()).thenAnswer(inv -> {
                    return Optional.of(new ThreeDSecureService.ThreeDSSession(
                            UUID.randomUUID(), "3ds_test123", "EPC123456", 5000L, "GHS",
                            "tok_card_test", "frictionless", "authenticated", "DS-123",
                            "05", "cavv_test", true, 10, null, OffsetDateTime.now()
                    ));
                });

        var session = threeDs.initiate(new ThreeDSecureService.InitRequest(
                "EPC123456", 5000L, "GHS", "tok_card_test", 10
        ));

        assertThat(session.flowType()).isEqualTo("frictionless");
        assertThat(session.status()).isEqualTo("authenticated");
        assertThat(session.liabilityShift()).isTrue();
        assertThat(session.eci()).isEqualTo("05");
        assertThat(session.dsTransId()).isNotNull();
    }

    @Test
    @DisplayName("Higher risk transaction triggers challenge flow requiring OTP authentication")
    void challengeFlowHighRisk() {
        // High amount > 10000 pesewas triggers challenge flow
        when(db.sql(contains("SELECT * FROM three_d_secure_session WHERE session_id = :sid"))
                .param(eq("sid"), anyString())
                .query(any(org.springframework.jdbc.core.RowMapper.class))
                .optional()).thenAnswer(inv -> {
                    return Optional.of(new ThreeDSecureService.ThreeDSSession(
                            UUID.randomUUID(), "3ds_challenge123", "EPC999888", 25000L, "GHS",
                            "tok_card_test", "challenge", "challenged", "DS-999",
                            null, null, false, 50, "http://localhost:8080/v1/rails/card/3ds/challenge/3ds_challenge123",
                            OffsetDateTime.now()
                    ));
                });

        var session = threeDs.initiate(new ThreeDSecureService.InitRequest(
                "EPC999888", 25000L, "GHS", "tok_card_test", 50
        ));

        assertThat(session.flowType()).isEqualTo("challenge");
        assertThat(session.status()).isEqualTo("challenged");
        assertThat(session.liabilityShift()).isFalse();
        assertThat(session.challengeUrl()).isNotNull();
    }
}
