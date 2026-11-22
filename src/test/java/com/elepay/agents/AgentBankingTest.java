package com.elepay.agents;

import com.elepay.PostgresTestBase;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AgentBankingTest extends PostgresTestBase {

    @Autowired
    private AgentConfigService configService;

    @Autowired
    private AgentOnboardingService onboardingService;

    @Autowired
    private AgentFloatService floatService;

    @Autowired
    private AgentCommissionEngine commissionEngine;

    @Autowired
    private AgentCashInService cashInService;

    @Autowired
    private AgentCashOutService cashOutService;

    @Autowired
    private AgentPortalService portalService;

    @Test
    @DisplayName("Domain 11: Authorized Agent Banking Platform - Onboarding, Float, Cash-In, Cash-Out, Commissions, Daily Payout, Portal")
    void testAgentBankingPlatform() {
        // 1. Live Agent Policy Enforcement
        assertThat(configService.isLiveAgentEnabled()).isFalse();

        // 2. Agent Onboarding & KYC Tiering
        String uniquePhone = "024" + (1000000 + (int)(Math.random() * 8999999));
        var reg = new AgentOnboardingService.AgentRegistration(
                "Kasoa Express Money Services", "Kwame Asante", "GHA-7829102-1",
                uniquePhone, "Shop 4, Kasoa High Street", "Kasoa", "Central Region",
                5.5342, -0.4219, "4321", "TIER_2"
        );
        var agent = onboardingService.registerAgent(reg);
        assertThat(agent.status()).isEqualTo("ACTIVE");
        assertThat(agent.agentCode()).startsWith("AGT-");
        assertThat(agent.kycTier()).isEqualTo("TIER_2");
        assertThat(agent.maxSingleCashInPesewas()).isEqualTo(500000L); // 5,000 GHS
        assertThat(onboardingService.verifyPin(agent.id(), "4321")).isTrue();
        assertThat(onboardingService.verifyPin(agent.id(), "0000")).isFalse();

        // 3. Float Account Management
        var initialFloat = floatService.getFloatAccount(agent.id()).orElseThrow();
        assertThat(initialFloat.availableFloatPesewas()).isEqualTo(0L);

        // Replenish float with 10,000 GHS (1,000,000 pesewas)
        String floatRef = "BANK-TXN-" + UUID.randomUUID().toString().substring(0, 8);
        var replenishedFloat = floatService.replenishFloat(agent.id(), 1_000_000L, floatRef);
        assertThat(replenishedFloat.availableFloatPesewas()).isEqualTo(1_000_000L);

        // 4. Cash-In (Deposit to customer) with PIN authorization
        // Live operation must fail by policy
        assertThatThrownBy(() -> cashInService.processCashIn(new AgentCashInService.CashInRequest(
                agent.id(), "4321", "0244112233", 50000L, false // 500 GHS
        ))).isInstanceOf(IllegalStateException.class).hasMessageContaining("LIVE_AGENT_DISABLED");

        // Simulation cash-in succeeds
        var cashInReceipt = cashInService.processCashIn(new AgentCashInService.CashInRequest(
                agent.id(), "4321", "0244112233", 50000L, true // 500 GHS
        ));
        assertThat(cashInReceipt.status()).isEqualTo("COMPLETED");
        assertThat(cashInReceipt.amountPesewas()).isEqualTo(50000L);
        assertThat(cashInReceipt.remainingFloatPesewas()).isEqualTo(950_000L); // 1,000,000 - 50,000
        assertThat(cashInReceipt.agentCommissionPesewas()).isGreaterThan(0L);

        // Verify float account has accrued commission
        var floatAfterCashIn = floatService.getFloatAccount(agent.id()).orElseThrow();
        assertThat(floatAfterCashIn.availableFloatPesewas()).isEqualTo(950_000L);
        assertThat(floatAfterCashIn.accruedCommissionPesewas()).isGreaterThan(0L);

        // 5. Cash-Out (Withdrawal from customer) with OTP Challenge
        // Step 1: Customer requests cash-out, system generates OTP challenge
        UUID customerWallet = UUID.randomUUID();
        var otpChallenge = cashOutService.generateOtpChallenge(agent.id(), "0244556677", customerWallet, 20000L); // 200 GHS
        assertThat(otpChallenge.otpCode()).hasSize(6);

        // Step 2: Agent confirms cash-out with customer OTP
        var cashOutReceipt = cashOutService.confirmCashOut(agent.id(), "0244556677", 20000L, otpChallenge.otpCode(), true);
        assertThat(cashOutReceipt.status()).isEqualTo("COMPLETED");
        assertThat(cashOutReceipt.amountPesewas()).isEqualTo(20000L);
        assertThat(cashOutReceipt.updatedFloatPesewas()).isEqualTo(970_000L); // 950,000 + 20,000
        assertThat(cashOutReceipt.agentCommissionPesewas()).isGreaterThan(0L);

        // 6. Tiered Commission Calculation Engine
        var commTier1 = commissionEngine.calculateCommission("TIER_1", "CASH_IN", 100000L);
        var commTier3 = commissionEngine.calculateCommission("TIER_3", "CASH_IN", 100000L);
        assertThat(commTier3.agentSharePesewas()).isGreaterThanOrEqualTo(commTier1.agentSharePesewas());

        // 7. Automated Daily Commission Payout Sweep
        long accruedBefore = floatService.getFloatAccount(agent.id()).orElseThrow().accruedCommissionPesewas();
        assertThat(accruedBefore).isGreaterThan(0L);

        var payoutSummary = commissionEngine.executeDailyCommissionPayouts();
        assertThat(payoutSummary.agentsPaid()).isGreaterThanOrEqualTo(1);
        assertThat(payoutSummary.totalCommissionPaidPesewas()).isGreaterThanOrEqualTo(accruedBefore);

        var floatAfterPayout = floatService.getFloatAccount(agent.id()).orElseThrow();
        assertThat(floatAfterPayout.accruedCommissionPesewas()).isEqualTo(0L);
        assertThat(floatAfterPayout.totalLifetimeCommissionPesewas()).isGreaterThanOrEqualTo(accruedBefore);

        // 8. Agent Portal API & Transaction Ledger
        var summary = portalService.getPortalSummary(agent.id());
        assertThat(summary.agentCode()).isEqualTo(agent.agentCode());
        assertThat(summary.availableFloatPesewas()).isGreaterThan(0L);
        assertThat(summary.todayTxnCount()).isGreaterThanOrEqualTo(2); // Cash-in + Cash-out

        var ledger = portalService.getTransactionLedger(agent.id(), null, 10, 0);
        assertThat(ledger).hasSizeGreaterThanOrEqualTo(3); // Float deposit, Cash-in, Cash-out, Commission payout
    }
}
