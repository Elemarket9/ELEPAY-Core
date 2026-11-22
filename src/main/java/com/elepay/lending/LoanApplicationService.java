package com.elepay.lending;

import com.elepay.identity.Actor;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationService {

    private final JdbcClient db;
    private final LoanProductService productService;

    public LoanApplicationService(JdbcClient db, LoanProductService productService) {
        this.db = db;
        this.productService = productService;
    }

    public record ApplicationSubmission(
            UUID applicantId,
            String applicantType,
            UUID productId,
            long requestedAmountPesewas,
            int tenorDays,
            String statedPurpose,
            long monthlyTurnoverPesewas,
            String invoiceReference
    ) {}

    public record ScoreCardEvaluation(
            int score,
            String riskBand,
            long maxApprovedPesewas,
            String decision,
            String reason
    ) {}

    public record LoanApplication(
            UUID id,
            UUID applicantId,
            String applicantType,
            UUID productId,
            long requestedAmountPesewas,
            int tenorDays,
            String statedPurpose,
            long monthlyTurnoverPesewas,
            String invoiceReference,
            String status,
            int creditScore,
            long maxApprovedPesewas,
            String riskBand,
            String decisionReason,
            OffsetDateTime submittedAt,
            OffsetDateTime decidedAt
    ) {}

    public ScoreCardEvaluation evaluateScoreCard(LoanProductService.LoanProduct product, ApplicationSubmission sub) {
        if (sub.requestedAmountPesewas() < product.minAmountPesewas()) {
            return new ScoreCardEvaluation(350, "REJECT", 0L, "REJECTED",
                    "Requested amount " + sub.requestedAmountPesewas() + " is below product minimum " + product.minAmountPesewas());
        }
        if (sub.requestedAmountPesewas() > product.maxAmountPesewas()) {
            return new ScoreCardEvaluation(400, "REJECT", 0L, "REJECTED",
                    "Requested amount " + sub.requestedAmountPesewas() + " exceeds product ceiling " + product.maxAmountPesewas());
        }
        if (sub.tenorDays() < product.minTenorDays() || sub.tenorDays() > product.maxTenorDays()) {
            return new ScoreCardEvaluation(420, "REJECT", 0L, "REJECTED",
                    "Tenor " + sub.tenorDays() + " days is outside product bounds [" + product.minTenorDays() + ", " + product.maxTenorDays() + "]");
        }

        // Turnover capacity check: Debt service capacity based on turnover
        // Monthly turnover should comfortably support the monthly repayment obligation
        if (sub.monthlyTurnoverPesewas() <= 0) {
            return new ScoreCardEvaluation(300, "REJECT", 0L, "REJECTED", "Monthly turnover must be strictly positive");
        }

        double turnoverRatio = (double) sub.requestedAmountPesewas() / sub.monthlyTurnoverPesewas();
        int baseScore = 650;

        // Favorable ratio: loan <= 50% of monthly turnover adds points
        if (turnoverRatio <= 0.5) {
            baseScore += 100;
        } else if (turnoverRatio <= 1.0) {
            baseScore += 50;
        } else if (turnoverRatio <= 2.0) {
            baseScore += 0;
        } else if (turnoverRatio <= 3.0) {
            baseScore -= 100;
        } else {
            baseScore -= 200;
        }

        // Invoice factoring bonus if invoice attached
        if ("INVOICE_FACTORING".equals(product.productType()) && sub.invoiceReference() != null && !sub.invoiceReference().isBlank()) {
            baseScore += 50;
        }

        // Bounded score between 300 and 850
        int finalScore = Math.max(300, Math.min(850, baseScore));

        String riskBand;
        long maxApproved;
        String decision;
        String reason;

        if (finalScore >= 700) {
            riskBand = "LOW";
            maxApproved = Math.min(product.maxAmountPesewas(), sub.requestedAmountPesewas());
            decision = "APPROVED";
            reason = "Excellent cash-flow profile, approved for 100% of requested amount";
        } else if (finalScore >= 600) {
            riskBand = "MEDIUM";
            maxApproved = Math.min(product.maxAmountPesewas(), (long) (sub.requestedAmountPesewas() * 0.85));
            decision = "APPROVED";
            reason = "Satisfactory cash-flow profile, approved with 15% debt containment cushion";
        } else if (finalScore >= 500) {
            riskBand = "HIGH";
            maxApproved = Math.min(product.maxAmountPesewas(), (long) (sub.requestedAmountPesewas() * 0.60));
            decision = "APPROVED";
            reason = "High debt-to-turnover ratio, approved with 40% risk-adjusted reduction";
        } else {
            riskBand = "REJECT";
            maxApproved = 0L;
            decision = "REJECTED";
            reason = "Underwriting criteria not met: insufficient monthly turnover relative to loan size";
        }

        return new ScoreCardEvaluation(finalScore, riskBand, maxApproved, decision, reason);
    }

    @Transactional
    public LoanApplication submitAndEvaluate(ApplicationSubmission sub) {
        LoanProductService.LoanProduct product = productService.getProductById(sub.productId())
                .orElseThrow(() -> new IllegalArgumentException("Loan product not found: " + sub.productId()));

        ScoreCardEvaluation eval = evaluateScoreCard(product, sub);

        // Applicant-supplied turnover is a recommendation, never verified approval.
        if ("APPROVED".equals(eval.decision())) eval = new ScoreCardEvaluation(eval.score(), eval.riskBand(), eval.maxApprovedPesewas(), "REVIEW_REQUIRED", "Verified turnover and credit review required");
        UUID applicationId = UUID.randomUUID();
        db.sql("""
            INSERT INTO loan_application
            (id, applicant_id, applicant_type, product_id, requested_amount_pesewas,
             tenor_days, stated_purpose, monthly_turnover_pesewas, invoice_reference,
             status, credit_score, max_approved_pesewas, risk_band, decision_reason,
             submitted_at, decided_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
        """).params(applicationId, sub.applicantId(), sub.applicantType(), sub.productId(),
                sub.requestedAmountPesewas(), sub.tenorDays(), sub.statedPurpose(),
                sub.monthlyTurnoverPesewas(), sub.invoiceReference(), eval.decision(),
                eval.score(), eval.maxApprovedPesewas(), eval.riskBand(), eval.reason())
        .update();

        return new LoanApplication(
                applicationId, sub.applicantId(), sub.applicantType(), sub.productId(),
                sub.requestedAmountPesewas(), sub.tenorDays(), sub.statedPurpose(),
                sub.monthlyTurnoverPesewas(), sub.invoiceReference(), eval.decision(),
                eval.score(), eval.maxApprovedPesewas(), eval.riskBand(), eval.reason(),
                OffsetDateTime.now(), OffsetDateTime.now()
        );
    }

    @Transactional
    public LoanApplication approveReviewed(UUID id, String evidenceReference) {
        Actor actor = Actor.current(); actor.requireOps();
        if (evidenceReference == null || evidenceReference.isBlank() || evidenceReference.length() > 256) throw new IllegalArgumentException("Verified credit evidence reference required");
        int changed = db.sql("UPDATE loan_application SET status = 'APPROVED', reviewed_by = ?, review_evidence = ?, decided_at = now() WHERE id = ? AND status = 'REVIEW_REQUIRED' AND max_approved_pesewas > 0")
                .params(actor.subject(), evidenceReference, id).update();
        if (changed != 1) throw new IllegalStateException("Application cannot be approved");
        return getApplication(id).orElseThrow();
    }

    public Optional<LoanApplication> getApplication(UUID id) {
        return db.sql("""
            SELECT id, applicant_id, applicant_type, product_id, requested_amount_pesewas,
                   tenor_days, stated_purpose, monthly_turnover_pesewas, invoice_reference,
                   status, credit_score, max_approved_pesewas, risk_band, decision_reason,
                   submitted_at, decided_at
            FROM loan_application
            WHERE id = ?
        """).param(id)
        .query((rs, rowNum) -> new LoanApplication(
                rs.getObject("id", UUID.class),
                rs.getObject("applicant_id", UUID.class),
                rs.getString("applicant_type"),
                rs.getObject("product_id", UUID.class),
                rs.getLong("requested_amount_pesewas"),
                rs.getInt("tenor_days"),
                rs.getString("stated_purpose"),
                rs.getLong("monthly_turnover_pesewas"),
                rs.getString("invoice_reference"),
                rs.getString("status"),
                rs.getInt("credit_score"),
                rs.getLong("max_approved_pesewas"),
                rs.getString("risk_band"),
                rs.getString("decision_reason"),
                rs.getObject("submitted_at", OffsetDateTime.class),
                rs.getObject("decided_at", OffsetDateTime.class)
        )).optional();
    }
}
