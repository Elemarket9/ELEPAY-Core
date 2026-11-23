package com.elepay.risk;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Algorithm 029: Adaptive step-up authentication.
 * Dynamically issues single-use challenges cryptographically bound to the intended action payload.
 * Prevents payload tampering, replay attacks, and enforces rate-limited attempt verification.
 */
@Service
public class StepUpAuthService {

    private final JdbcClient db;
    private final SecureRandom secureRandom = new SecureRandom();

    public StepUpAuthService(JdbcClient db) {
        this.db = db;
    }

    public enum ChallengeType {
        SMS_OTP,
        EMAIL_OTP,
        TOTP,
        BIOMETRIC
    }

    public enum ChallengeStatus {
        PENDING,
        VERIFIED,
        EXPIRED,
        FAILED
    }

    public record ActionPayload(
            String actorId,
            String actionType,
            String targetRecipient,
            long amountMinorUnits,
            String currency,
            String nonce
    ) {
        public String computeHash() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                String raw = String.join("|",
                        actorId != null ? actorId : "",
                        actionType != null ? actionType : "",
                        targetRecipient != null ? targetRecipient : "",
                        String.valueOf(amountMinorUnits),
                        currency != null ? currency : "",
                        nonce != null ? nonce : ""
                );
                byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-256 unavailable", e);
            }
        }
    }

    public record StepUpChallenge(
            String challengeToken,
            String actorId,
            ChallengeType challengeType,
            String boundAction,
            String boundPayloadHash,
            OffsetDateTime expiresAt
    ) {}

    public record VerificationResult(
            boolean verified,
            String reason
    ) {}

    @Transactional
    public StepUpChallenge issueChallenge(ActionPayload payload, ChallengeType challengeType, String testSecretCode) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plusMinutes(5);

        String challengeToken = "ch_" + UUID.randomUUID().toString().replace("-", "");
        String payloadHash = payload.computeHash();

        String secretCode;
        if (testSecretCode != null && !testSecretCode.isBlank()) {
            secretCode = testSecretCode;
        } else {
            // Generate 6-digit numeric OTP
            int codeInt = 100_000 + secureRandom.nextInt(900_000);
            secretCode = String.valueOf(codeInt);
        }

        String codeHash = hashCode(secretCode);

        db.sql("""
            INSERT INTO risk_step_up_challenges (
                id, challenge_token, actor_id, bound_action, bound_payload_hash,
                challenge_type, secret_code_hash, status, attempts, max_attempts,
                expires_at, created_at
            ) VALUES (
                :id, :token, :actorId, :action, :payloadHash,
                :type, :codeHash, 'PENDING', 0, 3,
                :expiresAt, :now
            )
        """)
                .param("id", UUID.randomUUID())
                .param("token", challengeToken)
                .param("actorId", payload.actorId())
                .param("action", payload.actionType())
                .param("payloadHash", payloadHash)
                .param("type", challengeType.name())
                .param("codeHash", codeHash)
                .param("expiresAt", expiresAt)
                .param("now", now)
                .update();

        return new StepUpChallenge(
                challengeToken,
                payload.actorId(),
                challengeType,
                payload.actionType(),
                payloadHash,
                expiresAt
        );
    }

    @Transactional
    public VerificationResult verifyChallenge(String challengeToken, String secretCode, ActionPayload submittedPayload) {
        return verifyChallenge(challengeToken, secretCode, submittedPayload, null);
    }

    public VerificationResult verifyChallenge(String challengeToken, String secretCode, ActionPayload submittedPayload,
                                              String callerSubject) {
        OffsetDateTime now = OffsetDateTime.now();

        var recordOpt = db.sql("""
            SELECT id, actor_id, bound_action, bound_payload_hash, secret_code_hash, status, attempts, max_attempts, expires_at
            FROM risk_step_up_challenges
            WHERE challenge_token = :token
            FOR UPDATE
        """)
                .param("token", challengeToken)
                .query((rs, rowNum) -> new Object() {
                    UUID id = rs.getObject("id", UUID.class);
                    String actorId = rs.getString("actor_id");
                    String boundPayloadHash = rs.getString("bound_payload_hash");
                    String secretCodeHash = rs.getString("secret_code_hash");
                    String status = rs.getString("status");
                    int attempts = rs.getInt("attempts");
                    int maxAttempts = rs.getInt("max_attempts");
                    OffsetDateTime expiresAt = rs.getObject("expires_at", OffsetDateTime.class);
                })
                .optional();

        if (recordOpt.isEmpty()) {
            return new VerificationResult(false, "Challenge token not found");
        }

        var rec = recordOpt.get();

        if (callerSubject != null && !callerSubject.equals(rec.actorId)) {
            return new VerificationResult(false, "Challenge is not bound to this caller");
        }

        if (!"PENDING".equals(rec.status)) {
            return new VerificationResult(false, "Challenge token is already " + rec.status + "; replay rejected");
        }

        if (now.isAfter(rec.expiresAt)) {
            db.sql("UPDATE risk_step_up_challenges SET status = 'EXPIRED' WHERE id = :id")
                    .param("id", rec.id).update();
            return new VerificationResult(false, "Challenge token expired");
        }

        if (rec.attempts >= rec.maxAttempts) {
            db.sql("UPDATE risk_step_up_challenges SET status = 'FAILED' WHERE id = :id")
                    .param("id", rec.id).update();
            return new VerificationResult(false, "Maximum verification attempts exceeded");
        }

        // Cryptographically verify payload binding: prevent tampering
        String submittedHash = submittedPayload.computeHash();
        if (!rec.boundPayloadHash.equals(submittedHash)) {
            return new VerificationResult(false, "Cryptographic payload mismatch: challenge was not issued for this action or recipient");
        }

        // Verify secret code hash
        String expectedHash = rec.secretCodeHash;
        String actualHash = hashCode(secretCode);

        if (!java.security.MessageDigest.isEqual(expectedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actualHash.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            int newAttempts = rec.attempts + 1;
            String newStatus = newAttempts >= rec.maxAttempts ? "FAILED" : "PENDING";
            db.sql("UPDATE risk_step_up_challenges SET attempts = :attempts, status = :status WHERE id = :id")
                    .param("attempts", newAttempts)
                    .param("status", newStatus)
                    .param("id", rec.id)
                    .update();
            return new VerificationResult(false, "Invalid secret code. Attempts remaining: " + (rec.maxAttempts - newAttempts));
        }

        // Successfully verified: single-use transition
        db.sql("UPDATE risk_step_up_challenges SET status = 'VERIFIED', verified_at = :now WHERE id = :id")
                .param("now", now)
                .param("id", rec.id)
                .update();

        return new VerificationResult(true, "Successfully verified");
    }

    private String hashCode(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("elepay_step_up_salt:" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
