package com.elepay.lending;

import com.elepay.ledger.Ledger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * End-to-end automated loan origination, schedule generation, and ledger disbursement engine.
 * Converts approved credit applications into enforceable contracts, calculates exact amortization plans,
 * and executes balanced, double-entry fund disbursements into the borrower's wallet.
 */
@Service
public class LoanOriginationEngine {

    public record OriginationResult(
            UUID contractId,
            String contractReference,
            UUID borrowerId,
            long principalPesewas,
            long totalRepaymentPesewas,
            int installmentCount,
            UUID disbursementJournalTxId,
            String status
    ) {}

    private final JdbcClient db;
    private final LoanContractService contractService;
    private final AmortizationScheduleEngine amortizationEngine;
    private final LoanProductService productService;
    private final LendingConfigService configService;
    private final Ledger ledger;

    public static final String LENDING_FUNDING_ACCOUNT = "system:lending_funding:GHS";

    public LoanOriginationEngine(
            JdbcClient db,
            LoanContractService contractService,
            AmortizationScheduleEngine amortizationEngine,
            LoanProductService productService,
            LendingConfigService configService,
            Ledger ledger
    ) {
        this.db = db;
        this.contractService = contractService;
        this.amortizationEngine = amortizationEngine;
        this.productService = productService;
        this.configService = configService;
        this.ledger = ledger;
    }

    @Transactional
    public OriginationResult originateAndDisburse(
            UUID applicationId,
            AmortizationScheduleEngine.AmortizationMethod method
    ) {
        // 1. Generate contract
        LoanContractService.LoanContract contract = contractService.generateContract(applicationId);

        var product = productService.getProductById(contract.productId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + contract.productId()));

        int tenorMonths = Math.max(1, contract.tenorDays() / 30);
        LocalDate startDate = (contract.startDate() != null) ? contract.startDate() : LocalDate.now();

        // 2. Compute Amortization Schedule
        var schedule = amortizationEngine.generateSchedule(
                contract.principalPesewas(),
                product.interestRateBps(),
                tenorMonths,
                startDate,
                method != null ? method : AmortizationScheduleEngine.AmortizationMethod.EQUAL_INSTALLMENT_FRENCH
        );

        // 3. Clear any placeholder installments and insert exact calculated installments
        db.sql("DELETE FROM loan_repayment_schedule WHERE contract_id = ?").param(contract.id()).update();

        for (var item : schedule.installments()) {
            UUID installmentId = UUID.randomUUID();
            db.sql("""
                INSERT INTO loan_repayment_schedule (
                    id, contract_id, installment_number, due_date,
                    principal_due_pesewas, interest_due_pesewas, fees_due_pesewas,
                    principal_paid_pesewas, interest_paid_pesewas, fees_paid_pesewas,
                    penalty_paid_pesewas, status
                ) VALUES (
                    :id, :contractId, :num, :dueDate,
                    :principal, :interest, 0,
                    0, 0, 0,
                    0, 'PENDING'
                )
            """)
                    .param("id", installmentId)
                    .param("contractId", contract.id())
                    .param("num", item.installmentNumber())
                    .param("dueDate", item.dueDate())
                    .param("principal", item.principalDuePesewas())
                    .param("interest", item.interestDuePesewas())
                    .update();
        }

        // 4. Update contract totals
        db.sql("""
            UPDATE loan_contract
            SET total_interest_pesewas = :interest,
                total_repayment_pesewas = :total,
                outstanding_principal_pesewas = :principal,
                outstanding_interest_pesewas = :interest,
                status = 'DISBURSED',
                disbursed_at = now(),
                updated_at = now()
            WHERE id = :id
        """)
                .param("interest", schedule.totalInterestPesewas())
                .param("total", schedule.totalRepaymentPesewas())
                .param("principal", contract.principalPesewas())
                .param("id", contract.id())
                .update();

        // 5. Post disbursement in Double-Entry Ledger
        ledger.openSystem(LENDING_FUNDING_ACCOUNT, "clearing", "GHS", false);
        String borrowerAccount = ledger.open("customer", contract.borrowerId(), "available", "GHS");

        var posting = Ledger.Posting.of(
                "lending_disbursement",
                "DISBURSE-" + contract.contractReference(),
                "LOAN_DISBURSEMENT",
                List.of(
                        Ledger.Leg.debit(LENDING_FUNDING_ACCOUNT, contract.principalPesewas()),
                        Ledger.Leg.credit(borrowerAccount, contract.principalPesewas())
                )
        ).describedAs("Loan disbursement for contract " + contract.contractReference());

        UUID journalTxId = ledger.post(posting).transactionId();

        // 6. Record disbursement loan transaction
        UUID txnId = UUID.randomUUID();
        db.sql("""
            INSERT INTO loan_transaction (
                id, contract_id, transaction_type, amount_pesewas,
                principal_component_pesewas, interest_component_pesewas,
                fee_component_pesewas, penalty_component_pesewas,
                reference, journal_tx, notes, created_at
            ) VALUES (
                :id, :contractId, 'DISBURSEMENT', :amt,
                :amt, 0, 0, 0,
                :ref, :journalTx, 'Loan funds disbursed', now()
            )
        """)
                .param("id", txnId)
                .param("contractId", contract.id())
                .param("amt", contract.principalPesewas())
                .param("ref", "TXN-DISB-" + contract.contractReference())
                .param("journalTx", journalTxId)
                .update();

        return new OriginationResult(
                contract.id(),
                contract.contractReference(),
                contract.borrowerId(),
                contract.principalPesewas(),
                schedule.totalRepaymentPesewas(),
                schedule.installments().size(),
                journalTxId,
                "DISBURSED"
        );
    }
}
