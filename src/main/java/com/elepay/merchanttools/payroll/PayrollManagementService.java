package com.elepay.merchanttools.payroll;

import com.elepay.merchanttools.accounting.MerchantGeneralLedgerService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain 6: Payroll Management Service.
 * Handles employee master records, monthly payroll batch computation,
 * itemized digital payslips, maker-checker approvals, batch execution to MoMo/Bank,
 * and General Ledger wage expense & statutory liability postings.
 */
@Service
public class PayrollManagementService {

    private final JdbcClient db;
    private final GhanaianStatutoryDeductionsCalculator calculator;
    private final MerchantGeneralLedgerService glService;

    public PayrollManagementService(JdbcClient db,
                                    GhanaianStatutoryDeductionsCalculator calculator,
                                    MerchantGeneralLedgerService glService) {
        this.db = db;
        this.calculator = calculator;
        this.glService = glService;
    }

    public record EmployeeRecord(
            UUID id,
            UUID merchantId,
            String employeeCode,
            String fullName,
            String nationalId,
            String tin,
            String ssnitNumber,
            String phone,
            String email,
            String bankCode,
            String bankAccountNumber,
            String momoNetwork,
            String momoNumber,
            String employmentType,
            long baseSalaryPesewas,
            long allowancesPesewas,
            int tier3VoluntaryRateBps,
            long reliefsPesewas,
            boolean isActive
    ) {}

    public record CreateEmployeeCommand(
            UUID merchantId,
            String employeeCode,
            String fullName,
            String nationalId,
            String tin,
            String ssnitNumber,
            String phone,
            String email,
            String bankCode,
            String bankAccountNumber,
            String momoNetwork,
            String momoNumber,
            String employmentType,
            long baseSalaryPesewas,
            long allowancesPesewas,
            int tier3VoluntaryRateBps,
            long reliefsPesewas
    ) {}

    public record PayslipItem(
            UUID id,
            UUID payrollRunId,
            UUID employeeId,
            String employeeName,
            long baseSalaryPesewas,
            long allowancesPesewas,
            long grossPayPesewas,
            long ssnitTier2Pesewas,
            long ssnitTier3VoluntaryPesewas,
            long taxableIncomePesewas,
            long payeTaxPesewas,
            long netPayPesewas,
            long ssnitTier1EmployerPesewas,
            String paymentMethod,
            String destinationAccount,
            String paymentStatus,
            String payoutReference
    ) {}

    public record PayrollRunSummary(
            UUID id,
            UUID merchantId,
            int periodMonth,
            int periodYear,
            long totalGrossPesewas,
            long totalEmployeeSsnitPesewas,
            long totalEmployerSsnitPesewas,
            long totalTier3Pesewas,
            long totalPayeTaxPesewas,
            long totalNetPayPesewas,
            String status,
            String initiatedBy,
            String approvedBy,
            OffsetDateTime executedAt,
            List<PayslipItem> payslips
    ) {}

    /**
     * Registers a new employee under the merchant's master payroll.
     */
    @Transactional
    public EmployeeRecord registerEmployee(CreateEmployeeCommand cmd) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO payroll_employee
            (id, merchant_id, employee_code, full_name, national_id, tin, ssnit_number, phone, email,
             bank_code, bank_account_number, momo_network, momo_number, employment_type, base_salary_pesewas,
             allowances_pesewas, tier3_voluntary_rate_bps, reliefs_pesewas, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true)
        """).params(id, cmd.merchantId(), cmd.employeeCode(), cmd.fullName(), cmd.nationalId(), cmd.tin(),
                cmd.ssnitNumber(), cmd.phone(), cmd.email(), cmd.bankCode(), cmd.bankAccountNumber(),
                cmd.momoNetwork(), cmd.momoNumber(), cmd.employmentType() != null ? cmd.employmentType() : "FULL_TIME",
                cmd.baseSalaryPesewas(), cmd.allowancesPesewas(), cmd.tier3VoluntaryRateBps(), cmd.reliefsPesewas())
        .update();

        return new EmployeeRecord(id, cmd.merchantId(), cmd.employeeCode(), cmd.fullName(), cmd.nationalId(),
                cmd.tin(), cmd.ssnitNumber(), cmd.phone(), cmd.email(), cmd.bankCode(), cmd.bankAccountNumber(),
                cmd.momoNetwork(), cmd.momoNumber(), cmd.employmentType() != null ? cmd.employmentType() : "FULL_TIME",
                cmd.baseSalaryPesewas(), cmd.allowancesPesewas(), cmd.tier3VoluntaryRateBps(), cmd.reliefsPesewas(), true);
    }

    public List<EmployeeRecord> listActiveEmployees(UUID merchantId) {
        return db.sql("""
            SELECT id, merchant_id, employee_code, full_name, national_id, tin, ssnit_number, phone, email,
                   bank_code, bank_account_number, momo_network, momo_number, employment_type, base_salary_pesewas,
                   allowances_pesewas, tier3_voluntary_rate_bps, reliefs_pesewas, is_active
            FROM payroll_employee
            WHERE merchant_id = ? AND is_active = true
            ORDER BY employee_code ASC
        """).params(merchantId)
        .query((rs, rowNum) -> new EmployeeRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("employee_code"),
                rs.getString("full_name"),
                rs.getString("national_id"),
                rs.getString("tin"),
                rs.getString("ssnit_number"),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getString("bank_code"),
                rs.getString("bank_account_number"),
                rs.getString("momo_network"),
                rs.getString("momo_number"),
                rs.getString("employment_type"),
                rs.getLong("base_salary_pesewas"),
                rs.getLong("allowances_pesewas"),
                rs.getInt("tier3_voluntary_rate_bps"),
                rs.getLong("reliefs_pesewas"),
                rs.getBoolean("is_active")
        )).list();
    }

    /**
     * Creates a draft payroll run for a specific month and year, computing itemized payslips
     * with statutory SSNIT and GRA PAYE deductions for all active employees.
     */
    @Transactional
    public PayrollRunSummary createPayrollRun(UUID merchantId, int month, int year, String initiatedBy) {
        List<EmployeeRecord> employees = listActiveEmployees(merchantId);
        if (employees.isEmpty()) {
            throw new IllegalStateException("No active employees found for merchant");
        }

        UUID runId = UUID.randomUUID();
        long totalGross = 0;
        long totalEmpSsnit = 0;
        long totalEmprSsnit = 0;
        long totalTier3 = 0;
        long totalPaye = 0;
        long totalNet = 0;

        List<PayslipItem> payslips = new ArrayList<>();

        for (EmployeeRecord emp : employees) {
            var res = calculator.calculate(
                    emp.baseSalaryPesewas(),
                    emp.allowancesPesewas(),
                    emp.tier3VoluntaryRateBps(),
                    0, // employer tier 3
                    emp.reliefsPesewas(),
                    0  // other deductions
            );

            UUID payslipId = UUID.randomUUID();
            String payMethod = (emp.momoNumber() != null && !emp.momoNumber().isBlank()) ? "MOMO" : "BANK";
            String destAccount = "MOMO".equals(payMethod)
                    ? emp.momoNetwork() + ":" + emp.momoNumber()
                    : emp.bankCode() + ":" + emp.bankAccountNumber();

            payslips.add(new PayslipItem(
                    payslipId,
                    runId,
                    emp.id(),
                    emp.fullName(),
                    emp.baseSalaryPesewas(),
                    emp.allowancesPesewas(),
                    res.grossSalaryPesewas(),
                    res.employeeSsnitTier2Pesewas(),
                    res.employeeTier3VoluntaryPesewas(),
                    res.taxableIncomePesewas(),
                    res.graPayeTaxPesewas(),
                    res.netPayPesewas(),
                    res.employerSsnitTier1Pesewas(),
                    payMethod,
                    destAccount,
                    "PENDING",
                    null
            ));

            totalGross += res.grossSalaryPesewas();
            totalEmpSsnit += res.employeeSsnitTier2Pesewas();
            totalEmprSsnit += res.employerSsnitTier1Pesewas();
            totalTier3 += res.employeeTier3VoluntaryPesewas();
            totalPaye += res.graPayeTaxPesewas();
            totalNet += res.netPayPesewas();
        }

        db.sql("""
            INSERT INTO payroll_run
            (id, merchant_id, period_month, period_year, total_gross_pesewas, total_employee_ssnit_pesewas,
             total_employer_ssnit_pesewas, total_tier3_pesewas, total_paye_tax_pesewas, total_net_pay_pesewas,
             status, initiated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?)
        """).params(runId, merchantId, month, year, totalGross, totalEmpSsnit, totalEmprSsnit,
                totalTier3, totalPaye, totalNet, initiatedBy)
        .update();

        for (PayslipItem ps : payslips) {
            db.sql("""
                INSERT INTO payroll_payslip
                (id, payroll_run_id, employee_id, base_salary_pesewas, allowances_pesewas, gross_pay_pesewas,
                 ssnit_tier2_pesewas, ssnit_tier3_voluntary_pesewas, taxable_income_pesewas, paye_tax_pesewas,
                 other_deductions_pesewas, net_pay_pesewas, ssnit_tier1_employer_pesewas, payment_status,
                 payment_method, destination_account)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 'PENDING', ?, ?)
            """).params(ps.id(), runId, ps.employeeId(), ps.baseSalaryPesewas(), ps.allowancesPesewas(),
                    ps.grossPayPesewas(), ps.ssnitTier2Pesewas(), ps.ssnitTier3VoluntaryPesewas(),
                    ps.taxableIncomePesewas(), ps.payeTaxPesewas(), ps.netPayPesewas(),
                    ps.ssnitTier1EmployerPesewas(), ps.paymentMethod(), ps.destinationAccount())
            .update();
        }

        return new PayrollRunSummary(runId, merchantId, month, year, totalGross, totalEmpSsnit,
                totalEmprSsnit, totalTier3, totalPaye, totalNet, "DRAFT", initiatedBy, null, null, payslips);
    }

    /**
     * Submits a draft payroll run for maker-checker review.
     */
    @Transactional
    public void submitPayrollRun(UUID merchantId, UUID runId) {
        if (db.sql("UPDATE payroll_run SET status = 'SUBMITTED' WHERE id = ? AND merchant_id = ? AND status = 'DRAFT'")
                .params(runId, merchantId).update() != 1)
            throw new IllegalStateException("Only a draft payroll can be submitted for approval");
    }

    /**
     * Approves a submitted payroll run (Maker-Checker approval).
     */
    @Transactional
    public void approvePayrollRun(UUID merchantId, UUID runId, String approvedBy) {
        if (db.sql("UPDATE payroll_run SET status = 'APPROVED', approved_by = ? WHERE id = ? AND merchant_id = ? AND status = 'SUBMITTED'")
                .params(approvedBy, runId, merchantId).update() != 1)
            throw new IllegalStateException("Only a submitted payroll can be approved");
    }

    /**
     * Hands an approved payroll run to a real payout batch (see recordPayoutSubmission) and posts salary
     * expense, SSNIT and PAYE liabilities, and net wages owed to the merchant GL.
     */
    @Transactional
    /**
     * Records that an approved payroll was handed to ELEPAY Payouts as a real payout batch. Nothing here moves
     * money: the payslips become SUBMITTED with the batch reference, and net wages are booked as owed (Accrued
     * Wages) until the payout settles. Payment outcomes are tracked on the payout batch, not invented here.
     */
    public PayrollRunSummary recordPayoutSubmission(UUID merchantId, UUID runId, String payoutBatchReference) {
        if (payoutBatchReference == null || !payoutBatchReference.matches("[A-Za-z0-9_.:-]{3,64}"))
            throw new IllegalArgumentException("A real payout batch reference is required");
        var run = db.sql("""
            SELECT id, merchant_id, period_month, period_year, total_gross_pesewas, total_employee_ssnit_pesewas,
                   total_employer_ssnit_pesewas, total_tier3_pesewas, total_paye_tax_pesewas, total_net_pay_pesewas,
                   status, initiated_by, approved_by, executed_at
            FROM payroll_run WHERE id = ? AND merchant_id = ? FOR UPDATE
        """).params(runId, merchantId)
        .query((rs, rowNum) -> new PayrollRunSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getInt("period_month"),
                rs.getInt("period_year"),
                rs.getLong("total_gross_pesewas"),
                rs.getLong("total_employee_ssnit_pesewas"),
                rs.getLong("total_employer_ssnit_pesewas"),
                rs.getLong("total_tier3_pesewas"),
                rs.getLong("total_paye_tax_pesewas"),
                rs.getLong("total_net_pay_pesewas"),
                rs.getString("status"),
                rs.getString("initiated_by"),
                rs.getString("approved_by"),
                rs.getObject("executed_at", OffsetDateTime.class),
                List.of()
        )).single();

        if (!"APPROVED".equals(run.status())) {
            throw new IllegalStateException("Payroll run must be APPROVED before submitting it to payouts (current: " + run.status() + ")");
        }

        OffsetDateTime now = OffsetDateTime.now();
        int moved = db.sql("UPDATE payroll_run SET status = 'PAYMENT_SUBMITTED', executed_at = ? WHERE id = ? AND status = 'APPROVED'")
                .params(now, runId).update();
        if (moved != 1) throw new IllegalStateException("Payroll run was already submitted");

        db.sql("""
            UPDATE payroll_payslip
            SET payment_status = 'SUBMITTED',
                payout_reference = ?
            WHERE payroll_run_id = ?
        """).params(payoutBatchReference, runId).update();

        // Post to General Ledger:
        // Debit: Salaries & Wages Expense (6010) -> totalGross
        // Debit: Employer SSNIT Tier 1 Expense (6020) -> totalEmployerSSNIT
        // Credit: Accrued Wages & Salaries (2050) -> totalNetPay (owed until the payout batch settles)
        // Credit: SSNIT Contributions Payable (2030) -> (totalEmployeeSSNIT + totalEmployerSSNIT + totalTier3)
        // Credit: GRA PAYE Tax Withholding Payable (2040) -> totalPayeTax
        var wageExp = glService.getAccountByCode(merchantId, "6010");
        var ssnitExp = glService.getAccountByCode(merchantId, "6020");
        var bankAcc = glService.getAccountByCode(merchantId, "2050");
        var ssnitPayable = glService.getAccountByCode(merchantId, "2030");
        var payePayable = glService.getAccountByCode(merchantId, "2040");

        if (wageExp.isPresent() && bankAcc.isPresent()) {
            List<MerchantGeneralLedgerService.JournalLineCommand> lines = new ArrayList<>();
            lines.add(new MerchantGeneralLedgerService.JournalLineCommand(wageExp.get().id(), run.totalGrossPesewas(), 0L, "Salaries Expense: " + run.periodMonth() + "/" + run.periodYear()));
            if (ssnitExp.isPresent() && run.totalEmployerSsnitPesewas() > 0) {
                lines.add(new MerchantGeneralLedgerService.JournalLineCommand(ssnitExp.get().id(), run.totalEmployerSsnitPesewas(), 0L, "Employer SSNIT 13.5%"));
            }
            lines.add(new MerchantGeneralLedgerService.JournalLineCommand(bankAcc.get().id(), 0L, run.totalNetPayPesewas(), "Net wages owed (payout batch " + payoutBatchReference + ")"));

            long totalSsnitObligation = run.totalEmployeeSsnitPesewas() + run.totalEmployerSsnitPesewas() + run.totalTier3Pesewas();
            if (ssnitPayable.isPresent() && totalSsnitObligation > 0) {
                lines.add(new MerchantGeneralLedgerService.JournalLineCommand(ssnitPayable.get().id(), 0L, totalSsnitObligation, "SSNIT & Tier 3 Payable"));
            }
            if (payePayable.isPresent() && run.totalPayeTaxPesewas() > 0) {
                lines.add(new MerchantGeneralLedgerService.JournalLineCommand(payePayable.get().id(), 0L, run.totalPayeTaxPesewas(), "GRA PAYE Tax Payable"));
            }

            glService.postJournalEntry(new MerchantGeneralLedgerService.PostJournalEntryCommand(
                    merchantId,
                    "PAYROLL-GL-" + run.periodYear() + "-" + run.periodMonth(),
                    LocalDate.now(),
                    "Payroll Run: " + run.periodMonth() + "/" + run.periodYear(),
                    "PAYROLL",
                    runId,
                    "GHS",
                    lines
            ));
        }

        var updatedPayslips = listPayslipsForRun(runId);
        return new PayrollRunSummary(run.id(), run.merchantId(), run.periodMonth(), run.periodYear(),
                run.totalGrossPesewas(), run.totalEmployeeSsnitPesewas(), run.totalEmployerSsnitPesewas(),
                run.totalTier3Pesewas(), run.totalPayeTaxPesewas(), run.totalNetPayPesewas(),
                "PAYMENT_SUBMITTED", run.initiatedBy(), run.approvedBy(), now, updatedPayslips);
    }

    public List<PayslipItem> listPayslipsForRun(UUID runId) {
        return db.sql("""
            SELECT p.id, p.payroll_run_id, p.employee_id, e.full_name as employee_name,
                   p.base_salary_pesewas, p.allowances_pesewas, p.gross_pay_pesewas,
                   p.ssnit_tier2_pesewas, p.ssnit_tier3_voluntary_pesewas, p.taxable_income_pesewas,
                   p.paye_tax_pesewas, p.net_pay_pesewas, p.ssnit_tier1_employer_pesewas,
                   p.payment_method, p.destination_account, p.payment_status, p.payout_reference
            FROM payroll_payslip p
            JOIN payroll_employee e ON p.employee_id = e.id
            WHERE p.payroll_run_id = ?
        """).param(runId)
        .query((rs, rowNum) -> new PayslipItem(
                rs.getObject("id", UUID.class),
                rs.getObject("payroll_run_id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getString("employee_name"),
                rs.getLong("base_salary_pesewas"),
                rs.getLong("allowances_pesewas"),
                rs.getLong("gross_pay_pesewas"),
                rs.getLong("ssnit_tier2_pesewas"),
                rs.getLong("ssnit_tier3_voluntary_pesewas"),
                rs.getLong("taxable_income_pesewas"),
                rs.getLong("paye_tax_pesewas"),
                rs.getLong("net_pay_pesewas"),
                rs.getLong("ssnit_tier1_employer_pesewas"),
                rs.getString("payment_method"),
                rs.getString("destination_account"),
                rs.getString("payment_status"),
                rs.getString("payout_reference")
        )).list();
    }

    public record PayrollRunRow(UUID id, int periodMonth, int periodYear, long totalGrossPesewas,
                                long totalPayeTaxPesewas, long totalNetPayPesewas, String status,
                                String initiatedBy, String approvedBy, OffsetDateTime executedAt) {}

    public List<PayrollRunRow> listRuns(UUID merchantId) {
        return db.sql("""
            SELECT id, period_month, period_year, total_gross_pesewas, total_paye_tax_pesewas, total_net_pay_pesewas,
                   status, initiated_by, approved_by, executed_at
            FROM payroll_run WHERE merchant_id = ? ORDER BY period_year DESC, period_month DESC
            """).param(merchantId).query(PayrollRunRow.class).list();
    }

    public Optional<PayrollRunRow> getRun(UUID merchantId, UUID runId) {
        return db.sql("""
            SELECT id, period_month, period_year, total_gross_pesewas, total_paye_tax_pesewas, total_net_pay_pesewas,
                   status, initiated_by, approved_by, executed_at
            FROM payroll_run WHERE id = ? AND merchant_id = ?
            """).params(runId, merchantId).query(PayrollRunRow.class).optional();
    }
}
