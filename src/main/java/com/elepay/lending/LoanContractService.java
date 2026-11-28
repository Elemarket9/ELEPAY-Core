package com.elepay.lending;

import com.elepay.ledger.Ledger;
import com.elepay.identity.Actor;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanContractService {

    private final JdbcClient db;
    private final LoanProductService productService;
    private final LendingConfigService configService;
    private final Ledger ledger;
    public static final String FUNDING = "system:lending_funding:GHS";


    public LoanContractService(JdbcClient db, LoanProductService productService, LendingConfigService configService, Ledger ledger) {
        this.db = db;
        this.productService = productService;
        this.configService = configService;
        this.ledger = ledger;
    }

    public record Installment(
            UUID id,
            UUID contractId,
            int installmentNumber,
            LocalDate dueDate,
            long principalDuePesewas,
            long interestDuePesewas,
            long feesDuePesewas,
            long principalPaidPesewas,
            long interestPaidPesewas,
            long feesPaidPesewas,
            long penaltyPaidPesewas,
            String status,
            OffsetDateTime paidAt
    ) {}

    public record LoanContract(
            UUID id,
            UUID applicationId,
            String contractReference,
            UUID borrowerId,
            UUID productId,
            long principalPesewas,
            long originationFeePesewas,
            long totalInterestPesewas,
            long totalRepaymentPesewas,
            long outstandingPrincipalPesewas,
            long outstandingInterestPesewas,
            long outstandingFeesPesewas,
            long outstandingPenaltiesPesewas,
            int tenorDays,
            int dailySweepBps,
            OffsetDateTime disbursedAt,
            LocalDate startDate,
            LocalDate maturityDate,
            String status,
            String delinquencyState,
            int daysPastDue,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    record AppRow(UUID id, UUID applicantId, UUID productId, long approvedAmount, int tenorDays, String status) {}

    @Transactional
    public LoanContract generateContract(UUID applicationId) {
        var app = db.sql("""
            SELECT id, applicant_id, product_id, max_approved_pesewas, tenor_days, status
            FROM loan_application
            WHERE id = ? FOR UPDATE
        """).param(applicationId)
        .query((rs, rowNum) -> new AppRow(
                rs.getObject("id", UUID.class),
                rs.getObject("applicant_id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getLong("max_approved_pesewas"),
                rs.getInt("tenor_days"),
                rs.getString("status")
        )).optional().orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));

        if (!"APPROVED".equals(app.status())) {
            throw new IllegalStateException("Application is not approved: " + app.status());
        }

        if (db.sql("SELECT count(*) FROM loan_contract WHERE application_id = ?").param(applicationId).query(Long.class).single() != 0) {
            throw new IllegalStateException("A contract already exists for this application");
        }
        var product = productService.getProductById(app.productId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + app.productId()));

        long principal = app.approvedAmount();
        long originationFee = BigInteger.valueOf(principal)
                .multiply(BigInteger.valueOf(product.originationFeeBps()))
                .divide(BigInteger.valueOf(10000L)).longValueExact();

        long totalInterest;
        if ("MERCHANT_CASH_ADVANCE".equals(product.productType())) {
            totalInterest = BigInteger.valueOf(principal)
                    .multiply(BigInteger.valueOf(product.interestRateBps()))
                    .divide(BigInteger.valueOf(10000L)).longValueExact();
        } else {
            totalInterest = BigInteger.valueOf(principal)
                    .multiply(BigInteger.valueOf(product.interestRateBps()))
                    .multiply(BigInteger.valueOf(app.tenorDays()))
                    .divide(BigInteger.valueOf(3650000L)).longValueExact();
        }

        long totalRepayment = principal + totalInterest + originationFee;
        UUID contractId = UUID.randomUUID();
        String contractReference = "LN-" + System.currentTimeMillis() + "-" + contractId.toString().substring(0, 8).toUpperCase();
        LocalDate startDate = LocalDate.now();
        LocalDate maturityDate = startDate.plusDays(app.tenorDays());

        db.sql("""
            INSERT INTO loan_contract
            (id, application_id, contract_reference, borrower_id, product_id,
             principal_pesewas, origination_fee_pesewas, total_interest_pesewas,
             total_repayment_pesewas, outstanding_principal_pesewas,
             outstanding_interest_pesewas, outstanding_fees_pesewas,
             outstanding_penalties_pesewas, tenor_days, daily_sweep_bps,
             start_date, maturity_date, status, delinquency_state, days_past_due)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, 'ACTIVE', 'PERFORMING', 0)
        """).params(contractId, applicationId, contractReference, app.applicantId(), app.productId(),
                principal, originationFee, totalInterest, totalRepayment, principal, totalInterest,
                originationFee, app.tenorDays(), product.dailySweepBps(), startDate, maturityDate)
        .update();

        // Generate Installments
        int installmentCount = Math.max(1, app.tenorDays() / 30);
        long installmentPrincipal = principal / installmentCount;
        long residualPrincipal = principal - (installmentPrincipal * installmentCount);

        long installmentInterest = totalInterest / installmentCount;
        long residualInterest = totalInterest - (installmentInterest * installmentCount);

        for (int i = 1; i <= installmentCount; i++) {
            UUID instId = UUID.randomUUID();
            LocalDate dueDate = startDate.plusDays(30L * i);
            if (i == installmentCount) {
                dueDate = maturityDate;
            }

            long pDue = installmentPrincipal + (i == installmentCount ? residualPrincipal : 0);
            long iDue = installmentInterest + (i == installmentCount ? residualInterest : 0);
            long fDue = (i == 1) ? originationFee : 0L; // Origination fee due with 1st installment

            db.sql("""
                INSERT INTO loan_repayment_schedule
                (id, contract_id, installment_number, due_date, principal_due_pesewas,
                 interest_due_pesewas, fees_due_pesewas, principal_paid_pesewas,
                 interest_paid_pesewas, fees_paid_pesewas, penalty_paid_pesewas, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, 'PENDING')
            """).params(instId, contractId, i, dueDate, pDue, iDue, fDue)
            .update();
        }

        return getContract(contractId).orElseThrow();
    }

    @Transactional
    public LoanContract disburse(UUID contractId, boolean isSimulation) {
        configService.assertPermitted(isSimulation);
        Actor.current().requireOps();
        db.sql("SELECT id FROM loan_contract WHERE id = ? FOR UPDATE").param(contractId).query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Contract not found: " + contractId));
        LoanContract contract = getContract(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Contract not found: " + contractId));

        if (contract.disbursedAt() != null) {
            throw new IllegalStateException("Contract already disbursed on " + contract.disbursedAt());
        }

        if (!"ACTIVE".equals(contract.status())) throw new IllegalStateException("Loan is not active");
        if (db.sql("SELECT count(*) FROM loan_application WHERE id = ? AND reviewed_by IS NOT NULL AND review_evidence IS NOT NULL AND status = 'APPROVED'")
                .param(contract.applicationId()).query(Long.class).single() != 1) throw new IllegalStateException("Verified credit review required");
        String borrower = borrowerAccount(contract);
        ledger.openSystem(FUNDING, "clearing", Ledger.GHS, false);
        // Origination fee is due on repayment as specified by the schedule; do not deduct it twice.
        long netDisbursement = contract.principalPesewas();
        var posted = ledger.post(Ledger.Posting.of("loan-disbursement", contractId.toString(), "loan_disbursement", List.of(
                Ledger.Leg.debit(FUNDING, netDisbursement), Ledger.Leg.credit(borrower, netDisbursement))));
        UUID txnId = UUID.randomUUID();
        String txnRef = "TXN-DSB-" + contract.contractReference();

        db.sql("""
            INSERT INTO loan_transaction
            (id, contract_id, transaction_type, amount_pesewas, principal_component_pesewas,
             interest_component_pesewas, fee_component_pesewas, penalty_component_pesewas, reference, notes, journal_tx)
            VALUES (?, ?, 'DISBURSEMENT', ?, ?, 0, 0, 0, ?, ?, ?)
        """).params(txnId, contractId, netDisbursement, contract.principalPesewas(),
                txnRef, "Prefunded loan principal credited to borrower; fee due on repayment", posted.transactionId())
        .update();

        db.sql("""
            UPDATE loan_contract
            SET disbursed_at = now(), updated_at = now()
            WHERE id = ?
        """).param(contractId).update();

        return getContract(contractId).orElseThrow();
    }

    String borrowerAccount(LoanContract contract) {
        String type = db.sql("SELECT applicant_type FROM loan_application WHERE id = ?").param(contract.applicationId()).query(String.class).single();
        String owner = switch (type) { case "MERCHANT" -> "merchant"; case "CUSTOMER" -> "customer"; default -> throw new IllegalStateException("Unsupported borrower type"); };
        String code = Ledger.code(owner, contract.borrowerId(), "available", Ledger.GHS);
        if (ledger.account(code).isEmpty()) throw new IllegalStateException("Borrower has no enrolled ledger account");
        return code;
    }

    public Optional<LoanContract> getContract(UUID contractId) {
        return db.sql("""
            SELECT id, application_id, contract_reference, borrower_id, product_id,
                   principal_pesewas, origination_fee_pesewas, total_interest_pesewas,
                   total_repayment_pesewas, outstanding_principal_pesewas,
                   outstanding_interest_pesewas, outstanding_fees_pesewas,
                   outstanding_penalties_pesewas, tenor_days, daily_sweep_bps,
                   disbursed_at, start_date, maturity_date, status, delinquency_state,
                   days_past_due, created_at, updated_at
            FROM loan_contract
            WHERE id = ?
        """).param(contractId)
        .query((rs, rowNum) -> new LoanContract(
                rs.getObject("id", UUID.class),
                rs.getObject("application_id", UUID.class),
                rs.getString("contract_reference"),
                rs.getObject("borrower_id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getLong("principal_pesewas"),
                rs.getLong("origination_fee_pesewas"),
                rs.getLong("total_interest_pesewas"),
                rs.getLong("total_repayment_pesewas"),
                rs.getLong("outstanding_principal_pesewas"),
                rs.getLong("outstanding_interest_pesewas"),
                rs.getLong("outstanding_fees_pesewas"),
                rs.getLong("outstanding_penalties_pesewas"),
                rs.getInt("tenor_days"),
                rs.getInt("daily_sweep_bps"),
                rs.getObject("disbursed_at", OffsetDateTime.class),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("maturity_date", LocalDate.class),
                rs.getString("status"),
                rs.getString("delinquency_state"),
                rs.getInt("days_past_due"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        )).optional();
    }

    public List<Installment> getSchedule(UUID contractId) {
        return db.sql("""
            SELECT id, contract_id, installment_number, due_date, principal_due_pesewas,
                   interest_due_pesewas, fees_due_pesewas, principal_paid_pesewas,
                   interest_paid_pesewas, fees_paid_pesewas, penalty_paid_pesewas,
                   status, paid_at
            FROM loan_repayment_schedule
            WHERE contract_id = ?
            ORDER BY installment_number ASC
        """).param(contractId)
        .query((rs, rowNum) -> new Installment(
                rs.getObject("id", UUID.class),
                rs.getObject("contract_id", UUID.class),
                rs.getInt("installment_number"),
                rs.getObject("due_date", LocalDate.class),
                rs.getLong("principal_due_pesewas"),
                rs.getLong("interest_due_pesewas"),
                rs.getLong("fees_due_pesewas"),
                rs.getLong("principal_paid_pesewas"),
                rs.getLong("interest_paid_pesewas"),
                rs.getLong("fees_paid_pesewas"),
                rs.getLong("penalty_paid_pesewas"),
                rs.getString("status"),
                rs.getObject("paid_at", OffsetDateTime.class)
        )).list();
    }
}
