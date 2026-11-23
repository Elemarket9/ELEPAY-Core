package com.elepay.compliance;

import com.elepay.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Biometric face matching and anti-spoofing liveness service (Algorithm 041).
 * Validates user consent, performs anti-spoofing liveness scoring,
 * evaluates facial similarity via authentic biometric evaluation (or fails closed
 * to human review queue when unconfigured), and manages the manual review queue.
 */
public class FaceMatchingLivenessService {

    public enum VerificationStatus {
        APPROVED,
        REJECTED,
        MANUAL_REVIEW_REQUIRED
    }

    public record FaceVerificationRecord(
            UUID id,
            UUID customerId,
            boolean consentObtained,
            Instant consentTimestamp,
            double livenessScore,
            double matchScore,
            boolean isLive,
            VerificationStatus status,
            String idPhotoHash,
            String selfieHash,
            String reviewerNotes,
            UUID reviewedBy,
            Instant createdAt,
            Instant reviewedAt
    ) {}

    public record BiometricSubmission(
            UUID customerId,
            boolean consentObtained,
            byte[] idPhotoBytes,
            byte[] selfieBytes,
            double measuredLivenessScore
    ) {
        /**
         * Deprecated constructor retaining parameter compatibility.
         * Note: simulatedMatchScore is strictly ignored in production; authentic evaluation is required.
         */
        @Deprecated
        public BiometricSubmission(UUID customerId, boolean consentObtained, byte[] idPhotoBytes, byte[] selfieBytes,
                                  double measuredLivenessScore, double simulatedMatchScore) {
            this(customerId, consentObtained, idPhotoBytes, selfieBytes, measuredLivenessScore);
        }
    }

    /**
     * Authentic biometric evaluation provider contract.
     * Evaluates face similarity between an official ID credential photo and a live capture selfie.
     */
    public interface BiometricEvaluator {
        BiometricEvaluationResult evaluate(byte[] idPhotoBytes, byte[] selfieBytes);
        default boolean isConfigured() { return true; }
        default String getEngineName() { return "AUTHENTIC_BIOMETRIC_EVALUATOR"; }
    }

    public record BiometricEvaluationResult(
            boolean evaluated,
            double matchScore,
            String confidenceBand,
            String details
    ) {}

    private final JdbcClient db;
    private final BiometricEvaluator evaluator;
    private final Map<UUID, FaceVerificationRecord> memoryQueue = new ConcurrentHashMap<>();

    public static final double LIVENESS_THRESHOLD = 0.80;
    public static final double AUTO_APPROVE_THRESHOLD = 0.80;
    public static final double MANUAL_REVIEW_MIN_THRESHOLD = 0.60;

    public FaceMatchingLivenessService(JdbcClient db) {
        this(db, null);
    }

    public FaceMatchingLivenessService(JdbcClient db, BiometricEvaluator evaluator) {
        this.db = db;
        this.evaluator = evaluator;
    }

    public FaceVerificationRecord processBiometrics(BiometricSubmission submission) {
        Objects.requireNonNull(submission, "submission cannot be null");
        Objects.requireNonNull(submission.customerId(), "customerId cannot be null");

        // 1. Mandatory consent check
        if (!submission.consentObtained()) {
            throw ApiException.badRequest("consent_required", "Explicit biometric consent must be granted by the customer before processing");
        }

        if (submission.idPhotoBytes() == null || submission.idPhotoBytes().length == 0
                || submission.selfieBytes() == null || submission.selfieBytes().length == 0) {
            throw ApiException.badRequest("missing_photos", "Both ID photo and selfie image bytes are required");
        }

        // 2. Compute secure SHA-256 hashes (never store raw biometric unencrypted in audit logs)
        String idPhotoHash = hashImage(submission.idPhotoBytes());
        String selfieHash = hashImage(submission.selfieBytes());

        // 3. Evaluate Liveness
        boolean isLive = submission.measuredLivenessScore() >= LIVENESS_THRESHOLD;

        // 4. Determine status based on anti-spoofing & authentic facial evaluation
        VerificationStatus status;
        double matchScore = 0.0;
        String notes;

        if (!isLive) {
            status = VerificationStatus.REJECTED;
            notes = "Anti-spoofing liveness check failed (liveness score " + submission.measuredLivenessScore() + " < " + LIVENESS_THRESHOLD + ")";
        } else if (evaluator != null && evaluator.isConfigured()) {
            // Authentic biometric evaluation
            BiometricEvaluationResult evalResult = evaluator.evaluate(submission.idPhotoBytes(), submission.selfieBytes());
            if (!evalResult.evaluated()) {
                status = VerificationStatus.MANUAL_REVIEW_REQUIRED;
                matchScore = evalResult.matchScore();
                notes = "Authentic biometric evaluator inconclusive: " + evalResult.details();
            } else {
                matchScore = evalResult.matchScore();
                if (matchScore >= AUTO_APPROVE_THRESHOLD) {
                    status = VerificationStatus.APPROVED;
                    notes = "Authentic biometric match verified by " + evaluator.getEngineName() + " (score " + matchScore + ")";
                } else if (matchScore >= MANUAL_REVIEW_MIN_THRESHOLD) {
                    status = VerificationStatus.MANUAL_REVIEW_REQUIRED;
                    notes = "Borderline biometric match (score " + matchScore + "); escalated to manual compliance review queue";
                } else {
                    status = VerificationStatus.REJECTED;
                    notes = "Biometric facial mismatch (score " + matchScore + " < " + MANUAL_REVIEW_MIN_THRESHOLD + ")";
                }
            }
        } else {
            // FAIL CLOSED: No synthetic score pass-through. Without an authentic evaluator, escalate to human review queue.
            status = VerificationStatus.MANUAL_REVIEW_REQUIRED;
            notes = "Biometric evaluation engine not configured; authentic facial evaluation requires manual compliance review queue";
        }

        UUID recordId = UUID.randomUUID();
        Instant now = Instant.now();
        FaceVerificationRecord record = new FaceVerificationRecord(
                recordId,
                submission.customerId(),
                true,
                now,
                submission.measuredLivenessScore(),
                matchScore,
                isLive,
                status,
                idPhotoHash,
                selfieHash,
                notes,
                null,
                now,
                null
        );

        memoryQueue.put(recordId, record);

        if (db != null) {
            try {
                db.sql("""
                    INSERT INTO biometric_face_verification
                    (id, customer_id, consent_obtained, consent_timestamp, liveness_score, match_score, is_live, status, id_photo_hash, selfie_hash, reviewer_notes, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(
                            record.id(),
                            record.customerId(),
                            record.consentObtained(),
                            record.consentTimestamp(),
                            record.livenessScore(),
                            record.matchScore(),
                            record.isLive(),
                            record.status().name(),
                            record.idPhotoHash(),
                            record.selfieHash(),
                            record.reviewerNotes(),
                            record.createdAt()
                    )
                    .update();
            } catch (Exception ignored) {}
        }

        return record;
    }

    public List<FaceVerificationRecord> getManualReviewQueue() {
        return memoryQueue.values().stream()
                .filter(r -> r.status() == VerificationStatus.MANUAL_REVIEW_REQUIRED)
                .toList();
    }

    public FaceVerificationRecord reviewCase(UUID recordId, UUID reviewerId, VerificationStatus decision, String notes) {
        FaceVerificationRecord existing = memoryQueue.get(recordId);
        if (existing == null) {
            throw ApiException.notFound("Face verification record not found: " + recordId);
        }
        if (decision == VerificationStatus.MANUAL_REVIEW_REQUIRED) {
            throw ApiException.badRequest("invalid_decision", "Review decision must be APPROVED or REJECTED");
        }

        Instant now = Instant.now();
        FaceVerificationRecord updated = new FaceVerificationRecord(
                existing.id(),
                existing.customerId(),
                existing.consentObtained(),
                existing.consentTimestamp(),
                existing.livenessScore(),
                existing.matchScore(),
                existing.isLive(),
                decision,
                existing.idPhotoHash(),
                existing.selfieHash(),
                notes,
                reviewerId,
                existing.createdAt(),
                now
        );

        memoryQueue.put(recordId, updated);

        if (db != null) {
            try {
                db.sql("""
                    UPDATE biometric_face_verification
                    SET status = ?, reviewer_notes = ?, reviewed_by = ?, reviewed_at = ?
                    WHERE id = ?
                    """)
                    .params(decision.name(), notes, reviewerId, now, recordId)
                    .update();
            } catch (Exception ignored) {}
        }

        return updated;
    }

    private String hashImage(byte[] imageBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(imageBytes);
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash image bytes", e);
        }
    }
}
