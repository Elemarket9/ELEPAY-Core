package com.elepay.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplianceAlgorithmsTest {

    // --- 040: Ghana Card Matching ---
    @Test
    @DisplayName("040: Validates Ghana Card format and check digit, and performs simulated NIA matching")
    void testGhanaCardVerification() {
        GhanaCardVerificationService service = new GhanaCardVerificationService(null, new GhanaCardVerificationService.SimulatedNiaClient());

        // Calculate a valid check digit for "123456789"
        int checkDigit = GhanaCardVerificationService.calculateLuhnCheckDigit("123456789");
        String validId = "GHA-123456789-" + checkDigit;
        String invalidId = "GHA-123456789-9"; // Wrong check digit

        assertThat(GhanaCardVerificationService.isValidFormat(validId)).isTrue();
        if (checkDigit != 9) {
            assertThat(GhanaCardVerificationService.isValidFormat(invalidId)).isFalse();
        }

        UUID customerId = UUID.randomUUID();
        var result = service.verify(customerId, validId, "Kwame Mensah", LocalDate.of(1990, 5, 15), "M");
        assertThat(result.status()).isEqualTo(GhanaCardVerificationService.Status.VERIFIED);
        assertThat(result.simulated()).isTrue();
        assertThat(result.simulationProvider()).isEqualTo("SIMULATED_NIA_SANDBOX");

        // Test distinguishing UNAVAILABLE from verified/mismatched
        int check999 = GhanaCardVerificationService.calculateLuhnCheckDigit("999000111");
        String unavailableId = "GHA-999000111-" + check999;
        var unavailResult = service.verify(customerId, unavailableId, "Kwame Mensah", LocalDate.of(1990, 5, 15), "M");
        assertThat(unavailResult.status()).isEqualTo(GhanaCardVerificationService.Status.UNAVAILABLE);
    }

    // --- 041: Face Matching and Liveness ---
    @Test
    @DisplayName("041: Enforces consent, checks anti-spoofing liveness, and routes borderline matches to manual review")
    void testFaceMatchingAndLiveness() {
        FaceMatchingLivenessService service = new FaceMatchingLivenessService(null);
        UUID customerId = UUID.randomUUID();
        byte[] dummyPhoto = "photo-bytes-id".getBytes();
        byte[] dummySelfie = "selfie-bytes-user".getBytes();

        // 1. Consent required
        var noConsent = new FaceMatchingLivenessService.BiometricSubmission(
                customerId, false, dummyPhoto, dummySelfie, 0.95, 0.95
        );
        assertThatThrownBy(() -> service.processBiometrics(noConsent))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Explicit biometric consent must be granted");

        // 2. Anti-spoofing rejection when liveness is low (< 0.80)
        var spoof = new FaceMatchingLivenessService.BiometricSubmission(
                customerId, true, dummyPhoto, dummySelfie, 0.45, 0.95
        );
        var spoofRecord = service.processBiometrics(spoof);
        assertThat(spoofRecord.status()).isEqualTo(FaceMatchingLivenessService.VerificationStatus.REJECTED);
        assertThat(spoofRecord.isLive()).isFalse();

        // 3. Auto-approval when live and high match score (>= 0.80)
        var autoApproved = new FaceMatchingLivenessService.BiometricSubmission(
                customerId, true, dummyPhoto, dummySelfie, 0.95, 0.88
        );
        var approvedRecord = service.processBiometrics(autoApproved);
        assertThat(approvedRecord.status()).isEqualTo(FaceMatchingLivenessService.VerificationStatus.APPROVED);

        // 4. Borderline match (0.60 to 0.79) routes to human review queue
        var borderline = new FaceMatchingLivenessService.BiometricSubmission(
                customerId, true, dummyPhoto, dummySelfie, 0.92, 0.72
        );
        var reviewRecord = service.processBiometrics(borderline);
        assertThat(reviewRecord.status()).isEqualTo(FaceMatchingLivenessService.VerificationStatus.MANUAL_REVIEW_REQUIRED);

        // Review queue inspection and analyst approval
        var queue = service.getManualReviewQueue();
        assertThat(queue).anyMatch(r -> r.id().equals(reviewRecord.id()));

        UUID analystId = UUID.randomUUID();
        var reviewed = service.reviewCase(reviewRecord.id(), analystId, FaceMatchingLivenessService.VerificationStatus.APPROVED, "Manual comparison verified customer features");
        assertThat(reviewed.status()).isEqualTo(FaceMatchingLivenessService.VerificationStatus.APPROVED);
        assertThat(reviewed.reviewedBy()).isEqualTo(analystId);
    }

    // --- 042: Fuzzy Sanctions Screening ---
    @Test
    @DisplayName("042: Screens names with exact, Jaro-Winkler, and Soundex matching against sanctions lists")
    void testFuzzySanctionsScreening() {
        SanctionsScreeningService service = new SanctionsScreeningService(null);
        UUID sanctionId = UUID.randomUUID();

        // Register sanctioned individual
        service.addSanctionEntry(new SanctionsScreeningService.SanctionEntry(
                sanctionId,
                "UN_SANCTIONS",
                "2026.1",
                "Viktor Anatolyevich Bout",
                "INDIVIDUAL",
                SanctionsScreeningService.computeSoundex("Viktor Bout"),
                "RU",
                "UN Security Council Resolution",
                true
        ));

        // 1. Exact match
        var exactMatches = service.screenName("Viktor Anatolyevich Bout");
        assertThat(exactMatches).isNotEmpty();
        assertThat(exactMatches.get(0).matchType()).isEqualTo(SanctionsScreeningService.MatchType.EXACT);

        // 2. Fuzzy Jaro-Winkler match
        var fuzzyMatches = service.screenName("Victor Anatolyevich Bout");
        assertThat(fuzzyMatches).isNotEmpty();
        assertThat(fuzzyMatches.get(0).similarityScore()).isGreaterThanOrEqualTo(0.85);

        // 3. Alert creation & resolution
        UUID customerId = UUID.randomUUID();
        var alert = service.screenAndAlert(customerId, "Victor Anatolyevich Bout");
        assertThat(alert).isNotNull();
        assertThat(alert.disposition()).isEqualTo(SanctionsScreeningService.AlertDisposition.PENDING_REVIEW);

        UUID analystId = UUID.randomUUID();
        var resolved = service.resolveAlert(alert.alertId(), analystId, SanctionsScreeningService.AlertDisposition.CONFIRMED_MATCH, "Positive identification confirmed");
        assertThat(resolved.disposition()).isEqualTo(SanctionsScreeningService.AlertDisposition.CONFIRMED_MATCH);
    }

    // --- 043: PEP Screening ---
    @Test
    @DisplayName("043: Screens PEP registry and enforces Senior Compliance Officer EDD sign-off")
    void testPepScreeningAndSeniorSignOff() {
        PepScreeningService service = new PepScreeningService(null);
        UUID pepId = UUID.randomUUID();

        service.registerPep(new PepScreeningService.PepEntry(
                pepId,
                "Alban Sumana Kingsford Bagbin",
                "Speaker of Parliament",
                "GH",
                PepScreeningService.PepTier.TIER_2,
                SanctionsScreeningService.computeSoundex("Alban Bagbin"),
                true
        ));

        UUID customerId = UUID.randomUUID();
        var pepCase = service.screenCustomer(customerId, "Alban Bagbin");
        assertThat(pepCase).isNotNull();
        assertThat(pepCase.status()).isEqualTo(PepScreeningService.PepCaseStatus.SENIOR_REVIEW_REQUIRED);

        // Senior sign-off requires meaningful EDD notes
        UUID seniorOfficerId = UUID.randomUUID();
        assertThatThrownBy(() -> service.seniorSignOff(pepCase.caseId(), seniorOfficerId, PepScreeningService.PepCaseStatus.APPROVED_BY_SENIOR, "ok"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Enhanced Due Diligence (EDD) justification notes are required");

        var approvedCase = service.seniorSignOff(pepCase.caseId(), seniorOfficerId, PepScreeningService.PepCaseStatus.APPROVED_BY_SENIOR, "Source of wealth verified via parliamentary salary disclosures; EDD completed.");
        assertThat(approvedCase.status()).isEqualTo(PepScreeningService.PepCaseStatus.APPROVED_BY_SENIOR);
        assertThat(approvedCase.seniorReviewerId()).isEqualTo(seniorOfficerId);
    }

    // --- 044: Structuring Detection ---
    @Test
    @DisplayName("044: Detects smurfing transaction clusters just below CTR threshold")
    void testStructuringDetection() {
        StructuringDetectionService service = new StructuringDetectionService(null);
        UUID customerId = UUID.randomUUID();
        Instant now = Instant.now();

        // CTR threshold is 20,000 GHS = 2,000,000 pesewas.
        // Customer executes 3 transactions of 1,900,000 pesewas (19,000 GHS) within 6 hours.
        List<StructuringDetectionService.TransactionRecord> txns = List.of(
                new StructuringDetectionService.TransactionRecord(UUID.randomUUID(), customerId, 1_900_000L, "GHS", now.minus(Duration.ofHours(5))),
                new StructuringDetectionService.TransactionRecord(UUID.randomUUID(), customerId, 1_900_000L, "GHS", now.minus(Duration.ofHours(3))),
                new StructuringDetectionService.TransactionRecord(UUID.randomUUID(), customerId, 1_900_000L, "GHS", now.minus(Duration.ofHours(1)))
        );

        var alert = service.evaluateCustomerTransactions(customerId, txns);
        assertThat(alert).isNotNull();
        assertThat(alert.transactionCount()).isEqualTo(3);
        assertThat(alert.aggregatePesewas()).isEqualTo(5_700_000L);
        assertThat(alert.structuringConfidence()).isGreaterThanOrEqualTo(0.75);
    }

    // --- 045: AML Transaction Monitoring ---
    @Test
    @DisplayName("045: Detects rapid flow-through, velocity spikes, high-risk countries, and dormant reactivation")
    void testAmlMonitoringRules() {
        AmlMonitoringService service = new AmlMonitoringService(null);
        UUID customerId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. High-risk country transfer ("KP" = North Korea)
        var highRiskEvent = new AmlMonitoringService.AmlEvent(
                UUID.randomUUID(), customerId, "OUTFLOW", 500_000L, "GHS", "KP", now, 100_000L, now
        );
        var highRiskCases = service.evaluateTransaction(highRiskEvent, null);
        assertThat(highRiskCases).anyMatch(c -> c.triggeredRule() == AmlMonitoringService.AmlRule.HIGH_RISK_JURISDICTION);

        // 2. Rapid movement (Inflow of 10,000 GHS followed 5 minutes later by 9,000 GHS outflow)
        var inflow = new AmlMonitoringService.AmlEvent(
                UUID.randomUUID(), customerId, "INFLOW", 1_000_000L, "GHS", "GH", now.minus(Duration.ofDays(1)), 100_000L, now.minus(Duration.ofMinutes(5))
        );
        var rapidOutflow = new AmlMonitoringService.AmlEvent(
                UUID.randomUUID(), customerId, "OUTFLOW", 900_000L, "GHS", "GH", now.minus(Duration.ofDays(1)), 100_000L, now
        );
        var rapidCases = service.evaluateTransaction(rapidOutflow, List.of(inflow));
        assertThat(rapidCases).anyMatch(c -> c.triggeredRule() == AmlMonitoringService.AmlRule.RAPID_MOVEMENT);

        // 3. Case lifecycle progression
        var amlCase = rapidCases.get(0);
        UUID analystId = UUID.randomUUID();
        var escalated = service.updateCaseStatus(amlCase.id(), AmlMonitoringService.CaseStatus.ESCALATED_STR, analystId);
        assertThat(escalated.status()).isEqualTo(AmlMonitoringService.CaseStatus.ESCALATED_STR);
        assertThat(escalated.assignedTo()).isEqualTo(analystId);
    }

    // --- 046: Alert Prioritization ---
    @Test
    @DisplayName("046: Prioritizes cases by rule severity, financial exposure, confidence, and age")
    void testAlertPrioritizationQueue() {
        AlertPrioritizationService service = new AlertPrioritizationService(null);
        Instant twoHoursAgo = Instant.now().minus(Duration.ofHours(2));

        // High priority: Critical severity + large exposure (> 100k GHS)
        var case1 = service.enqueueCase(
                UUID.randomUUID(), "CASE-001", "SANCTIONS", UUID.randomUUID(),
                "CRITICAL", 15_000_000L, 0.95, "SANCTIONS_EXACT_MATCH", twoHoursAgo
        );

        // Low priority: Low severity + low exposure (< 5k GHS)
        var case2 = service.enqueueCase(
                UUID.randomUUID(), "CASE-002", "VELOCITY", UUID.randomUUID(),
                "LOW", 200_000L, 0.50, "VELOCITY_MINOR", Instant.now()
        );

        assertThat(case1.breakdown().calculatedTier()).isEqualTo(AlertPrioritizationService.PriorityTier.CRITICAL);
        assertThat(case2.breakdown().calculatedTier()).isEqualTo(AlertPrioritizationService.PriorityTier.LOW);

        var queue = service.getPrioritizedQueue();
        assertThat(queue.get(0).caseId()).isEqualTo(case1.caseId());
        assertThat(queue.get(1).caseId()).isEqualTo(case2.caseId());
    }

    // --- 047: Regulatory Report Generation ---
    @Test
    @DisplayName("047: Generates BoG EMI returns and FIC CTR/STR filings with maker-checker approval requirement")
    void testRegulatoryReporting() {
        RegulatoryReportingService service = new RegulatoryReportingService(null);
        UUID preparerId = UUID.randomUUID();
        UUID complianceOfficerId = UUID.randomUUID();

        // 1. Bank of Ghana EMI Monthly Return
        var bogMetrics = new RegulatoryReportingService.BogEmiMetrics(
                150_000L, 25_000_000_00L, 45_000L, 26_000_000_00L, 3
        );
        var bogReport = service.generateBogEmiMonthlyReport(YearMonth.of(2026, 10), bogMetrics, preparerId);
        assertThat(bogReport.status()).isEqualTo(RegulatoryReportingService.ReportStatus.DRAFT);
        assertThat(bogReport.contentPayload()).contains("Bank of Ghana");
        assertThat(bogReport.contentPayload()).contains("Payment Systems and Services Act");

        // 2. Maker-checker enforcement: Preparer cannot approve own report
        assertThatThrownBy(() -> service.approveReport(bogReport.id(), preparerId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Maker-checker violation");

        // 3. Approved by distinct compliance officer
        var approvedBog = service.approveReport(bogReport.id(), complianceOfficerId);
        assertThat(approvedBog.status()).isEqualTo(RegulatoryReportingService.ReportStatus.APPROVED_FOR_FILING);
        assertThat(approvedBog.approvedBy()).isEqualTo(complianceOfficerId);

        // 4. FIC CTR Report
        var ctrEntry = new RegulatoryReportingService.CtrEntry(
                UUID.randomUUID(), UUID.randomUUID(), "Kofi Mensah", 2_500_000L, "MTN MoMo", Instant.now()
        );
        var ctrReport = service.generateFicCtrReport("2026-10", List.of(ctrEntry), preparerId);
        assertThat(ctrReport.contentPayload()).contains("Financial Intelligence Centre");
        assertThat(ctrReport.contentPayload()).contains("Cash Transaction Report (CTR)");
    }

    // --- 048: Retention and Redaction ---
    @Test
    @DisplayName("048: Enforces 5-year statutory retention, respects legal holds, and executes controlled PII redaction")
    void testRetentionAndRedaction() {
        RetentionRedactionService service = new RetentionRedactionService(null);
        UUID customerId = UUID.randomUUID();
        UUID officerId = UUID.randomUUID();

        // Case 1: Active legal hold blocks any redaction
        var hold = service.applyLegalHold(customerId, "REF-POLICE-001", "Court subpoena pending investigation", officerId);
        assertThat(service.hasActiveLegalHold(customerId)).isTrue();

        Instant closedTenYearsAgo = Instant.now().minus(Duration.ofDays(3650));
        assertThatThrownBy(() -> service.redactCustomerPii(customerId, closedTenYearsAgo, officerId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Cannot redact records for customer");

        // Case 2: Release legal hold, but retention period (60 months) has NOT expired
        service.releaseLegalHold(hold.holdId());
        assertThat(service.hasActiveLegalHold(customerId)).isFalse();

        Instant closedOneYearAgo = Instant.now().minus(Duration.ofDays(365));
        assertThatThrownBy(() -> service.redactCustomerPii(customerId, closedOneYearAgo, officerId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("statutory retention period (60 months) has not expired");

        // Case 3: Retention period expired (> 60 months) and no legal hold -> Successful PII redaction
        var redactionResult = service.redactCustomerPii(customerId, closedTenYearsAgo, officerId);
        assertThat(redactionResult.successful()).isTrue();
        assertThat(redactionResult.redactedFields()).contains("full_name", "phone_number", "id_number");

        assertThat(service.getRedactionAuditTrail()).isNotEmpty();
    }
}
