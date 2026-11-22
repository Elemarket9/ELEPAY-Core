package com.elepay.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.UUID;

@DisplayName("Algorithm 032: Mule-Account Graph Detection with Bounded Aggregation & Paging")
class MuleGraphServiceTest extends PostgresTestBase {

    @Autowired MuleGraphService muleGraphService;

    @Test
    @DisplayName("Algorithm 032: Rapid pass-through funds within 15 minutes increments mule score")
    void testRapidPassThroughDetection() {
        String muleAccount = "mule-" + UUID.randomUUID();
        String sourceAccount = "victim-" + UUID.randomUUID();
        String destAccount = "cashout-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        // 1. Inflow to mule account (100,000 pesewas = 1,000 GHS)
        muleGraphService.recordFlowAndAssess(new MuleGraphService.TransactionFlow(
                sourceAccount, muleAccount, 100000, "GHS", now.minusMinutes(5)
        ));

        // 2. Immediate outflow from mule account within 10 minutes (95,000 pesewas transferred out)
        var assessment = muleGraphService.recordFlowAndAssess(new MuleGraphService.TransactionFlow(
                muleAccount, destAccount, 95000, "GHS", now
        ));

        assertThat(assessment.rapidPassThroughCount()).isGreaterThanOrEqualTo(1);
        assertThat(assessment.drainRatio()).isGreaterThanOrEqualTo(0.90);
        assertThat(assessment.suspicionFactors()).anyMatch(f -> f.contains("RAPID_PASSTHROUGH"));
    }

    @Test
    @DisplayName("Algorithm 032: Bounded pagination prevents full-table memory scans")
    void testBoundedPagination() {
        String suspectAccount = "suspect-mule-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        // Feed flows to create a suspect
        for (int i = 0; i < 5; i++) {
            muleGraphService.recordFlowAndAssess(new MuleGraphService.TransactionFlow(
                    "source-" + i + "-" + UUID.randomUUID(), suspectAccount, 30000, "GHS", now.minusMinutes(10)
            ));
        }
        muleGraphService.recordFlowAndAssess(new MuleGraphService.TransactionFlow(
                suspectAccount, "exit-" + UUID.randomUUID(), 145000, "GHS", now
        ));

        // Test listSuspects with bounded page size
        var suspects = muleGraphService.listSuspects(0, 10);
        assertThat(suspects).isNotNull();

        // Test listRecentFlows with bounded limit
        var flows = muleGraphService.listRecentFlows(suspectAccount, 5, 0);
        assertThat(flows).isNotEmpty();
        assertThat(flows.size()).isLessThanOrEqualTo(5);
    }
}
