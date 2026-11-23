package com.elepay.lending;

import com.elepay.ledger.Ledger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Bank of Ghana (BOG) Prudential Credit Staging & IFRS 9 Expected Credit Loss (ECL) Engine.
 * Evaluates portfolio delinquency states (Current, OLEM, Substandard, Doubtful, Loss),
 * computes required impairment allowances, and posts balanced provisioning entries to the authoritative ledger.
 */
@Service
public class LoanPrudentialStagingService {

    public enum PrudentialStage {
        STAGE_1_PERFORMING("PERFORMING", 0, 30, 100),            // 1.00% provision
        STAGE_2_OLEM("WATCH", 31, 60, 500),                      // 5.00% provision
        STAGE_3_SUBSTANDARD("SUBSTANDARD", 61, 90, 2000),         // 20.00% provision
        STAGE_3_DOUBTFUL("DOUBTFUL", 91, 180, 5000),             // 50.00% provision
        STAGE_3_LOSS("LOSS", 181, Integer.MAX_VALUE, 10000);     // 100.00% provision

        private final String code;
        private final int minDpd;
        private final int maxDpd;
        private final int provisionBps;

        PrudentialStage(String code, int minDpd, int maxDpd, int provisionBps) {
            this.code = code;
            this.minDpd = minDpd;
            this.maxDpd = maxDpd;
            this.provisionBps = provisionBps;
        }

        public String getCode() { return code; }
        public int getMinDpd() { return minDpd; }
        public int getMaxDpd() { return maxDpd; }
        public int getProvisionBps() { return provisionBps; }

        public static PrudentialStage fromDpd(int dpd) {
            if (dpd <= 30) return STAGE_1_PERFORMING;
            if (dpd <= 60) return STAGE_2_OLEM;
            if (dpd <= 90) return STAGE_3_SUBSTANDARD;
            if (dpd <= 180) return STAGE_3_DOUBTFUL;
            return STAGE_3_LOSS;
        }
    }

    public record ContractStagingAssessment(
            UUID contractId,
            String contractReference,
            int daysPastDue,
            PrudentialStage stage,
            long outstandingExposurePesewas,
            long requiredImpairmentPesewas
    ) {}

    public record PortfolioStagingReport(
            int totalEvaluatedContracts,
            long totalExposurePesewas,
            long totalImpairmentRequiredPesewas,
            int stage1Count,
            int stage2Count,
            int stage3Count,
            List<ContractStagingAssessment> assessments
    ) {}

    private final JdbcClient db;
    private final Ledger ledger;

    public static final String IMPAIRMENT_EXPENSE = "system:lending_impairment_expense:GHS";
    public static final String IMPAIRMENT_RESERVE = "system:lending_impairment_reserve:GHS";

    public LoanPrudentialStagingService(JdbcClient db, Ledger ledger) {
        this.db = db;
        this.ledger = ledger;
    }

    @Transactional
    public PortfolioStagingReport evaluatePortfolioAndPostImpairment(LocalDate asOfDate) {
        LocalDate date = (asOfDate != null) ? asOfDate : LocalDate.now();

        // 1. Retrieve all open contracts with schedules
        var contracts = db.sql("""
            SELECT c.id, c.contract_reference, c.outstanding_principal_pesewas,
                   c.outstanding_interest_pesewas, c.outstanding_penalties_pesewas,
                   c.status, c.delinquency_state
            FROM loan_contract c
            WHERE c.status IN ('ACTIVE', 'DISBURSED', 'OVERDUE')
        """)
                .query((rs, rowNum) -> new Object() {
                    UUID id = rs.getObject("id", UUID.class);
                    String ref = rs.getString("contract_reference");
                    long principal = rs.getLong("outstanding_principal_pesewas");
                    long interest = rs.getLong("outstanding_interest_pesewas");
                    long penalties = rs.getLong("outstanding_penalties_pesewas");
                    String status = rs.getString("status");
                    String delinquencyState = rs.getString("delinquency_state");
                })
                .list();

        List<ContractStagingAssessment> assessments = new ArrayList<>();
        long totalExposure = 0L;
        long totalImpairment = 0L;
        int s1Count = 0;
        int s2Count = 0;
        int s3Count = 0;

        for (var c : contracts) {
            // Find max DPD across unpaid installments
            Integer maxDpd = db.sql("""
                SELECT COALESCE(MAX(:date - due_date), 0)
                FROM loan_repayment_schedule
                WHERE contract_id = :id AND status != 'PAID' AND due_date < :date
            """)
                    .param("date", date)
                    .param("id", c.id)
                    .query(Integer.class)
                    .optional().orElse(0);

            if (maxDpd < 0) maxDpd = 0;

            PrudentialStage stage = PrudentialStage.fromDpd(maxDpd);
            long exposure = c.principal + c.interest + c.penalties;
            long impairment = (exposure * stage.getProvisionBps()) / 10000L;

            totalExposure += exposure;
            totalImpairment += impairment;

            if (stage == PrudentialStage.STAGE_1_PERFORMING) s1Count++;
            else if (stage == PrudentialStage.STAGE_2_OLEM) s2Count++;
            else s3Count++;

            // Update contract delinquency state & DPD
            db.sql("""
                UPDATE loan_contract
                SET delinquency_state = :state,
                    days_past_due = :dpd,
                    updated_at = now()
                WHERE id = :id
            """)
                    .param("state", stage.getCode())
                    .param("dpd", maxDpd)
                    .param("id", c.id)
                    .update();

            assessments.add(new ContractStagingAssessment(c.id, c.ref, maxDpd, stage, exposure, impairment));
        }

        // 2. Post impairment adjustment to Double-Entry Ledger if totalImpairment > 0
        if (totalImpairment > 0) {
            ledger.openSystem(IMPAIRMENT_EXPENSE, "clearing", "GHS", true);
            ledger.openSystem(IMPAIRMENT_RESERVE, "clearing", "GHS", true);

            var posting = Ledger.Posting.of(
                    "lending_ecl",
                    "ECL-RUN-" + date,
                    "PORTFOLIO_IMPAIRMENT",
                    List.of(
                            Ledger.Leg.debit(IMPAIRMENT_EXPENSE, totalImpairment),
                            Ledger.Leg.credit(IMPAIRMENT_RESERVE, totalImpairment)
                    )
            ).describedAs("BoG Prudential IFRS 9 ECL Impairment Provision as of " + date);

            ledger.post(posting);
        }

        return new PortfolioStagingReport(
                contracts.size(), totalExposure, totalImpairment,
                s1Count, s2Count, s3Count, assessments
        );
    }
}
