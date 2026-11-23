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
        var report = stagingService.evaluatePortfolioAndPostImpairment(LocalDate.now());

        assertThat(report).isNotNull();
        assertThat(report.totalEvaluatedContracts()).isGreaterThanOrEqualTo(0);
        assertThat(report.totalExposurePesewas()).isGreaterThanOrEqualTo(0L);
        assertThat(report.totalImpairmentRequiredPesewas()).isGreaterThanOrEqualTo(0L);
    }
}
