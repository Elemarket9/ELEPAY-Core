package com.elepay.merchanttools.payroll;

import com.elepay.common.ApiException;
import com.elepay.identity.Actor;
import com.elepay.merchanttools.MerchantToolAccess;
import com.elepay.merchanttools.accounting.MerchantGeneralLedgerService;
import com.elepay.payout.Payouts;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
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
        validatePayoutBatch(batch, slips);
        try {
            return payroll.recordPayoutSubmission(merchantId, runId, batch.reference());
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw ApiException.conflict("payroll_state", e.getMessage());
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("payroll_state",
                    "Payout batch reference " + batch.reference() + " is already linked to another payroll run");
        }
    }

    private record PayoutTarget(String destinationKey, long amountPesewas) {}

    /**
     * Validates that a payout batch is fully completed (all items succeeded, zero failed/pending/submitted),
     * has the exact total and item count, and matches every payroll payslip 1-to-1 by normalized recipient
     * destination and net pay amount.
     */
    public static void validatePayoutBatch(Payouts.Batch batch, List<PayrollManagementService.PayslipItem> slips) {
        if (batch == null) {
            throw ApiException.unprocessable("payout_batch_not_found", "Payout batch not found");
        }
        Map<String, Long> statusCounts = batch.statusCounts() == null ? Map.of() : batch.statusCounts();
        List<Payouts.Payout> items = batch.items() == null ? List.of() : batch.items();

        long failedCount = statusCounts.getOrDefault("failed", 0L)
                + statusCounts.getOrDefault("rejected", 0L)
                + statusCounts.getOrDefault("returned", 0L);
        if (failedCount == 0L) {
            failedCount = items.stream()
                    .filter(Objects::nonNull)
                    .filter(p -> isFailedStatus(p.status()))
                    .count();
        }
        if (failedCount > 0) {
            throw ApiException.unprocessable("payout_batch_failed",
                    "Cannot link a payout batch that has failed items (" + failedCount + " failed)");
        }

        int totalItems = batch.itemCount();
        long succeededCount = statusCounts.getOrDefault("succeeded", 0L)
                + statusCounts.getOrDefault("completed", 0L);
        boolean allSucceeded = totalItems > 0
                && items.size() == totalItems
                && succeededCount == totalItems
                && items.stream().allMatch(p -> p != null && isSucceededStatus(p.status()));
        if (!allSucceeded) {
            throw ApiException.unprocessable("payout_batch_incomplete",
                    "Cannot link a payout batch until all items have succeeded (" + succeededCount + " of " + totalItems + " succeeded)");
        }

        long net = slips.stream().mapToLong(PayrollManagementService.PayslipItem::netPayPesewas).sum();
        if (batch.total() != net || totalItems != slips.size()) {
            throw ApiException.unprocessable("payout_batch_mismatch",
                    "That payout batch does not match this payroll (" + slips.size() + " staff, net " + net + " pesewas)");
        }

        Map<PayoutTarget, Integer> expectedTargets = new HashMap<>();
        for (PayrollManagementService.PayslipItem slip : slips) {
            String key = normalizePayslipDestination(slip.paymentMethod(), slip.destinationAccount());
            if (key == null) {
                throw ApiException.unprocessable("payout_batch_mismatch",
                        "Payslip destination is invalid or missing for employee " + slip.employeeName());
            }
            expectedTargets.merge(new PayoutTarget(key, slip.netPayPesewas()), 1, Integer::sum);
        }

        Map<PayoutTarget, Integer> actualTargets = new HashMap<>();
        for (Payouts.Payout item : items) {
            String key = normalizePayoutDestination(item.destination());
            if (key == null) {
                throw ApiException.unprocessable("payout_batch_mismatch",
                        "Payout batch item " + item.reference() + " has an invalid destination");
            }
            actualTargets.merge(new PayoutTarget(key, item.amount()), 1, Integer::sum);
        }

        if (!expectedTargets.equals(actualTargets)) {
            throw ApiException.unprocessable("payout_batch_mismatch",
                    "Payout batch recipient destinations and amounts do not match the payroll run payslips");
        }
    }

    private static boolean isFailedStatus(String status) {
        if (status == null) return false;
        String s = status.strip().toLowerCase(Locale.ROOT);
        return "failed".equals(s) || "rejected".equals(s) || "returned".equals(s);
    }

    private static boolean isSucceededStatus(String status) {
        if (status == null) return false;
        String s = status.strip().toLowerCase(Locale.ROOT);
        return "succeeded".equals(s) || "completed".equals(s);
    }

    private static String normalizePayslipDestination(String paymentMethod, String destinationAccount) {
        if (destinationAccount == null || destinationAccount.isBlank()) return null;
        String[] parts = destinationAccount.strip().split(":", 2);
        if (parts.length != 2) return null;
        String method = paymentMethod == null ? "" : paymentMethod.strip().toUpperCase(Locale.ROOT);
        String network = parts[0].strip().toUpperCase(Locale.ROOT);
        String account = parts[1].strip();
        if (network.isEmpty() || account.isEmpty()) return null;
        return canonicalDestination(method, network, account);
    }

    private static String normalizePayoutDestination(Payouts.Destination dest) {
        if (dest == null || dest.type() == null || dest.network() == null || dest.accountNumber() == null) return null;
        String method = dest.type().strip().toUpperCase(Locale.ROOT);
        String network = dest.network().strip().toUpperCase(Locale.ROOT);
        String account = dest.accountNumber().strip();
        if (method.isEmpty() || network.isEmpty() || account.isEmpty()) return null;
        return canonicalDestination(method, network, account);
    }

    private static String canonicalDestination(String method, String network, String account) {
        if ("MOMO".equals(method)) {
            String digits = account.replaceAll("[^0-9]", "");
            if (digits.startsWith("0") && digits.length() == 10) {
                digits = "233" + digits.substring(1);
            }
            if (digits.isEmpty()) return null;
            return "MOMO:" + network + ":" + digits;
        }
        String cleanAccount = account.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (cleanAccount.isEmpty()) return null;
        return method + ":" + network + ":" + cleanAccount;
    }

    private static void guard(Runnable r) {
        try { r.run(); } catch (IllegalStateException e) { throw ApiException.conflict("payroll_state", e.getMessage()); }
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.strip(); }
}
