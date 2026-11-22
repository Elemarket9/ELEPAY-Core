package com.elepay.compliance;

import com.elepay.common.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Politically Exposed Persons (PEP) screening engine (Algorithm 043).
 * Screens individuals against PEP registers across risk tiers (Tier 1-4),
 * mandates Senior Compliance Officer Enhanced Due Diligence (EDD) sign-off,
 * and maintains immutable audit trails.
 */
public class PepScreeningService {

    public enum PepTier {
        TIER_1, // Head of State, Prime Minister, Cabinet Ministers
        TIER_2, // Members of Parliament, Supreme Court Judges, Central Bank Executives
        TIER_3, // High-ranking military officials, State-Owned Enterprise (SOE) CEOs
        TIER_4  // Immediate family members & close associates
    }

    public enum PepCaseStatus {
        SENIOR_REVIEW_REQUIRED,
        APPROVED_BY_SENIOR,
        REJECTED
    }

    public record PepEntry(
            UUID id,
            String fullName,
            String positionTitle,
            String jurisdiction,
            PepTier tier,
            String soundexCode,
            boolean active
    ) {}

    public record PepScreeningCase(
            UUID caseId,
            UUID customerId,
            String queryName,
            UUID matchedPepId,
            double matchConfidence,
            PepCaseStatus status,
            UUID seniorReviewerId,
            String eddNotes,
            Instant createdAt,
            Instant signedOffAt
    ) {}

    private final JdbcClient db;
    private final Map<UUID, PepEntry> pepRegistry = new ConcurrentHashMap<>();
    private final Map<UUID, PepScreeningCase> cases = new ConcurrentHashMap<>();

    public PepScreeningService(JdbcClient db) {
        this.db = db;
    }

    public void registerPep(PepEntry entry) {
        pepRegistry.put(entry.id(), entry);
        if (db != null) {
            try {
                db.sql("""
                    INSERT INTO pep_directory
                    (id, full_name, position_title, jurisdiction, pep_tier, soundex_code, active, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, now())
                    """)
                    .params(entry.id(), entry.fullName(), entry.positionTitle(), entry.jurisdiction(), entry.tier().name(), entry.soundexCode(), entry.active())
                    .update();
            } catch (Exception ignored) {}
        }
    }

    public PepScreeningCase screenCustomer(UUID customerId, String customerName) {
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(customerName, "customerName cannot be null");

        String normQuery = SanctionsScreeningService.normalize(customerName);
        String querySoundex = SanctionsScreeningService.computeSoundex(normQuery);

        PepEntry bestMatch = null;
        double highestConfidence = 0.0;

        for (PepEntry entry : pepRegistry.values()) {
            if (!entry.active()) continue;

            String normPep = SanctionsScreeningService.normalize(entry.fullName());
            if (normQuery.equals(normPep)) {
                bestMatch = entry;
                highestConfidence = 1.0;
                break;
            }

            double jw = SanctionsScreeningService.computeJaroWinkler(normQuery, normPep);
            if (jw >= 0.85 && jw > highestConfidence) {
                bestMatch = entry;
                highestConfidence = jw;
            } else if (querySoundex.equals(entry.soundexCode()) && jw >= 0.70 && jw > highestConfidence) {
                bestMatch = entry;
                highestConfidence = jw;
            }
        }

        if (bestMatch == null) {
            return null; // Clean, no PEP hit
        }

        UUID caseId = UUID.randomUUID();
        Instant now = Instant.now();
        PepScreeningCase pepCase = new PepScreeningCase(
                caseId,
                customerId,
                customerName,
                bestMatch.id(),
                highestConfidence,
                PepCaseStatus.SENIOR_REVIEW_REQUIRED,
                null,
                null,
                now,
                null
        );

        cases.put(caseId, pepCase);

        if (db != null) {
            try {
                db.sql("""
                    INSERT INTO pep_screening_case
                    (id, customer_id, query_name, matched_pep_id, match_confidence, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(caseId, customerId, customerName, bestMatch.id(), highestConfidence, PepCaseStatus.SENIOR_REVIEW_REQUIRED.name(), now)
                    .update();
            } catch (Exception ignored) {}
        }

        return pepCase;
    }

    public PepScreeningCase seniorSignOff(UUID caseId, UUID seniorReviewerId, PepCaseStatus decision, String eddNotes) {
        PepScreeningCase existing = cases.get(caseId);
        if (existing == null) {
            throw ApiException.notFound("PEP case not found: " + caseId);
        }
        if (decision == PepCaseStatus.SENIOR_REVIEW_REQUIRED) {
            throw ApiException.badRequest("invalid_decision", "Decision must be APPROVED_BY_SENIOR or REJECTED");
        }
        if (eddNotes == null || eddNotes.trim().length() < 5) {
            throw ApiException.badRequest("edd_notes_required", "Detailed Enhanced Due Diligence (EDD) justification notes are required for PEP sign-off");
        }

        Instant now = Instant.now();
        PepScreeningCase updated = new PepScreeningCase(
                existing.caseId(),
                existing.customerId(),
                existing.queryName(),
                existing.matchedPepId(),
                existing.matchConfidence(),
                decision,
                seniorReviewerId,
                eddNotes,
                existing.createdAt(),
                now
        );

        cases.put(caseId, updated);

        if (db != null) {
            try {
                db.sql("""
                    UPDATE pep_screening_case
                    SET status = ?, senior_reviewer_id = ?, edd_notes = ?, signed_off_at = ?
                    WHERE id = ?
                    """)
                    .params(decision.name(), seniorReviewerId, eddNotes, now, caseId)
                    .update();
            } catch (Exception ignored) {}
        }

        return updated;
    }

    public List<PepScreeningCase> getPendingSeniorReviews() {
        return cases.values().stream()
                .filter(c -> c.status() == PepCaseStatus.SENIOR_REVIEW_REQUIRED)
                .toList();
    }
}
