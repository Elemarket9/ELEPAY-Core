package com.elepay.reconciliation;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconSuspenseServiceTest extends PostgresTestBase {

    @Autowired
    private ReconSuspenseService suspenseService;

    @Autowired
    private Mt940StatementParser parser;

    @Autowired
    private TenWayClearingReconciliationEngine reconEngine;

    @Test
    @DisplayName("Reconciliation suspense lifecycle: posting variance, aging bucket, and maker-checker resolution")
    void testSuspenseLifecycle() {
        // Create an un-reconciled statement line to obtain a real match_record_id
        String s1 = UUID.randomUUID().toString().substring(0, 8);
        String unreconRef = "DISCREPANT-" + s1;
        String mt940 = """
                :20:GCB-SUSP-%s
                :25:1010%s
                :28C:00002/001
                :60F:C260301GHS1000,00
                :61:2603010301CR350,00NTRFNONREF//%s
                :86:UNMATCHED
                :62F:C260301GHS1350,00
                -
                """.formatted(s1, s1, unreconRef);

        var batch = parser.ingestStatement("GCB", mt940);
        var report = reconEngine.reconcileBatch(batch.batchId());
        UUID matchRecordId = report.outcomes().get(0).matchRecordId();

        // 1. Post variance to suspense
        long variancePesewas = 35_000L; // 350.00 GHS
        var suspense = suspenseService.postSuspenseVariance(
                matchRecordId,
                "GCB",
                variancePesewas,
                "GHS"
        );

        assertThat(suspense).isNotNull();
        assertThat(suspense.status()).isEqualTo(ReconSuspenseService.SuspenseStatus.OPEN);
        assertThat(suspense.agingBucket()).isEqualTo(ReconSuspenseService.AgingBucket.BUCKET_0_24H);
        assertThat(suspense.variancePesewas()).isEqualTo(35_000L);
        assertThat(suspense.suspenseAccountCode()).isEqualTo("system:suspense:recon:GCB:GHS");

        // 2. Query open items
        var openItems = suspenseService.listOpenSuspenseItems(ReconSuspenseService.AgingBucket.BUCKET_0_24H);
        assertThat(openItems).isNotEmpty();
        assertThat(openItems.stream().anyMatch(i -> i.id().equals(suspense.id()))).isTrue();

        // 3. Update aging buckets
        int updated = suspenseService.updateAgingBuckets();
        assertThat(updated).isGreaterThanOrEqualTo(0);

        // 4. Resolve variance with maker-checker controls
        String adjustmentAccount = "system:clearing:resolved:" + UUID.randomUUID() + ":GHS";
        var resolved = suspenseService.resolveVariance(
                suspense.id(),
                "Confirmed bank debit timing delay, matched via internal ticket #4812",
                "SeniorOperationsAuditor_Kojo",
                adjustmentAccount
        );

        assertThat(resolved.status()).isEqualTo(ReconSuspenseService.SuspenseStatus.RESOLVED);
        assertThat(resolved.resolvedBy()).isEqualTo("SeniorOperationsAuditor_Kojo");
        assertThat(resolved.resolvedAt()).isNotNull();

        // Query again to ensure resolved status is persisted
        var queried = suspenseService.findById(suspense.id());
        assertThat(queried).isPresent();
        assertThat(queried.get().status()).isEqualTo(ReconSuspenseService.SuspenseStatus.RESOLVED);
    }

    @Test
    @DisplayName("Resolving suspense variance requires non-empty target adjustment account")
    void testResolveVarianceRequiresTargetAdjustmentAccount() {
        String s2 = UUID.randomUUID().toString().substring(0, 8);
        String unreconRef = "DISCREPANT-VAL-" + s2;
        String mt940 = """
                :20:GCB-VAL-%s
                :25:1010%s
                :28C:00003/001
                :60F:C260301GHS1000,00
                :61:2603010301CR100,00NTRFNONREF//%s
                :86:UNMATCHED VALIDATION
                :62F:C260301GHS1100,00
                -
                """.formatted(s2, s2, unreconRef);

        var batch = parser.ingestStatement("GCB", mt940);
        var report = reconEngine.reconcileBatch(batch.batchId());
        UUID matchRecordId = report.outcomes().get(0).matchRecordId();

        var record = suspenseService.postSuspenseVariance(matchRecordId, "GCB", 10_000L, "GHS");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                suspenseService.resolveVariance(record.id(), "Invalid resolve without adjustment account", "ops_user", null)
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Target adjustment account is required");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                suspenseService.resolveVariance(record.id(), "Invalid resolve without adjustment account", "ops_user", "   ")
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Target adjustment account is required");
    }
}
