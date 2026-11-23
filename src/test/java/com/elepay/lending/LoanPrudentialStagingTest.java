package com.elepay.lending;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LoanPrudentialStagingTest extends PostgresTestBase {

    @Autowired
    private LoanPrudentialStagingService stagingService;

    @Autowired
    private LoanProductService productService;

    @Test
    @DisplayName("Evaluate portfolio prudential staging across performing and non-performing loans")
    void testPortfolioPrudentialStaging() {
        // Test BOG regulatory DPD staging mappings
        assertThat(LoanPrudentialStagingService.PrudentialStage.fromDpd(0))
                .isEqualTo(LoanPrudentialStagingService.PrudentialStage.STAGE_1_PERFORMING);
        assertThat(LoanPrudentialStagingService.PrudentialStage.fromDpd(30))
                .isEqualTo(LoanPrudentialStagingService.PrudentialStage.STAGE_1_PERFORMING);
        assertThat(LoanPrudentialStagingService.PrudentialStage.fromDpd(31))
                .isEqualTo(LoanPrudentialStagingService.PrudentialStage.STAGE_2_UNDERPERFORMING);
        assertThat(LoanPrudentialStagingService.PrudentialStage.fromDpd(60))
                .isEqualTo(LoanPrudentialStagingService.PrudentialStage.STAGE_2_UNDERPERFORMING);
        assertThat(LoanPrudentialStagingService.PrudentialStage.fromDpd(61))
                .isEqualTo(LoanPrudentialStagingService.PrudentialStage.STAGE_2_UNDERPERFORMING);
        assertThat(LoanPrudentialStagingService.PrudentialStage.fromDpd(90))
                .isEqualTo(LoanPrudentialStagingService.PrudentialStage.STAGE_2_UNDERPERFORMING);
        assertThat(LoanPrudentialStagingService.PrudentialStage.fromDpd(91))
                .isEqualTo(LoanPrudentialStagingService.PrudentialStage.STAGE_3_NON_PERFORMING);
        assertThat(LoanPrudentialStagingService.PrudentialStage.fromDpd(180))
                .isEqualTo(LoanPrudentialStagingService.PrudentialStage.STAGE_3_NON_PERFORMING);

        var report1 = stagingService.evaluatePortfolioAndPostImpairment(LocalDate.now());
        assertThat(report1).isNotNull();
        assertThat(report1.totalEvaluatedContracts()).isGreaterThanOrEqualTo(0);
        assertThat(report1.totalExposurePesewas()).isGreaterThanOrEqualTo(0L);
        assertThat(report1.totalImpairmentRequiredPesewas()).isGreaterThanOrEqualTo(0L);

        // Multiple ECL runs on the same date do not collide due to unique idempotency key
        var report2 = stagingService.evaluatePortfolioAndPostImpairment(LocalDate.now());
        assertThat(report2).isNotNull();
    }
}
