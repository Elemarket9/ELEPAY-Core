package com.elepay.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.ApiException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GhanaCardAndBiometricVerificationTest {

    @Test
    @DisplayName("GhanaCard verification fails closed with UNAVAILABLE when NIA client is not configured")
    void testGhanaCardUnconfiguredFailsClosed() {
        GhanaCardVerificationService service = new GhanaCardVerificationService(null);
        UUID customerId = UUID.randomUUID();

        int checkDigit = GhanaCardVerificationService.calculateLuhnCheckDigit("712345678");
        String validCard = "GHA-712345678-" + checkDigit;

        var result = service.verify(customerId, validCard, "Ama Mensah", LocalDate.of(1995, 3, 22), "F");

        assertThat(result.status()).isEqualTo(GhanaCardVerificationService.Status.UNAVAILABLE);
        assertThat(result.confidenceScore()).isEqualTo(0.0);
        assertThat(result.simulated()).isFalse();
        assertThat(result.simulationProvider()).isEqualTo("UNCONFIGURED_NIA");
        assertThat(result.details()).contains("not configured");
    }

    @Test
    @DisplayName("GhanaCard format validation strictly enforces 9 digits and Luhn check digit")
    void testGhanaCardFormatValidation() {
        // Valid card check digits
        int cd1 = GhanaCardVerificationService.calculateLuhnCheckDigit("111222333");
        assertThat(GhanaCardVerificationService.isValidFormat("GHA-111222333-" + cd1)).isTrue();

        // Invalid cases
        assertThat(GhanaCardVerificationService.isValidFormat(null)).isFalse();
        assertThat(GhanaCardVerificationService.isValidFormat("")).isFalse();
        assertThat(GhanaCardVerificationService.isValidFormat("GHA-11122233-1")).isFalse(); // 8 digits
        assertThat(GhanaCardVerificationService.isValidFormat("GHA-1112223333-1")).isFalse(); // 10 digits
        assertThat(GhanaCardVerificationService.isValidFormat("XYZ-111222333-1")).isFalse(); // Wrong prefix
        assertThat(GhanaCardVerificationService.isValidFormat("GHA-111222333-" + ((cd1 + 1) % 10))).isFalse(); // Wrong check digit
    }

    @Test
    @DisplayName("GhanaCard rejects invalid card format with bad request exception")
    void testGhanaCardInvalidFormatThrowsBadRequest() {
        GhanaCardVerificationService service = new GhanaCardVerificationService(null);
        UUID customerId = UUID.randomUUID();

        assertThatThrownBy(() -> service.verify(customerId, "INVALID-CARD", "Ama Mensah", LocalDate.of(1995, 3, 22), "F"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid Ghana Card number format or check digit");
    }

    @Test
    @DisplayName("FaceMatching requires explicit customer consent and non-empty image bytes")
    void testFaceMatchingConsentAndImageValidation() {
        FaceMatchingLivenessService service = new FaceMatchingLivenessService(null);
        UUID customerId = UUID.randomUUID();
        byte[] photo = "valid-photo-data".getBytes();
        byte[] selfie = "valid-selfie-data".getBytes();

        // Missing consent
        var noConsent = new FaceMatchingLivenessService.BiometricSubmission(customerId, false, photo, selfie, 0.99);
        assertThatThrownBy(() -> service.processBiometrics(noConsent))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Explicit biometric consent must be granted");

        // Missing ID photo
        var missingIdPhoto = new FaceMatchingLivenessService.BiometricSubmission(customerId, true, null, selfie, 0.99);
        assertThatThrownBy(() -> service.processBiometrics(missingIdPhoto))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Both ID photo and selfie image bytes are required");

        // Missing selfie
        var missingSelfie = new FaceMatchingLivenessService.BiometricSubmission(customerId, true, photo, new byte[0], 0.99);
        assertThatThrownBy(() -> service.processBiometrics(missingSelfie))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Both ID photo and selfie image bytes are required");
    }

    @Test
    @DisplayName("FaceMatching fails closed with MANUAL_REVIEW_REQUIRED when no evaluator is configured")
    void testFaceMatchingFailsClosedWithoutEvaluator() {
        FaceMatchingLivenessService service = new FaceMatchingLivenessService(null);
        UUID customerId = UUID.randomUUID();
        byte[] photo = "photo-sample-1".getBytes();
        byte[] selfie = "selfie-sample-1".getBytes();

        var submission = new FaceMatchingLivenessService.BiometricSubmission(customerId, true, photo, selfie, 0.95);
        var record = service.processBiometrics(submission);

        assertThat(record.status()).isEqualTo(FaceMatchingLivenessService.VerificationStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(record.isLive()).isTrue();
        assertThat(record.matchScore()).isEqualTo(0.0);
        assertThat(record.reviewerNotes()).contains("not configured");

        // Verify record is placed into manual review queue
        var queue = service.getManualReviewQueue();
        assertThat(queue).anyMatch(r -> r.id().equals(record.id()));
    }

    @Test
    @DisplayName("FaceMatching authentic evaluation approves high match and rejects low liveness")
    void testFaceMatchingWithAuthenticEvaluator() {
        FaceMatchingLivenessService.BiometricEvaluator evaluator = (idPhoto, selfie) ->
                new FaceMatchingLivenessService.BiometricEvaluationResult(true, 0.91, "HIGH", "Authentic 91% match");

        FaceMatchingLivenessService service = new FaceMatchingLivenessService(null, evaluator);
        UUID customerId = UUID.randomUUID();
        byte[] photo = "sample-photo".getBytes();
        byte[] selfie = "sample-selfie".getBytes();

        // 1. High match with genuine liveness -> APPROVED
        var approvedSubmission = new FaceMatchingLivenessService.BiometricSubmission(customerId, true, photo, selfie, 0.85);
        var approvedRecord = service.processBiometrics(approvedSubmission);
        assertThat(approvedRecord.status()).isEqualTo(FaceMatchingLivenessService.VerificationStatus.APPROVED);
        assertThat(approvedRecord.matchScore()).isEqualTo(0.91);

        // 2. Spoof attempt (liveness 0.75 < 0.80) -> REJECTED regardless of evaluator match
        var spoofSubmission = new FaceMatchingLivenessService.BiometricSubmission(customerId, true, photo, selfie, 0.75);
        var spoofRecord = service.processBiometrics(spoofSubmission);
        assertThat(spoofRecord.status()).isEqualTo(FaceMatchingLivenessService.VerificationStatus.REJECTED);
        assertThat(spoofRecord.isLive()).isFalse();
    }

    @Test
    @DisplayName("Manual review queue allows analysts to review, approve, or reject cases")
    void testManualReviewWorkflow() {
        FaceMatchingLivenessService service = new FaceMatchingLivenessService(null);
        UUID customerId = UUID.randomUUID();
        var sub = new FaceMatchingLivenessService.BiometricSubmission(customerId, true, "p".getBytes(), "s".getBytes(), 0.90);
        var record = service.processBiometrics(sub);

        UUID analystId = UUID.randomUUID();

        // Analyst decision cannot be MANUAL_REVIEW_REQUIRED
        assertThatThrownBy(() -> service.reviewCase(record.id(), analystId, FaceMatchingLivenessService.VerificationStatus.MANUAL_REVIEW_REQUIRED, "invalid"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Review decision must be APPROVED or REJECTED");

        // Analyst approval
        var approved = service.reviewCase(record.id(), analystId, FaceMatchingLivenessService.VerificationStatus.APPROVED, "Verified by human compliance officer");
        assertThat(approved.status()).isEqualTo(FaceMatchingLivenessService.VerificationStatus.APPROVED);
        assertThat(approved.reviewedBy()).isEqualTo(analystId);
        assertThat(approved.reviewedAt()).isNotNull();

        // Now the case should not be in the manual review queue
        assertThat(service.getManualReviewQueue()).noneMatch(r -> r.id().equals(record.id()));
    }
}
