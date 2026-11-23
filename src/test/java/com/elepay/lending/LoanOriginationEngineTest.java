package com.elepay.lending;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LoanOriginationEngineTest extends PostgresTestBase {

    @Autowired
    private LoanOriginationEngine originationEngine;

    @Autowired
    private LoanApplicationService applicationService;

    @Autowired
    private LoanProductService productService;

    @Autowired
    private com.elepay.ledger.Ledger ledger;

    @Test
    @DisplayName("End-to-end loan origination, schedule generation, and disbursement into borrower account")
    void testOriginateAndDisburse() {
        var product = productService.getProductByCode("SME_WC_01").orElseThrow();
        UUID merchantId = UUID.randomUUID();

        // 1. Submit application
        var sub = new LoanApplicationService.ApplicationSubmission(
                merchantId, "MERCHANT", product.id(), 500_000L, 90,
                "Working capital expansion", 2_000_000L, null
        );
        var app = applicationService.submitAndEvaluate(sub);

        // Approve if review required
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(
                        Jwt.withTokenValue("test").header("alg", "none").subject("ops-tester").build(),
                        List.of(new SimpleGrantedAuthority("ROLE_ops"))
                )
        );
        if ("REVIEW_REQUIRED".equals(app.status())) {
            applicationService.approveReviewed(app.id(), "ops-approved-evidence");
        }

        // 2. Pre-fund lending capital account legitimately and originate/disburse
        ledger.openSystem(LoanOriginationEngine.LENDING_FUNDING_ACCOUNT, "clearing", com.elepay.ledger.Ledger.GHS, false);
        String testSource = ledger.openSystem("system:test_loan_funds:GHS", "clearing", com.elepay.ledger.Ledger.GHS, true);
        ledger.post(com.elepay.ledger.Ledger.Posting.of("test-loan-fund", app.id().toString(), "test_funding", List.of(
                com.elepay.ledger.Ledger.Leg.debit(testSource, 500_000L),
                com.elepay.ledger.Ledger.Leg.credit(LoanOriginationEngine.LENDING_FUNDING_ACCOUNT, 500_000L)
        )));

        var result = originationEngine.originateAndDisburse(
                app.id(),
                AmortizationScheduleEngine.AmortizationMethod.EQUAL_INSTALLMENT_FRENCH
        );

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("DISBURSED");
        assertThat(result.principalPesewas()).isEqualTo(500_000L);
        assertThat(result.disbursementJournalTxId()).isNotNull();
        assertThat(result.installmentCount()).isGreaterThan(0);
        assertThat(ledger.balance(com.elepay.ledger.Ledger.code("customer", merchantId, "available", com.elepay.ledger.Ledger.GHS)))
                .isEqualTo(500_000L);
    }
}
