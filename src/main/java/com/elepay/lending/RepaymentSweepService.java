package com.elepay.lending;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepaymentSweepService {

    private final JdbcClient db;
    private final LoanContractService contractService;

    public RepaymentSweepService(JdbcClient db, LoanContractService contractService) {
        this.db = db;
        this.contractService = contractService;
    }

    public record SweepResult(
            UUID transactionId,
            UUID contractId,
            long salesAmountPesewas,
            long sweptAmountPesewas,
            long principalPaidPesewas,
            long interestPaidPesewas,
            long feePaidPesewas,
            long penaltyPaidPesewas,
            long remainingTotalDebtPesewas,
            String contractStatus
    ) {}

    @Transactional
    public SweepResult executeSweep(UUID contractId, long salesBatchAmountPesewas, String externalReference) {
        var contract = contractService.getContract(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Contract not found: " + contractId));

        if (!"ACTIVE".equals(contract.status()) && !"RESTRUCTURED".equals(contract.status())) {
            throw new IllegalStateException("Contract is not in repayable state: " + contract.status());
        }

        if (contract.dailySweepBps() <= 0) {
            throw new IllegalStateException("Contract has no daily sweep configured (daily_sweep_bps = 0)");
        }

        long calculatedSweep = Math.round(salesBatchAmountPesewas * (contract.dailySweepBps() / 10000.0));
        long totalDebt = contract.outstandingPenaltiesPesewas() + contract.outstandingFeesPesewas()
                + contract.outstandingInterestPesewas() + contract.outstandingPrincipalPesewas();

        if (totalDebt <= 0) {
            db.sql("UPDATE loan_contract SET status = 'CLOSED', updated_at = now() WHERE id = ?").param(contractId).update();
            return new SweepResult(null, contractId, salesBatchAmountPesewas, 0L, 0L, 0L, 0L, 0L, 0L, "CLOSED");
        }

        long actualSweep = Math.min(totalDebt, calculatedSweep);
        long remainingSweep = actualSweep;

        // 1. Allocate to penalties
        long penaltyAlloc = Math.min(contract.outstandingPenaltiesPesewas(), remainingSweep);
        remainingSweep -= penaltyAlloc;

        // 2. Allocate to fees
        long feeAlloc = Math.min(contract.outstandingFeesPesewas(), remainingSweep);
        remainingSweep -= feeAlloc;

        // 3. Allocate to interest
        long interestAlloc = Math.min(contract.outstandingInterestPesewas(), remainingSweep);
        remainingSweep -= interestAlloc;

        // 4. Allocate to principal
        long principalAlloc = Math.min(contract.outstandingPrincipalPesewas(), remainingSweep);
        remainingSweep -= principalAlloc;

        long newPenalties = contract.outstandingPenaltiesPesewas() - penaltyAlloc;
        long newFees = contract.outstandingFeesPesewas() - feeAlloc;
        long newInterest = contract.outstandingInterestPesewas() - interestAlloc;
        long newPrincipal = contract.outstandingPrincipalPesewas() - principalAlloc;
        long remainingTotal = newPenalties + newFees + newInterest + newPrincipal;
        String newStatus = remainingTotal == 0 ? "CLOSED" : contract.status();

        db.sql("""
            UPDATE loan_contract
            SET outstanding_penalties_pesewas = ?,
                outstanding_fees_pesewas = ?,
                outstanding_interest_pesewas = ?,
                outstanding_principal_pesewas = ?,
                status = ?,
                updated_at = now()
            WHERE id = ?
        """).params(newPenalties, newFees, newInterest, newPrincipal, newStatus, contractId)
        .update();

        // Update repayment schedule installments
        allocateToInstallments(contractId, principalAlloc, interestAlloc, feeAlloc, penaltyAlloc);

        UUID txnId = UUID.randomUUID();
        String ref = "TXN-SWP-" + (externalReference != null ? externalReference : txnId.toString().substring(0, 8));

        db.sql("""
            INSERT INTO loan_transaction
            (id, contract_id, transaction_type, amount_pesewas, principal_component_pesewas,
             interest_component_pesewas, fee_component_pesewas, penalty_component_pesewas, reference, notes)
            VALUES (?, ?, 'REPAYMENT_SWEEP', ?, ?, ?, ?, ?, ?, ?)
        """).params(txnId, contractId, actualSweep, principalAlloc, interestAlloc, feeAlloc, penaltyAlloc, ref,
                "Automated daily sales sweep: " + contract.dailySweepBps() + " bps on sales batch " + salesBatchAmountPesewas)
        .update();

        return new SweepResult(txnId, contractId, salesBatchAmountPesewas, actualSweep,
                principalAlloc, interestAlloc, feeAlloc, penaltyAlloc, remainingTotal, newStatus);
    }

    private void allocateToInstallments(UUID contractId, long principalPaid, long interestPaid, long feePaid, long penaltyPaid) {
        List<LoanContractService.Installment> installments = contractService.getSchedule(contractId);

        long pLeft = principalPaid;
        long iLeft = interestPaid;
        long fLeft = feePaid;
        long penLeft = penaltyPaid;

        for (var inst : installments) {
            if ("PAID".equals(inst.status())) {
                continue;
            }

            long pDueRem = inst.principalDuePesewas() - inst.principalPaidPesewas();
            long pApply = Math.min(pDueRem, pLeft);
            pLeft -= pApply;

            long iDueRem = inst.interestDuePesewas() - inst.interestPaidPesewas();
            long iApply = Math.min(iDueRem, iLeft);
            iLeft -= iApply;

            long fDueRem = inst.feesDuePesewas() - inst.feesPaidPesewas();
            long fApply = Math.min(fDueRem, fLeft);
            fLeft -= fApply;

            long penApply = penLeft;
            penLeft = 0; // penalties credited to earliest active installment

            long newPPaid = inst.principalPaidPesewas() + pApply;
            long newIPaid = inst.interestPaidPesewas() + iApply;
            long newFPaid = inst.feesPaidPesewas() + fApply;
            long newPenPaid = inst.penaltyPaidPesewas() + penApply;

            boolean fullyPaid = (newPPaid >= inst.principalDuePesewas()) &&
                                (newIPaid >= inst.interestDuePesewas()) &&
                                (newFPaid >= inst.feesDuePesewas());

            String status = fullyPaid ? "PAID" : "PARTIALLY_PAID";

            db.sql("""
                UPDATE loan_repayment_schedule
                SET principal_paid_pesewas = ?,
                    interest_paid_pesewas = ?,
                    fees_paid_pesewas = ?,
                    penalty_paid_pesewas = ?,
                    status = ?,
                    paid_at = CASE WHEN ? = 'PAID' THEN now() ELSE paid_at END
                WHERE id = ?
            """).params(newPPaid, newIPaid, newFPaid, newPenPaid, status, status, inst.id())
            .update();
        }
    }
}
