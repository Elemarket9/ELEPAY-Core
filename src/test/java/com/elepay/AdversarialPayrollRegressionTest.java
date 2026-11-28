package com.elepay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.ApiException;
import com.elepay.merchanttools.accounting.MerchantGeneralLedgerService;
import com.elepay.merchanttools.payroll.PayrollController;
import com.elepay.merchanttools.payroll.PayrollManagementService;
import com.elepay.payout.Payouts;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Regression tests for CRIT-07 payroll payout batch hardening:
 * 1. Rejects non-completed (submitted/pending/empty) batches with 422 payout_batch_incomplete
 *    and failed/rejected/returned batches with 422 payout_batch_failed.
 * 2. Enforces 1-to-1 recipient destination and net-pay amount matching against payslips
 *    (422 payout_batch_mismatch).
 * 3. Closes the concurrent TOCTOU race on payoutBatchReference reuse via DB unique index
 *    uq_payroll_run_payout_batch_ref (V67) while preserving multi-payslip runs.
 */
class AdversarialPayrollRegressionTest extends PostgresTestBase {

    @Autowired
    private PayrollManagementService payrollService;

    @Autowired
    private MerchantGeneralLedgerService glService;

    @Autowired
    private JdbcClient db;

    private static PayrollManagementService.PayslipItem slip(String name, String method, String destAccount, long netPay) {
        return new PayrollManagementService.PayslipItem(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), name,
                netPay, 0L, netPay, 0L, 0L, netPay, 0L, netPay, 0L,
                method, destAccount, "PENDING", null);
    }

    private static Payouts.Payout payoutItem(UUID batchId, UUID ownerId, long amount, String status,
                                             String type, String network, String accountNumber, String name) {
        return new Payouts.Payout(
                UUID.randomUUID(), "PO-" + UUID.randomUUID().toString().substring(0, 8), "cli-1",
                "merchant", ownerId, amount, status, "verified", name, null,
                new Payouts.Destination(type, network, accountNumber, name),
                null, null, batchId, OffsetDateTime.now(),
                "succeeded".equals(status) ? OffsetDateTime.now() : null);
    }

    @Test
    @DisplayName("CRIT-07.1: Submitted, partially completed, and empty payout batches are rejected with 422 payout_batch_incomplete")
    void rejectsNonCompletedPayoutBatches() {
        UUID batchId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        var employeeSlip = slip("Ama", "MOMO", "MTN:0245550005", 4725L);

        // Submitted (in-flight / timed-out) batch with failed=0
        var submittedItem = payoutItem(batchId, merchantId, 4725L, "submitted", "momo", "mtn", "233245550005", "Ama");
        var submittedBatch = new Payouts.Batch(batchId, "PB-SUBMITTED01", 1, 4725L, Map.of("submitted", 1L), List.of(submittedItem));

        assertThatThrownBy(() -> PayrollController.validatePayoutBatch(submittedBatch, List.of(employeeSlip)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(api.code()).isEqualTo("payout_batch_incomplete");
                });

        // Partially succeeded batch (1 succeeded, 1 submitted)
        var slip2 = slip("Kwame", "MOMO", "MTN:0245550006", 5000L);
        var okItem = payoutItem(batchId, merchantId, 4725L, "succeeded", "momo", "mtn", "233245550005", "Ama");
        var pendingItem = payoutItem(batchId, merchantId, 5000L, "submitted", "momo", "mtn", "233245550006", "Kwame");
        var partialBatch = new Payouts.Batch(batchId, "PB-PARTIAL001", 2, 9725L,
                Map.of("succeeded", 1L, "submitted", 1L), List.of(okItem, pendingItem));

        assertThatThrownBy(() -> PayrollController.validatePayoutBatch(partialBatch, List.of(employeeSlip, slip2)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(api.code()).isEqualTo("payout_batch_incomplete");
                });

        // Empty batch
        var emptyBatch = new Payouts.Batch(batchId, "PB-EMPTY00001", 0, 0L, Map.of(), List.of());
        assertThatThrownBy(() -> PayrollController.validatePayoutBatch(emptyBatch, List.of(employeeSlip)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(api.code()).isEqualTo("payout_batch_incomplete");
                });

        // Failed batch must still return 422 payout_batch_failed
        var failedItem = payoutItem(batchId, merchantId, 4725L, "failed", "momo", "mtn", "233205550001", "Ama");
        var failedBatch = new Payouts.Batch(batchId, "PB-FAILED0001", 1, 4725L, Map.of("failed", 1L), List.of(failedItem));
        assertThatThrownBy(() -> PayrollController.validatePayoutBatch(failedBatch, List.of(employeeSlip)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(api.code()).isEqualTo("payout_batch_failed");
                });
    }

    @Test
    @DisplayName("CRIT-07.3: Batch recipient destinations and per-employee amounts must match payslips 1-to-1")
    void enforcesDestinationAndAmountMatching() {
        UUID batchId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        var slipAma = slip("Ama", "MOMO", "MTN:0245550005", 4725L);
        var slipKwame = slip("Kwame", "BANK", "TST:101100223344", 8200L);

        // Wrong recipient MoMo number (0245550006 instead of 0245550005), same amount, succeeded
        var wrongDestItem = payoutItem(batchId, merchantId, 4725L, "succeeded", "momo", "mtn", "233245550006", "Other");
        var wrongDestBatch = new Payouts.Batch(batchId, "PB-WRONGDEST1", 1, 4725L, Map.of("succeeded", 1L), List.of(wrongDestItem));

        assertThatThrownBy(() -> PayrollController.validatePayoutBatch(wrongDestBatch, List.of(slipAma)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(api.code()).isEqualTo("payout_batch_mismatch");
                });

        // Swapped amounts across 2 employees (same total 12,925, same destinations, wrong per-employee amounts)
        var swappedAma = payoutItem(batchId, merchantId, 8200L, "succeeded", "momo", "mtn", "233245550005", "Ama");
        var swappedKwame = payoutItem(batchId, merchantId, 4725L, "succeeded", "bank", "TST", "101100223344", "Kwame");
        var swappedBatch = new Payouts.Batch(batchId, "PB-SWAPPED001", 2, 12925L,
                Map.of("succeeded", 2L), List.of(swappedAma, swappedKwame));

        assertThatThrownBy(() -> PayrollController.validatePayoutBatch(swappedBatch, List.of(slipAma, slipKwame)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(api.code()).isEqualTo("payout_batch_mismatch");
                });

        // Exact 1-to-1 match (normalizing 0245550005 <-> 233245550005 and MTN <-> mtn, plus bank account)
        var okAma = payoutItem(batchId, merchantId, 4725L, "succeeded", "momo", "mtn", "233245550005", "Ama");
        var okKwame = payoutItem(batchId, merchantId, 8200L, "succeeded", "bank", "TST", "101100223344", "Kwame");
        var validBatch = new Payouts.Batch(batchId, "PB-VALID00001", 2, 12925L,
                Map.of("succeeded", 2L), List.of(okKwame, okAma));

        assertThatCode(() -> PayrollController.validatePayoutBatch(validBatch, List.of(slipAma, slipKwame)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CRIT-07.2: Concurrent TOCTOU race reusing one payoutBatchReference across two approved runs allows at most one")
    void concurrentPayoutBatchReferenceReuseAllowsOnlyOneRun() throws Exception {
        UUID merchantId = UUID.randomUUID();
        glService.initializeStandardChartOfAccounts(merchantId);

        // Register 2 employees to also verify a single run with 2+ payslips legitimately shares one batch reference
        payrollService.registerEmployee(new PayrollManagementService.CreateEmployeeCommand(
                merchantId, "EMP-R1", "Ama Race", null, null, null, "+233245550005", "ama@race.gh",
                null, null, "MTN", "0245550005", "FULL_TIME", 50_000L, 0L, 0, 0L));
        payrollService.registerEmployee(new PayrollManagementService.CreateEmployeeCommand(
                merchantId, "EMP-R2", "Kwame Race", null, null, null, "+233245550006", "kwame@race.gh",
                "TST", "101100223344", null, null, "FULL_TIME", 60_000L, 0L, 0, 0L));

        var run1 = payrollService.createPayrollRun(merchantId, 1, 2026, "maker@race.gh");
        var run2 = payrollService.createPayrollRun(merchantId, 2, 2026, "maker@race.gh");
        payrollService.submitPayrollRun(merchantId, run1.id());
        payrollService.submitPayrollRun(merchantId, run2.id());
        payrollService.approvePayrollRun(merchantId, run1.id(), "checker@race.gh");
        payrollService.approvePayrollRun(merchantId, run2.id(), "checker@race.gh");

        String sharedRef = "PB-RACE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (UUID targetRunId : List.of(run1.id(), run2.id())) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        var summary = payrollService.recordPayoutSubmission(merchantId, targetRunId, sharedRef);
                        assertThat(summary.payslips()).hasSize(2);
                        return "OK";
                    } catch (IllegalStateException e) {
                        return "CONFLICT:" + e.getMessage();
                    }
                }));
            }

            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : futures) {
                outcomes.add(f.get(15, TimeUnit.SECONDS));
            }

            long successes = outcomes.stream().filter("OK"::equals).count();
            long conflicts = outcomes.stream().filter(s -> s.startsWith("CONFLICT:")).count();
            assertThat(successes).as("exactly one concurrent run may claim the payout batch reference").isEqualTo(1);
            assertThat(conflicts).as("the losing concurrent run must fail with conflict").isEqualTo(1);

            Integer linkedRuns = db.sql("SELECT COUNT(*) FROM payroll_run WHERE payout_batch_reference = ?")
                    .param(sharedRef).query(Integer.class).single();
            assertThat(linkedRuns).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
