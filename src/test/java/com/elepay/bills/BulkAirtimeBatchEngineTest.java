package com.elepay.bills;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BulkAirtimeBatchEngineTest extends PostgresTestBase {

    @Autowired
    private BulkAirtimeBatchEngine bulkEngine;

    @Test
    @DisplayName("Bulk airtime batch: mass multi-carrier top-up with atomic funding deduction")
    void testBulkAirtimeBatch() {
        UUID sponsorId = UUID.randomUUID();
        String batchRef = "CORP-PAYROLL-AIRTIME-" + UUID.randomUUID().toString().substring(0, 6);

        var items = List.of(
                new BulkAirtimeBatchEngine.AirtimeBatchItem("+233241112233", "MTN", 5000L, "ITEM-1"),
                new BulkAirtimeBatchEngine.AirtimeBatchItem("+233202223344", "TELECEL", 2000L, "ITEM-2"),
                new BulkAirtimeBatchEngine.AirtimeBatchItem("+233263334455", "AIRTELTIGO", 3000L, "ITEM-3")
        );

        var result = bulkEngine.processBatch(sponsorId, batchRef, items);

        assertThat(result).isNotNull();
        assertThat(result.batchReference()).isEqualTo(batchRef);
        assertThat(result.totalCount()).isEqualTo(3);
        assertThat(result.successCount()).isEqualTo(3);
        assertThat(result.failureCount()).isZero();
        assertThat(result.totalAmountPesewas()).isEqualTo(10_000L); // 100.00 GHS
        assertThat(result.ledgerTxId()).isNotNull();
        assertThat(result.itemOutcomes()).hasSize(3);
    }
}
