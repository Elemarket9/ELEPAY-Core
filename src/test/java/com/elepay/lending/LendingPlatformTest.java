package com.elepay.lending;

import com.elepay.PostgresTestBase;
import com.elepay.ledger.Ledger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class LendingPlatformTest extends PostgresTestBase {

    @Autowired private Ledger ledger;
    @Autowired private JdbcClient db;
    @Autowired
    private LendingConfigService configService;

    @Autowired
    private LoanProductService productService;

    @Autowired
    private LoanApplicationService applicationService;

    @Autowired
    private LoanContractService contractService;

    @Autowired
    private RepaymentSweepService sweepService;

    @Autowired
    private DelinquencyStateMachine delinquencyStateMachine;

    @Autowired
    private LoanRestructureService restructureService;

    @Test
    @DisplayName("Domain 8: Complete Lending Lifecycle - Product catalog, Score-card, Contract, Disbursement, Sweeps, Delinquency, Restructure, Write-off")
    void testCompleteLendingLifecycle() {
        // 1. Verify Product Catalog
        var products = productService.listActiveProducts();
        assertThat(products).isNotEmpty();
        assertThat(products.stream().anyMatch(p -> "SME_WC_01".equals(p.code()))).isTrue();
        assertThat(products.stream().anyMatch(p -> "MCA_01".equals(p.code()))).isTrue();
        assertThat(products.stream().anyMatch(p -> "FACTOR_01".equals(p.code()))).isTrue();

        var mcaProduct = productService.getProductByCode("MCA_01").orElseThrow();
        assertThat(mcaProduct.dailySweepBps()).isEqualTo(1500); // 15% sweep

        // 2. Loan Application Submission & Score-Card Evaluation
        UUID merchantId = UUID.randomUUID();
        var appSub = new LoanApplicationService.ApplicationSubmission(
                merchantId, "MERCHANT", mcaProduct.id(), 1_000_000L, 90,
                "Inventory expansion for holiday rush", 3_000_000L, null
        );

        var app = applicationService.submitAndEvaluate(appSub);
        assertThat(app.status()).isEqualTo("REVIEW_REQUIRED");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("test").header("alg","none").subject("lending-review-test").build(), List.of(new SimpleGrantedAuthority("ROLE_ops"))));
        applicationService.approveReviewed(app.id(), "test-verified-turnover");
        assertThat(app.creditScore()).isGreaterThanOrEqualTo(700);
        assertThat(app.riskBand()).isEqualTo("LOW");
        assertThat(app.maxApprovedPesewas()).isEqualTo(1_000_000L);

        // Test rejection case: excessive requested amount vs turnover
        var poorSub = new LoanApplicationService.ApplicationSubmission(
                UUID.randomUUID(), "MERCHANT", mcaProduct.id(), 15_000_000L, 90,
                "Too large request", 1_000_000L, null
        );
        var rejectedApp = applicationService.submitAndEvaluate(poorSub);
        assertThat(rejectedApp.status()).isEqualTo("REJECTED");
        assertThat(rejectedApp.riskBand()).isEqualTo("REJECT");

        // 3. Contract Generation & Repayment Schedule
        var contract = contractService.generateContract(app.id());
        assertThat(contract.status()).isEqualTo("ACTIVE");
        assertThat(contract.delinquencyState()).isEqualTo("PERFORMING");
        assertThat(contract.principalPesewas()).isEqualTo(1_000_000L);
        assertThat(contract.originationFeePesewas()).isGreaterThan(0L);
        assertThat(contract.totalInterestPesewas()).isGreaterThan(0L);

        var schedule = contractService.getSchedule(contract.id());
        assertThat(schedule).hasSize(3); // 90 days / 30 = 3 installments
        long sumPrincipal = schedule.stream().mapToLong(LoanContractService.Installment::principalDuePesewas).sum();
        assertThat(sumPrincipal).isEqualTo(contract.principalPesewas());

        // 4. Live Lending Policy Enforcement
        assertThat(configService.isLiveLendingEnabled()).isFalse();
        // Live disbursement must fail by policy
        assertThatThrownBy(() -> contractService.disburse(contract.id(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIVE_LENDING_DISABLED");

        assertThatThrownBy(() -> contractService.disburse(contract.id(), true)).hasMessageContaining("SIMULATIONS_NOT_PERMITTED");
        ledger.open("merchant", merchantId, "available", Ledger.GHS);
        ledger.openSystem(LoanContractService.FUNDING, "clearing", Ledger.GHS, false);
        String testSource = ledger.openSystem("system:test_loan_funds:GHS", "clearing", Ledger.GHS, true);
        ledger.post(Ledger.Posting.of("test-loan-fund", contract.id().toString(), "test_funding",List.of(Ledger.Leg.debit(testSource,1_000_000L),Ledger.Leg.credit(LoanContractService.FUNDING,1_000_000L))));
        configService.setLiveLendingEnabled(true);
        var disbursedContract = contractService.disburse(contract.id(), false);
        configService.setLiveLendingEnabled(false);
        SecurityContextHolder.clearContext();
        assertThat(disbursedContract.disbursedAt()).isNotNull();

        // 5. Automated Sales Sweep Repayments
        // Daily sales batch of 2,000,000 pesewas (20,000 GHS) -> 15% sweep = 300,000 pesewas
        db.sql("UPDATE loan_contract SET sweep_mandate = ?, sweep_authorized_by = ? WHERE id = ?").params("fixture-mandate","merchant-owner-fixture",contract.id()).update();
        String batchRef = ledger.post(Ledger.Posting.of("test-settlement", contract.id().toString(), "settlement", List.of(Ledger.Leg.debit(testSource,2_000_000L),Ledger.Leg.credit(Ledger.code("merchant",merchantId,"available",Ledger.GHS),2_000_000L)))).transactionId().toString();
        var sweepResult = sweepService.executeSweep(contract.id(), 2_000_000L, batchRef);
        assertThat(sweepResult.sweptAmountPesewas()).isEqualTo(300_000L);
        assertThat(sweepResult.remainingTotalDebtPesewas()).isLessThan(contract.totalRepaymentPesewas());

        var updatedSchedule = contractService.getSchedule(contract.id());
        assertThat(updatedSchedule.get(0).principalPaidPesewas()).isGreaterThan(0L);

        // 6. Delinquency State Machine & Penalty Fees
        // Evaluate contract as of today (due dates in future) -> PERFORMING
        var delinq1 = delinquencyStateMachine.evaluateContract(contract.id(), LocalDate.now());
        assertThat(delinq1.newState()).isEqualTo("PERFORMING");
        assertThat(delinq1.daysPastDue()).isEqualTo(0);

        // Evaluate contract as of 15 days past due date -> OVERDUE + Penalty accrued
        LocalDate futureDate = LocalDate.now().plusDays(45);
        var delinq2 = delinquencyStateMachine.evaluateContract(contract.id(), futureDate);
        assertThat(delinq2.newState()).isEqualTo("OVERDUE");
        assertThat(delinq2.daysPastDue()).isGreaterThan(7);
        assertThat(delinq2.accruedPenaltyPesewas()).isGreaterThan(0L);

        // 7. Restructure Workflow
        var restResult = restructureService.restructure(contract.id(), 60, "Borrower requested 60-day tenor extension");
        assertThat(restResult.newStatus()).isEqualTo("RESTRUCTURED");
        assertThat(restResult.extendedDays()).isEqualTo(60);

        var refreshed = contractService.getContract(contract.id()).orElseThrow();
        assertThat(refreshed.status()).isEqualTo("RESTRUCTURED");
        assertThat(refreshed.delinquencyState()).isEqualTo("PERFORMING");

        // 8. Write-Off Workflow
        var writeOffResult = restructureService.writeOff(contract.id(), "Unrecoverable loan balance write-off");
        assertThat(writeOffResult.status()).isEqualTo("WRITTEN_OFF");
        assertThat(writeOffResult.totalWrittenOffPesewas()).isGreaterThan(0L);

        var finalContract = contractService.getContract(contract.id()).orElseThrow();
        assertThat(finalContract.status()).isEqualTo("WRITTEN_OFF");
        assertThat(finalContract.outstandingPrincipalPesewas()).isEqualTo(0L);
    }
}
