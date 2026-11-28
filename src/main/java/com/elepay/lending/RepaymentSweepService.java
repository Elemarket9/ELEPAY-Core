package com.elepay.lending;

import com.elepay.ledger.Ledger;
import com.elepay.merchant.MerchantAccess;
import java.math.BigInteger;
import java.util.ArrayList;
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
    private final Ledger ledger;
    private final MerchantAccess merchants;

    public RepaymentSweepService(JdbcClient db, LoanContractService contractService, Ledger ledger, MerchantAccess merchants) {
        this.db = db;
        this.contractService = contractService;
        this.ledger = ledger;
        this.merchants = merchants;
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
    public void authorizeSweep(UUID contractId, String acceptedMandateReference) {
        var contract = contractService.getContract(contractId).orElseThrow();
        var access = merchants.require(contract.borrowerId(), MerchantAccess.Permission.MANAGE);
        access.actor().requireUser();
        if (!"owner".equals(access.role()) || acceptedMandateReference == null || acceptedMandateReference.isBlank() || acceptedMandateReference.length() > 256) throw new IllegalArgumentException("Merchant owner and accepted mandate evidence required");
        db.sql("UPDATE loan_contract SET sweep_mandate = ?, sweep_authorized_by = ? WHERE id = ?").params(acceptedMandateReference, access.actor().subject(), contractId).update();
    }

    @Transactional
    public SweepResult executeSweep(UUID contractId, long salesBatchAmountPesewas, String externalReference) {
        db.sql("SELECT id FROM loan_contract WHERE id = ? FOR UPDATE").param(contractId).query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Contract not found: " + contractId));
        var contract = contractService.getContract(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Contract not found: " + contractId));

        if (!"ACTIVE".equals(contract.status()) && !"RESTRUCTURED".equals(contract.status())) {
            throw new IllegalStateException("Contract is not in repayable state: " + contract.status());
        }

        if (contract.dailySweepBps() <= 0) {
            throw new IllegalStateException("Contract has no daily sweep configured (daily_sweep_bps = 0)");
        }

        if (contract.disbursedAt() == null || db.sql("SELECT count(*) FROM loan_transaction WHERE contract_id = ? AND transaction_type = 'DISBURSEMENT' AND journal_tx IS NOT NULL").param(contractId).query(Long.class).single() != 1) throw new IllegalStateException("Loan has no verified disbursement");
        if (db.sql("SELECT count(*) FROM loan_contract WHERE id = ? AND sweep_mandate IS NOT NULL AND sweep_authorized_by IS NOT NULL").param(contractId).query(Long.class).single() != 1) throw new IllegalStateException("Sweep mandate required");
        UUID settlement = UUID.fromString(externalReference);
        String borrower = contractService.borrowerAccount(contract);
        long verifiedSales = ledger.entries(settlement).stream().filter(e -> e.kind().equals("settlement") && e.account().equals(borrower) && e.amount() > 0 && Ledger.GHS.equals(e.currency())).mapToLong(Ledger.Entry::amount).reduce(0L, Math::addExact);
        if (verifiedSales <= 0 || verifiedSales != salesBatchAmountPesewas) throw new IllegalArgumentException("Settlement evidence does not match sales amount");
        if (db.sql("SELECT count(*) FROM loan_transaction WHERE settlement_evidence = ?").param(settlement).query(Long.class).single() != 0) throw new IllegalStateException("Settlement already swept");
        long calculatedSweep = BigInteger.valueOf(verifiedSales).multiply(BigInteger.valueOf(contract.dailySweepBps())).divide(BigInteger.valueOf(10000)).longValueExact();
        long totalDebt = contract.outstandingPenaltiesPesewas() + contract.outstandingFeesPesewas()
                + contract.outstandingInterestPesewas() + contract.outstandingPrincipalPesewas();

        if (totalDebt <= 0) {
            db.sql("UPDATE loan_contract SET status = 'CLOSED', updated_at = now() WHERE id = ?").param(contractId).update();
            return new SweepResult(null, contractId, salesBatchAmountPesewas, 0L, 0L, 0L, 0L, 0L, 0L, "CLOSED");
        }

        long actualSweep = Math.min(Math.min(totalDebt, calculatedSweep), ledger.balance(borrower));
        if (actualSweep <= 0) return new SweepResult(null, contractId, verifiedSales, 0L, 0L, 0L, 0L, 0L, totalDebt, contract.status());
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

        ledger.openSystem(LoanContractService.FUNDING, "clearing", Ledger.GHS, false);
        List<Ledger.Leg> legs = new ArrayList<>();
        legs.add(Ledger.Leg.debit(borrower, actualSweep));
        if (principalAlloc > 0) legs.add(Ledger.Leg.credit(LoanContractService.FUNDING, principalAlloc));
        long income = Math.addExact(Math.addExact(interestAlloc, feeAlloc), penaltyAlloc);
        if (income > 0) legs.add(Ledger.Leg.credit(Ledger.REVENUE, income));
        var posted = ledger.post(Ledger.Posting.of("loan-sweep", settlement.toString(), "loan_repayment", legs).relatedTo(settlement));
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
             interest_component_pesewas, fee_component_pesewas, penalty_component_pesewas, reference, notes, journal_tx, settlement_evidence)
            VALUES (?, ?, 'REPAYMENT_SWEEP', ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).params(txnId, contractId, actualSweep, principalAlloc, interestAlloc, feeAlloc, penaltyAlloc, ref,
                "Authorized ledger-backed settlement sweep", posted.transactionId(), settlement)
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
