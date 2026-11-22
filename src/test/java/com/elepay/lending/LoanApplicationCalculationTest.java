package com.elepay.lending;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class LoanApplicationCalculationTest {

    private final LoanApplicationService service = new LoanApplicationService(null, null);

    private final LoanProductService.LoanProduct standardProduct = new LoanProductService.LoanProduct(
            UUID.randomUUID(), "SME_WC_01", "SME Working Capital", "SME_WORKING_CAPITAL",
            500_000L, 50_000_000L, 30, 365, 2400, 200, 500, 0, "ACTIVE"
    );

    private final LoanProductService.LoanProduct factoringProduct = new LoanProductService.LoanProduct(
            UUID.randomUUID(), "FACTOR_01", "Invoice Factoring", "INVOICE_FACTORING",
            1_000_000L, 100_000_000L, 15, 90, 1800, 100, 400, 0, "ACTIVE"
    );

    @Test
    @DisplayName("Evaluate score card: reject below minimum amount")
    void testBelowMinimumAmount() {
        var sub = new LoanApplicationService.ApplicationSubmission(
                UUID.randomUUID(), "MERCHANT", standardProduct.id(),
                400_000L, 60, "Expansion", 2_000_000L, null
        );
        var eval = service.evaluateScoreCard(standardProduct, sub);
        assertThat(eval.decision()).isEqualTo("REJECTED");
        assertThat(eval.riskBand()).isEqualTo("REJECT");
        assertThat(eval.maxApprovedPesewas()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Evaluate score card: reject above ceiling")
    void testAboveCeiling() {
        var sub = new LoanApplicationService.ApplicationSubmission(
                UUID.randomUUID(), "MERCHANT", standardProduct.id(),
                60_000_000L, 60, "Expansion", 200_000_000L, null
        );
        var eval = service.evaluateScoreCard(standardProduct, sub);
        assertThat(eval.decision()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("Evaluate score card: reject tenor outside bounds")
    void testTenorOutsideBounds() {
        var sub = new LoanApplicationService.ApplicationSubmission(
                UUID.randomUUID(), "MERCHANT", standardProduct.id(),
                1_000_000L, 10, "Expansion", 5_000_000L, null
        );
        var eval = service.evaluateScoreCard(standardProduct, sub);
        assertThat(eval.decision()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("Evaluate score card: reject non-positive turnover")
    void testNonPositiveTurnover() {
        var sub = new LoanApplicationService.ApplicationSubmission(
                UUID.randomUUID(), "MERCHANT", standardProduct.id(),
                1_000_000L, 60, "Expansion", 0L, null
        );
        var eval = service.evaluateScoreCard(standardProduct, sub);
        assertThat(eval.decision()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("Evaluate score card: excellent cash-flow (ratio <= 0.5) achieves LOW risk and 100% approval")
    void testExcellentCashFlow() {
        var sub = new LoanApplicationService.ApplicationSubmission(
                UUID.randomUUID(), "MERCHANT", standardProduct.id(),
                1_000_000L, 60, "Inventory", 3_000_000L, null
        );
        var eval = service.evaluateScoreCard(standardProduct, sub);
        assertThat(eval.decision()).isEqualTo("APPROVED");
        assertThat(eval.riskBand()).isEqualTo("LOW");
        assertThat(eval.score()).isGreaterThanOrEqualTo(700);
        assertThat(eval.maxApprovedPesewas()).isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("Evaluate score card: invoice factoring bonus adds 50 points")
    void testInvoiceFactoringBonus() {
        var sub = new LoanApplicationService.ApplicationSubmission(
                UUID.randomUUID(), "MERCHANT", factoringProduct.id(),
                2_000_000L, 30, "Receivable discount", 2_500_000L, "INV-2026-99"
        );
        var eval = service.evaluateScoreCard(factoringProduct, sub);
        assertThat(eval.decision()).isEqualTo("APPROVED");
        assertThat(eval.score()).isGreaterThanOrEqualTo(700);
    }

    @Test
    @DisplayName("Evaluate score card: high debt-to-turnover ratio gets risk-adjusted reduction")
    void testHighDebtRatioAdjustment() {
        // Requested 4,000,000 against monthly turnover 1,500,000 (ratio 2.66 > 2.0 -> baseScore 550)
        var sub = new LoanApplicationService.ApplicationSubmission(
                UUID.randomUUID(), "MERCHANT", standardProduct.id(),
                4_000_000L, 90, "Working capital", 1_500_000L, null
        );
        var eval = service.evaluateScoreCard(standardProduct, sub);
        assertThat(eval.decision()).isEqualTo("APPROVED");
        assertThat(eval.riskBand()).isEqualTo("HIGH");
        // 60% of 4,000,000 = 2,400,000
        assertThat(eval.maxApprovedPesewas()).isEqualTo(2_400_000L);
    }
}
