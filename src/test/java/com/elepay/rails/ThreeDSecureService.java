package com.elepay.rails;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 018: Risk-based 3-D Secure (3DS 2.x).
 * Supports both frictionless and challenge flows while preserving
 * Directory Server (DS) transaction IDs, ECI, CAVV, and liability shift evidence.
 */
@Service
public class ThreeDSecureService {
    private static final Logger log = LoggerFactory.getLogger(ThreeDSecureService.class);
    private static final long FRICTIONLESS_THRESHOLD_PESEWAS = 10000L; // 100 GHS
    private static final int MAX_CHALLENGE_ATTEMPTS = 3;

    public record ThreeDSSession(
            UUID id,
            String sessionId,
            String reference,
            long amount,
            String currency,
            String cardToken,
            String flowType,
            String status,
            String dsTransId,
            String eci,
            String cavv,
            boolean liabilityShift,
            int riskScore,
            String challengeUrl,
            OffsetDateTime createdAt
    ) {}

    public record InitRequest(
            String reference,
            long amount,
            String currency,
            String cardToken,
            Integer clientRiskScore
    ) {}

    private final JdbcClient db;
    private final String baseUrl;
    private final SecureRandom rng = new SecureRandom();

    public ThreeDSecureService(
            JdbcClient db,
            @Value("${elepay.public-base-url:http://127.0.0.1:8080}") String baseUrl
    ) {
        this.db = db;
        this.baseUrl = baseUrl;
    }

    /**
     * Evaluates risk and initiates 3-D Secure 2.x flow.
     */
    @Transactional
    public ThreeDSSession initiate(InitRequest request) {
        int riskScore = request.clientRiskScore() != null ? request.clientRiskScore() : calculateRiskScore(request.amount());
        boolean isFrictionless = (riskScore < 40 && request.amount() <= FRICTIONLESS_THRESHOLD_PESEWAS);

        UUID id = UUID.randomUUID();
        String sessionId = "3ds_" + UUID.randomUUID().toString().replace("-", "");
        String dsTransId = "DS-" + UUID.randomUUID();
        String flowType = isFrictionless ? "frictionless" : "challenge";

        if (isFrictionless) {
            // Low risk -> Frictionless flow: immediate authentication with liability shift
            String eci = "05"; // ECI 05: Fully authenticated / frictionless liability shift
            String cavv = generateCavv(request.reference(), dsTransId);

            db.sql("""
                    INSERT INTO three_d_secure_session (id, session_id, reference, amount, currency,
                                                        card_token, flow_type, status, ds_trans_id,
                                                        eci, cavv, liability_shift, risk_score)
                    VALUES (:id, :sid, :ref, :amount, :cur, :tok, :flow, 'authenticated', :ds,
                            :eci, :cavv, true, :risk)""")
                    .param("id", id).param("sid", sessionId).param("ref", request.reference())
                    .param("amount", request.amount()).param("cur", request.currency() != null ? request.currency() : "GHS")
                    .param("tok", request.cardToken()).param("flow", flowType)
                    .param("ds", dsTransId).param("eci", eci).param("cavv", cavv).param("risk", riskScore)
                    .update();

            log.info("3DS 2.x frictionless authentication succeeded for reference {} (dsTransId={})", request.reference(), dsTransId);
        } else {
            // Elevated risk -> Challenge flow: issue OTP challenge
            String otpCode = String.format("%06d", rng.nextInt(1_000_000));
            String challengeUrl = baseUrl + "/v1/rails/card/3ds/challenge/" + sessionId;

            db.sql("""
                    INSERT INTO three_d_secure_session (id, session_id, reference, amount, currency,
                                                        card_token, flow_type, status, ds_trans_id,
                                                        liability_shift, risk_score, challenge_url, otp_code)
                    VALUES (:id, :sid, :ref, :amount, :cur, :tok, :flow, 'challenged', :ds,
                            false, :risk, :url, :otp)""")
                    .param("id", id).param("sid", sessionId).param("ref", request.reference())
                    .param("amount", request.amount()).param("cur", request.currency() != null ? request.currency() : "GHS")
                    .param("tok", request.cardToken()).param("flow", flowType)
                    .param("ds", dsTransId).param("risk", riskScore).param("url", challengeUrl).param("otp", otpCode)
                    .update();

            log.info("3DS 2.x challenge issued for reference {} (sessionId={}, challengeUrl={})", request.reference(), sessionId, challengeUrl);
        }

        return findBySessionId(sessionId).orElseThrow();
    }

    /**
     * Completes a 3DS challenge by verifying the customer OTP / ACS response.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public ThreeDSSession completeChallenge(String sessionId, String submittedOtp) {
        var row = db.sql("SELECT * FROM three_d_secure_session WHERE session_id = :sid FOR UPDATE")
                .param("sid", sessionId)
                .query(this::mapFull)
                .optional()
                .orElseThrow(() -> ApiException.notFound("session_not_found", "3DS session not found"));

        if (!"challenged".equals(row.status())) {
            throw ApiException.unprocessable("invalid_state", "3DS session is in status " + row.status());
        }

        if (row.attempts() >= MAX_CHALLENGE_ATTEMPTS) {
            db.sql("UPDATE three_d_secure_session SET status = 'rejected', updated_at = now() WHERE session_id = :sid")
                    .param("sid", sessionId).update();
            throw ApiException.badRequest("challenge_exhausted", "Maximum 3DS challenge attempts exceeded");
        }

        boolean valid = row.otpCode() != null && submittedOtp != null && java.security.MessageDigest.isEqual(row.otpCode().getBytes(java.nio.charset.StandardCharsets.UTF_8), submittedOtp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (valid) {
            String eci = "05";
            String cavv = generateCavv(row.reference(), row.dsTransId());
            db.sql("""
                    UPDATE three_d_secure_session SET status = 'authenticated', eci = :eci, cavv = :cavv,
                           liability_shift = true, updated_at = now()
                    WHERE session_id = :sid""")
                    .param("sid", sessionId).param("eci", eci).param("cavv", cavv).update();
            log.info("3DS challenge successfully passed for session {}", sessionId);
        } else {
            int newAttempts = row.attempts() + 1;
            String newStatus = newAttempts >= MAX_CHALLENGE_ATTEMPTS ? "rejected" : "challenged";
            db.sql("""
                    UPDATE three_d_secure_session SET challenge_attempts = :att, status = :st, updated_at = now()
                    WHERE session_id = :sid""")
                    .param("sid", sessionId).param("att", newAttempts).param("st", newStatus).update();
            throw ApiException.badRequest("invalid_otp", "Invalid 3DS authentication code");
        }

        return findBySessionId(sessionId).orElseThrow();
    }

    public Optional<ThreeDSSession> findBySessionId(String sessionId) {
        return db.sql("SELECT * FROM three_d_secure_session WHERE session_id = :sid")
                .param("sid", sessionId)
                .query(this::mapPublic)
                .optional();
    }

    public Optional<ThreeDSSession> findByReference(String reference) {
        return db.sql("SELECT * FROM three_d_secure_session WHERE reference = :r ORDER BY created_at DESC LIMIT 1")
                .param("r", reference)
                .query(this::mapPublic)
                .optional();
    }

    private int calculateRiskScore(long amount) {
        if (amount > 100_000) return 80;
        if (amount > 20_000) return 50;
        return 10;
    }

    private static String generateCavv(String reference, String dsTransId) {
        return Idempotency.sha256("cavv:" + reference + ":" + dsTransId).substring(0, 28);
    }

    private ThreeDSSession mapPublic(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ThreeDSSession(
                rs.getObject("id", UUID.class),
                rs.getString("session_id"),
                rs.getString("reference"),
                rs.getLong("amount"),
                rs.getString("currency"),
                rs.getString("card_token"),
                rs.getString("flow_type"),
                rs.getString("status"),
                rs.getString("ds_trans_id"),
                rs.getString("eci"),
                rs.getString("cavv"),
                rs.getBoolean("liability_shift"),
                rs.getInt("risk_score"),
                rs.getString("challenge_url"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private record FullSession(
            String sessionId, String reference, String dsTransId, String status,
            String otpCode, int attempts
    ) {}

    private FullSession mapFull(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new FullSession(
                rs.getString("session_id"),
                rs.getString("reference"),
                rs.getString("ds_trans_id"),
                rs.getString("status"),
                rs.getString("otp_code"),
                rs.getInt("challenge_attempts")
        );
    }
}
