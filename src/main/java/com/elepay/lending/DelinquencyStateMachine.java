package com.elepay.lending;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DelinquencyStateMachine {

    private final JdbcClient db;
    private final LoanContractService contractService;
    private final LoanProductService productService;

    public DelinquencyStateMachine(JdbcClient db, LoanContractService contractService, LoanProductService productService) {
        this.db = db;
        this.contractService = contractService;
        this.productService = productService;
    }

    public record DelinquencyAssessment(
            UUID contractId,
            String previousState,
            String newState,
            int daysPastDue,
            long overduePrincipalPesewas,
            long accruedPenaltyPesewas
    ) {}

    @Transactional
    public DelinquencyAssessment evaluateContract(UUID contractId, LocalDate asOfDate) {
        var contract = contractService.getContract(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Contract not found: " + contractId));

        if ("CLOSED".equals(contract.status()) || "WRITTEN_OFF".equals(contract.status())) {
            return new DelinquencyAssessment(contractId, contract.delinquencyState(), contract.delinquencyState(), 0, 0L, 0L);
        }

        List<LoanContractService.Installment> schedule = contractService.getSchedule(contractId);

        int maxDpd = 0;
        long overduePrincipal = 0L;

        for (var inst : schedule) {
            if ("PAID".equals(inst.status())) {
                continue;
            }

            if (inst.dueDate().isBefore(asOfDate)) {
                int dpd = (int) ChronoUnit.DAYS.between(inst.dueDate(), asOfDate);
                if (dpd > maxDpd) {
                    maxDpd = dpd;
                }
                long unpaidPrincipal = inst.principalDuePesewas() - inst.principalPaidPesewas();
                if (unpaidPrincipal > 0) {
                    overduePrincipal += unpaidPrincipal;
                }
            }
        }

        String newState;
        if (maxDpd == 0) {
            newState = "PERFORMING";
        } else if (maxDpd <= 7) {
            newState = "GRACE";
        } else if (maxDpd <= 90) {
            newState = "OVERDUE";
        } else {
            newState = "DEFAULT";
        }

        // Apply penalty fee if overdue and penalty rate > 0
        long accruedPenalty = 0L;
        if (maxDpd > 7 && overduePrincipal > 0) {
            var product = productService.getProductById(contract.productId()).orElseThrow();
            if (product.penaltyRateBps() > 0) {
                accruedPenalty = BigInteger.valueOf(overduePrincipal)
                        .multiply(BigInteger.valueOf(product.penaltyRateBps()))
                        .multiply(BigInteger.valueOf(maxDpd))
                        .divide(BigInteger.valueOf(3650000L)).longValueExact();
                if (accruedPenalty > 0) {
                    long newPenalties = contract.outstandingPenaltiesPesewas() + accruedPenalty;
                    db.sql("""
                        UPDATE loan_contract
                        SET outstanding_penalties_pesewas = ?, updated_at = now()
                        WHERE id = ?
                    """).params(newPenalties, contractId).update();

                    UUID txnId = UUID.randomUUID();
                    db.sql("""
                        INSERT INTO loan_transaction
                        (id, contract_id, transaction_type, amount_pesewas, penalty_component_pesewas, reference, notes)
                        VALUES (?, ?, 'PENALTY_ACCRUAL', ?, ?, ?, ?)
                    """).params(txnId, contractId, accruedPenalty, accruedPenalty,
                            "TXN-PEN-" + txnId.toString().substring(0, 8),
                            "Penalty accrued for " + maxDpd + " DPD on overdue principal " + overduePrincipal)
                    .update();
                }
            }
        }

        db.sql("""
            UPDATE loan_contract
            SET delinquency_state = ?,
                days_past_due = ?,
                updated_at = now()
            WHERE id = ?
        """).params(newState, maxDpd, contractId).update();

        return new DelinquencyAssessment(contractId, contract.delinquencyState(), newState, maxDpd, overduePrincipal, accruedPenalty);
    }
}
