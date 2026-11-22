package com.elepay.lending;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanRestructureService {

    private final JdbcClient db;
    private final LoanContractService contractService;

    public LoanRestructureService(JdbcClient db, LoanContractService contractService) {
        this.db = db;
        this.contractService = contractService;
    }

    public record RestructureResult(
            UUID contractId,
            String previousStatus,
            String newStatus,
            int extendedDays,
            long newTotalDebtPesewas,
            LocalDate newMaturityDate,
            String notes
    ) {}

    public record WriteOffResult(
            UUID contractId,
            long writtenOffPrincipalPesewas,
            long writtenOffInterestPesewas,
            long writtenOffFeesPesewas,
            long writtenOffPenaltiesPesewas,
            long totalWrittenOffPesewas,
            String status,
            String reason
    ) {}

    @Transactional
    public RestructureResult restructure(UUID contractId, int additionalDays, String justification) {
        var contract = contractService.getContract(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Contract not found: " + contractId));

        if ("CLOSED".equals(contract.status()) || "WRITTEN_OFF".equals(contract.status())) {
            throw new IllegalStateException("Cannot restructure closed or written off contract: " + contract.status());
        }

        LocalDate newMaturityDate = contract.maturityDate().plusDays(additionalDays);
        int newTenor = contract.tenorDays() + additionalDays;

        db.sql("""
            UPDATE loan_contract
            SET status = 'RESTRUCTURED',
                delinquency_state = 'PERFORMING',
                days_past_due = 0,
                maturity_date = ?,
                tenor_days = ?,
                updated_at = now()
            WHERE id = ?
        """).params(newMaturityDate, newTenor, contractId)
        .update();

        // Push due dates of all remaining unpaid installments
        db.sql("""
            UPDATE loan_repayment_schedule
            SET due_date = due_date + (? || ' days')::interval
            WHERE contract_id = ? AND status != 'PAID'
        """).params(additionalDays, contractId)
        .update();

        UUID txnId = UUID.randomUUID();
        db.sql("""
            INSERT INTO loan_transaction
            (id, contract_id, transaction_type, amount_pesewas, reference, notes)
            VALUES (?, ?, 'RESTRUCTURE', 0, ?, ?)
        """).params(txnId, contractId, "TXN-RST-" + txnId.toString().substring(0, 8),
                "Restructure approved. Tenor extended by " + additionalDays + " days. Justification: " + justification)
        .update();

        long remainingDebt = contract.outstandingPrincipalPesewas() + contract.outstandingInterestPesewas()
                + contract.outstandingFeesPesewas() + contract.outstandingPenaltiesPesewas();

        return new RestructureResult(contractId, contract.status(), "RESTRUCTURED", additionalDays, remainingDebt, newMaturityDate, justification);
    }

    @Transactional
    public WriteOffResult writeOff(UUID contractId, String approvalReason) {
        var contract = contractService.getContract(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Contract not found: " + contractId));

        if ("WRITTEN_OFF".equals(contract.status())) {
            throw new IllegalStateException("Contract already written off");
        }

        long p = contract.outstandingPrincipalPesewas();
        long i = contract.outstandingInterestPesewas();
        long f = contract.outstandingFeesPesewas();
        long pen = contract.outstandingPenaltiesPesewas();
        long total = p + i + f + pen;

        db.sql("""
            UPDATE loan_contract
            SET status = 'WRITTEN_OFF',
                outstanding_principal_pesewas = 0,
                outstanding_interest_pesewas = 0,
                outstanding_fees_pesewas = 0,
                outstanding_penalties_pesewas = 0,
                updated_at = now()
            WHERE id = ?
        """).params(contractId).update();

        // Mark remaining installments as WAIVED
        db.sql("""
            UPDATE loan_repayment_schedule
            SET status = 'WAIVED'
            WHERE contract_id = ? AND status != 'PAID'
        """).params(contractId).update();

        UUID txnId = UUID.randomUUID();
        db.sql("""
            INSERT INTO loan_transaction
            (id, contract_id, transaction_type, amount_pesewas, principal_component_pesewas,
             interest_component_pesewas, fee_component_pesewas, penalty_component_pesewas, reference, notes)
            VALUES (?, ?, 'WRITE_OFF', ?, ?, ?, ?, ?, ?, ?)
        """).params(txnId, contractId, total, p, i, f, pen, "TXN-WRO-" + txnId.toString().substring(0, 8),
                "Bad debt write-off. Approval reason: " + approvalReason)
        .update();

        return new WriteOffResult(contractId, p, i, f, pen, total, "WRITTEN_OFF", approvalReason);
    }
}
