package com.elepay.merchanttools.payroll;

import com.elepay.common.ApiException;
import com.elepay.identity.Actor;
import com.elepay.merchanttools.MerchantToolAccess;
import com.elepay.merchanttools.accounting.MerchantGeneralLedgerService;
import com.elepay.payout.Payouts;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import static com.elepay.merchanttools.MerchantToolAccess.Resource.*;

/**
 * Payroll for the merchant's own staff. ELEPAY calculates pay, SSNIT and PAYE and keeps the approval trail.
 * Paying staff happens through a real ELEPAY payout batch (with all payout controls); payroll only records
 * which batch paid the run after checking it belongs to this merchant and matches the net pay.
 */
@RestController
@RequestMapping("/v1/payroll")
public class PayrollController {
    private final MerchantToolAccess access;
    private final PayrollManagementService payroll;
    private final MerchantGeneralLedgerService ledger;
    private final Payouts payouts;

    public PayrollController(MerchantToolAccess access, PayrollManagementService payroll,
                             MerchantGeneralLedgerService ledger, Payouts payouts) {
        this.access = access;
        this.payroll = payroll;
        this.ledger = ledger;
        this.payouts = payouts;
    }

    @GetMapping("/employees/merchant/{merchantId}")
    public List<PayrollManagementService.EmployeeRecord> employees(@PathVariable UUID merchantId) {
        access.manage(merchantId);
        return payroll.listActiveEmployees(merchantId);
    }

    public record EmployeeRequest(UUID merchantId, String fullName, String phone, String email, String ssnitNumber,
                                  String tin, String momoNetwork, String momoNumber, String bankCode,
                                  String bankAccountNumber, String employmentType, long baseSalaryPesewas,
                                  Long allowancesPesewas) {}

    @PostMapping("/employees")
    public PayrollManagementService.EmployeeRecord addEmployee(@RequestBody EmployeeRequest req) {
        access.manage(req.merchantId());
        if (req.fullName() == null || req.fullName().isBlank())
            throw ApiException.badRequest("name_required", "Employee name is required");
        if (req.baseSalaryPesewas() <= 0) throw ApiException.badRequest("salary_invalid", "Base salary must be positive");
        boolean momo = req.momoNumber() != null && !req.momoNumber().isBlank();
        boolean bank = req.bankAccountNumber() != null && !req.bankAccountNumber().isBlank();
        if (!momo && !bank) throw ApiException.badRequest("pay_destination_required", "Add a MoMo number or bank account to pay this employee");
        if (momo && (req.momoNetwork() == null || !req.momoNetwork().matches("MTN|TELECEL|AIRTELTIGO")))
            throw ApiException.badRequest("momo_network_invalid", "MoMo network must be MTN, TELECEL or AIRTELTIGO");
        String code = "EMP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return payroll.registerEmployee(new PayrollManagementService.CreateEmployeeCommand(req.merchantId(), code,
                req.fullName().strip(), null, blank(req.tin()), blank(req.ssnitNumber()), blank(req.phone()), blank(req.email()),
                bank ? blank(req.bankCode()) : null, bank ? req.bankAccountNumber().strip() : null,
                momo ? req.momoNetwork() : null, momo ? req.momoNumber().replaceAll("\\s", "") : null,
                req.employmentType() == null ? "FULL_TIME" : req.employmentType(), req.baseSalaryPesewas(),
                req.allowancesPesewas() == null ? 0L : req.allowancesPesewas(), 0, 0L));
    }

    @GetMapping("/runs/merchant/{merchantId}")
    public List<PayrollManagementService.PayrollRunRow> runs(@PathVariable UUID merchantId) {
        access.manage(merchantId);
        return payroll.listRuns(merchantId);
    }

    public record RunRequest(UUID merchantId, int month, int year) {}

    @PostMapping("/runs")
    public PayrollManagementService.PayrollRunSummary createRun(@RequestBody RunRequest req) {
        access.manage(req.merchantId());
        if (req.month() < 1 || req.month() > 12 || req.year() < 2020 || req.year() > 2100)
            throw ApiException.badRequest("period_invalid", "Choose a valid month and year");
        boolean exists = payroll.listRuns(req.merchantId()).stream()
                .anyMatch(r -> r.periodMonth() == req.month() && r.periodYear() == req.year() && !"CANCELLED".equals(r.status()));
        if (exists) throw ApiException.conflict("payroll_exists", "There is already a payroll for that month");
        ledger.initializeStandardChartOfAccounts(req.merchantId());
        try {
            return payroll.createPayrollRun(req.merchantId(), req.month(), req.year(), Actor.current().subject());
        } catch (IllegalStateException e) {
            throw ApiException.unprocessable("payroll_refused", e.getMessage());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("payroll_exists", "There is already a payroll for that month");
        }
    }

    @GetMapping("/runs/{runId}/payslips")
    public List<PayrollManagementService.PayslipItem> payslips(@PathVariable UUID runId) {
        access.manage(PAYROLL_RUN, runId);
        return payroll.listPayslipsForRun(runId);
    }

    @PostMapping("/runs/{runId}/submit")
    public PayrollManagementService.PayrollRunRow submit(@PathVariable UUID runId) {
        UUID merchantId = access.manage(PAYROLL_RUN, runId);
        guard(() -> payroll.submitPayrollRun(merchantId, runId));
        return payroll.getRun(merchantId, runId)
                .orElseThrow(() -> ApiException.notFound("Payroll run not found"));
    }

    @PostMapping("/runs/{runId}/approve")
    public PayrollManagementService.PayrollRunRow approve(@PathVariable UUID runId) {
        UUID merchantId = access.manage(PAYROLL_RUN, runId);
        guard(() -> payroll.approvePayrollRun(merchantId, runId, Actor.current().subject()));
        return payroll.getRun(merchantId, runId)
                .orElseThrow(() -> ApiException.notFound("Payroll run not found"));
    }

    public record PayoutSubmission(String payoutBatchReference) {}

    /** Links an approved run to the payout batch that pays it, after checking the batch is real and matches. */
    @PostMapping("/runs/{runId}/payout")
    @Transactional
    public PayrollManagementService.PayrollRunSummary recordPayout(@PathVariable UUID runId, @RequestBody PayoutSubmission req) {
        UUID merchantId = access.manage(PAYROLL_RUN, runId);
        String reference = req.payoutBatchReference() == null ? "" : req.payoutBatchReference().strip();
        UUID batchId = payouts.batchId("merchant", merchantId, reference)
                .orElseThrow(() -> ApiException.unprocessable("payout_batch_not_found", "No payout batch with that reference for this merchant"));
        Payouts.Batch batch = payouts.batch(batchId);
        List<PayrollManagementService.PayslipItem> slips = payroll.listPayslipsForRun(runId);
        long net = slips.stream().mapToLong(PayrollManagementService.PayslipItem::netPayPesewas).sum();
        if (batch.total() != net || batch.itemCount() != slips.size())
            throw ApiException.unprocessable("payout_batch_mismatch",
                    "That payout batch does not match this payroll (" + slips.size() + " staff, net " + net + " pesewas)");
        try {
            return payroll.recordPayoutSubmission(merchantId, runId, batch.reference());
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw ApiException.conflict("payroll_state", e.getMessage());
        }
    }

    private static void guard(Runnable r) {
        try { r.run(); } catch (IllegalStateException e) { throw ApiException.conflict("payroll_state", e.getMessage()); }
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.strip(); }
}
