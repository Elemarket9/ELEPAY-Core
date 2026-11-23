package com.elepay.merchanttools.payroll;

import com.elepay.PostgresTestBase;
import com.elepay.merchanttools.accounting.AccountsReceivableAndPayableService;
import com.elepay.merchanttools.accounting.MerchantGeneralLedgerService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PayrollAndSupplierTest extends PostgresTestBase {

    @Autowired
    private GhanaianStatutoryDeductionsCalculator calculator;

    @Autowired
    private PayrollManagementService payrollService;

    @Autowired
    private SupplierManagementService supplierService;

    @Autowired
    private MerchantGeneralLedgerService glService;

    @Autowired
    private AccountsReceivableAndPayableService arApService;

    @Test
    @DisplayName("Domain 6: Ghanaian Statutory Deductions (SSNIT Tier 1 & 2, Tier 3, GRA PAYE)")
    void testGhanaianStatutoryDeductions() {
        // Test 1: Low-income below PAYE threshold (GHS 490.00 = 49,000 pesewas)
        long baseSalary = 40_000L; // GHS 400.00
        var resLow = calculator.calculate(baseSalary, 0L, 0, 0, 0L, 0L);

        // SSNIT Tier 2 (5.5% of 400) = GHS 22.00 (2,200 pesewas)
        assertThat(resLow.employeeSsnitTier2Pesewas()).isEqualTo(2_200L);
        // SSNIT Tier 1 (13.5% employer of 400) = GHS 54.00 (5,400 pesewas)
        assertThat(resLow.employerSsnitTier1Pesewas()).isEqualTo(5_400L);
        // Taxable income = 40,000 - 2,200 = 37,800 pesewas (under 49,000 band limit -> 0% PAYE tax)
        assertThat(resLow.graPayeTaxPesewas()).isEqualTo(0L);
        assertThat(resLow.netPayPesewas()).isEqualTo(baseSalary - 2_200L);

        // Test 2: Standard professional monthly salary (GHS 5,000.00 = 500,000 pesewas) with 5% Tier 3 voluntary
        long profSalary = 500_000L;
        long allowances = 50_000L; // GHS 500.00 allowances
        var resProf = calculator.calculate(profSalary, allowances, 500, 500, 0L, 0L);

        assertThat(resProf.grossSalaryPesewas()).isEqualTo(550_000L);
        // SSNIT Tier 2: 5.5% of 500,000 = 27,500 pesewas
        assertThat(resProf.employeeSsnitTier2Pesewas()).isEqualTo(27_500L);
        // Employer Tier 1: 13.5% of 500,000 = 67,500 pesewas
        assertThat(resProf.employerSsnitTier1Pesewas()).isEqualTo(67_500L);
        // Voluntary Tier 3 (5% of 500,000) = 25,000 pesewas
        assertThat(resProf.employeeTier3VoluntaryPesewas()).isEqualTo(25_000L);
        // PAYE Tax graduated calculation
        assertThat(resProf.graPayeTaxPesewas()).isGreaterThan(0L);
        // Invariant: netPay = gross - employee deductions
        assertThat(resProf.netPayPesewas()).isEqualTo(resProf.grossSalaryPesewas() - resProf.totalEmployeeDeductionsPesewas());
        // Invariant: employer cost = gross + employer SSNIT Tier 1 + employer Tier 3
        assertThat(resProf.totalCostToEmployerPesewas()).isEqualTo(resProf.grossSalaryPesewas() + resProf.employerSsnitTier1Pesewas() + resProf.employerTier3VoluntaryPesewas());
    }

    @Test
    @DisplayName("Domain 6: Employee Master, Monthly Payroll Run, Maker-Checker & Batch Execution to MoMo")
    void testPayrollRunLifecycle() {
        UUID merchantId = UUID.randomUUID();
        glService.initializeStandardChartOfAccounts(merchantId);

        // 1. Register employees (1 MoMo, 1 Bank)
        var emp1 = payrollService.registerEmployee(new PayrollManagementService.CreateEmployeeCommand(
                merchantId,
                "EMP-001",
                "Ama Serwaa",
                "GHA-123456789-1",
                "P0001234567",
                "S1234567890",
                "+233244111222",
                "ama@company.gh",
                null,
                null,
                "MTN",
                "0244111222",
                "FULL_TIME",
                300_000L, // GHS 3,000.00
                30_000L,  // GHS 300.00
                500,      // 5% Tier 3
                0L
        ));

        var emp2 = payrollService.registerEmployee(new PayrollManagementService.CreateEmployeeCommand(
                merchantId,
                "EMP-002",
                "Kwame Boateng",
                "GHA-987654321-2",
                "P0009876543",
                "S9876543210",
                "+233201999888",
                "kwame@company.gh",
                "GCB",
                "101100223344",
                null,
                null,
                "FULL_TIME",
                500_000L, // GHS 5,000.00
                50_000L,
                0,
                0L
        ));

        var activeEmps = payrollService.listActiveEmployees(merchantId);
        assertThat(activeEmps).hasSize(2);

        // 2. Create monthly draft payroll run
        int month = 5;
        int year = 2026;
        var run = payrollService.createPayrollRun(merchantId, month, year, "hr.manager@elepay.com");
        assertThat(run.status()).isEqualTo("DRAFT");
        assertThat(run.payslips()).hasSize(2);
        assertThat(run.totalNetPayPesewas()).isGreaterThan(0L);

        // 3. Execution without approval should fail
        assertThatThrownBy(() -> payrollService.executePayrollRun(merchantId, run.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be APPROVED");

        // 4. Maker-Checker workflow: Submit -> Approve
        payrollService.submitPayrollRun(merchantId, run.id());
        payrollService.approvePayrollRun(merchantId, run.id(), "cfo@elepay.com");

        // 5. Execute batch payroll run
        var executed = payrollService.executePayrollRun(merchantId, run.id());
        assertThat(executed.status()).isEqualTo("EXECUTED");
        assertThat(executed.executedAt()).isNotNull();

        // Verify each payslip has a generated payout reference and PAID status
        var payslips = payrollService.listPayslipsForRun(run.id());
        for (var ps : payslips) {
            assertThat(ps.paymentStatus()).isEqualTo("PAID");
            assertThat(ps.payoutReference()).startsWith("PAYOUT-PR-");
        }

        // Verify GL postings (Salaries expense debited, wages disbursed credited)
        var wageExp = glService.getAccountByCode(merchantId, "6010").orElseThrow();
        assertThat(wageExp.balancePesewas()).isEqualTo(run.totalGrossPesewas());

        var ssnitPayable = glService.getAccountByCode(merchantId, "2030").orElseThrow();
        assertThat(ssnitPayable.balancePesewas()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("Domain 6: Supplier Master, Bill Creation, Approval & Automated Payment Run")
    void testSupplierAndAccountsPayable() {
        UUID merchantId = UUID.randomUUID();
        glService.initializeStandardChartOfAccounts(merchantId);

        // 1. Register Supplier
        var supplier = supplierService.registerSupplier(new SupplierManagementService.CreateSupplierCommand(
                merchantId,
                "SUP-001",
                "Ghana Grid Supplies Ltd",
                "Eben Quaye",
                "orders@gridgh.com",
                "+233302123456",
                "C0008889999",
                30,
                "BANK",
                "ECOBANK",
                "144100998877",
                null,
                null
        ));

        // 2. Create Supplier Bill
        var bill = supplierService.createBill(new SupplierManagementService.CreateBillCommand(
                merchantId,
                supplier.id(),
                "BILL-2026-001",
                LocalDate.now(),
                LocalDate.now().plusDays(15),
                "GHS",
                List.of(
                        new SupplierManagementService.BillItemCommand("Copper Cables 50m", 10, 50_000L),
                        new SupplierManagementService.BillItemCommand("Circuit Breakers 32A", 20, 10_000L)
                )
        ));

        assertThat(bill.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(bill.totalAmountPesewas()).isEqualTo(700_000L); // GHS 7,000.00

        // 3. Approve Bill -> GL auto-posting (COGS debited, AP credited)
        var approvedBill = supplierService.approveBill(merchantId, bill.id(), "ops_lead");
        assertThat(approvedBill.status()).isEqualTo("APPROVED");

        var apAcc = glService.getAccountByCode(merchantId, "2010").orElseThrow();
        assertThat(apAcc.balancePesewas()).isEqualTo(700_000L);

        // 4. AP Aging Report
        var apReport = arApService.getApAgingReport(merchantId, LocalDate.now());
        assertThat(apReport.totalUnpaidBillsCount()).isEqualTo(1);
        assertThat(apReport.aging().currentPesewas()).isEqualTo(700_000L);

        // 5. Execute Automated Payment Run
        var payRun = supplierService.executeAutomatedPaymentRun(
                merchantId,
                LocalDate.now().plusDays(30),
                "1020"
        );

        assertThat(payRun.status()).isEqualTo("EXECUTED");
        assertThat(payRun.billCount()).isEqualTo(1);
        assertThat(payRun.totalAmountPesewas()).isEqualTo(700_000L);

        // Verify AP account cleared in GL
        var apCleared = glService.getAccountByCode(merchantId, "2010").orElseThrow();
        assertThat(apCleared.balancePesewas()).isEqualTo(0L);
    }
}
