package com.elepay.enterprise.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.PostgresTestBase;
import com.elepay.common.ApiException;
import com.elepay.enterprise.accounts.EnterpriseManagedAccountService;
import com.elepay.ledger.Ledger;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Enterprise Treasury & Funding Engine Automated Verification Suite.
 */
class EnterpriseTreasuryTest extends PostgresTestBase {

    @Autowired
    private EnterpriseTreasuryService treasuryService;

    @Autowired
    private EnterpriseManagedAccountService enterpriseService;

    @Autowired
    private Ledger ledger;

    private UUID enterpriseId;

    @BeforeEach
    void setUp() {
        var ent = enterpriseService.registerEnterprise(
                "TREASURY-TEST-" + UUID.randomUUID().toString().substring(0, 8),
                "Treasury Test Enterprise",
                "treasury@test.com",
                "+233241230001",
                "https://api.test.com/webhooks"
        );
        enterpriseId = ent.id();
    }

    @Test
    @DisplayName("Treasury Setup & Segregation: Initializes 4 designated segregated accounts")
    void testTreasuryAccountSegregation() {
        treasuryService.setupEnterpriseTreasury(enterpriseId, "GHS");

        for (TreasuryAccountType type : TreasuryAccountType.values()) {
            EnterpriseTreasuryAccount acc = treasuryService.getTreasuryAccount(enterpriseId, type, "GHS");
            assertThat(acc).isNotNull();
            assertThat(acc.enterpriseId()).isEqualTo(enterpriseId);
            assertThat(acc.accountType()).isEqualTo(type);
            assertThat(acc.strictSegregation()).isTrue();
            assertThat(acc.balancePesewas()).isZero();
        }
    }

    @Test
    @DisplayName("Enterprise Prefunding Deposit: Updates balance, posts balanced ledger entry, records audit trail")
    void testPrefundingDeposit() {
        long depositPesewas = 500_000L; // GH₵ 5,000.00
        String ref = "PREFUND-" + UUID.randomUUID().toString().substring(0, 8);

        PrefundingLedgerEntry entry = treasuryService.depositPrefunding(
                enterpriseId,
                depositPesewas,
                "GHS",
                ref,
                "Initial liquidity prefunding"
        );

        assertThat(entry).isNotNull();
        assertThat(entry.amountPesewas()).isEqualTo(depositPesewas);
        assertThat(entry.entryType()).isEqualTo(PrefundingEntryType.DEPOSIT);
        assertThat(entry.balanceAfterPesewas()).isEqualTo(depositPesewas);
        assertThat(entry.ledgerTxId()).isNotNull();

        // Verify treasury account balance
        EnterpriseTreasuryAccount prefundingAcc = treasuryService.getTreasuryAccount(
                enterpriseId, TreasuryAccountType.PREFUNDING, "GHS"
        );
        assertThat(prefundingAcc.balancePesewas()).isEqualTo(depositPesewas);
        assertThat(prefundingAcc.availablePesewas()).isEqualTo(depositPesewas);

        // Verify authoritative ledger balance
        long ledgerBal = ledger.balance(Ledger.code("enterprise", enterpriseId, "holding", "GHS"));
        assertThat(ledgerBal).isEqualTo(depositPesewas);
    }

    @Test
    @DisplayName("External Receivables Tracking: Records receivable and settles upon reconciliation")
    void testExternalReceivablesLifecycle() {
        String orderRef = "ORD-RCV-" + UUID.randomUUID().toString().substring(0, 8);
        long orderAmountPesewas = 152_000L; // GH₵ 1,520.00

        // 1. Record receivable incurred
        PrefundingLedgerEntry inc = treasuryService.recordReceivable(enterpriseId, orderRef, orderAmountPesewas, "GHS");
        assertThat(inc.entryType()).isEqualTo(PrefundingEntryType.RECEIVABLE_INCURRED);
        assertThat(inc.balanceAfterPesewas()).isEqualTo(orderAmountPesewas);

        EnterpriseTreasuryAccount recvAcc = treasuryService.getTreasuryAccount(
                enterpriseId, TreasuryAccountType.EXTERNAL_RECEIVABLES, "GHS"
        );
        assertThat(recvAcc.balancePesewas()).isEqualTo(orderAmountPesewas);

        // 2. Settle receivable when funds arrive
        PrefundingLedgerEntry stl = treasuryService.settleReceivable(enterpriseId, orderRef, orderAmountPesewas, "GHS");
        assertThat(stl.entryType()).isEqualTo(PrefundingEntryType.RECEIVABLE_SETTLED);
        assertThat(stl.balanceAfterPesewas()).isZero();

        EnterpriseTreasuryAccount settleAcc = treasuryService.getTreasuryAccount(
                enterpriseId, TreasuryAccountType.SAFEGUARDED_SETTLEMENT, "GHS"
        );
        assertThat(settleAcc.balancePesewas()).isEqualTo(orderAmountPesewas);
    }

    @Test
    @DisplayName("Funding Shortfall Detection: Triggers SHORTFALL and places HOLD when funds insufficient")
    void testFundingShortfallDetectionAndHold() {
        long requiredPesewas = 200_000L; // GH₵ 2,000.00 required

        // Currently 0 prefunding deposited -> should trigger SHORTFALL & HOLD_PLACED
        var evaluation = treasuryService.checkFundingSufficiency(
                enterpriseId, requiredPesewas, "GHS", "SETTLEMENT_RELEASE"
        );

        assertThat(evaluation.status()).isEqualTo(ShortfallStatus.SHORTFALL);
        assertThat(evaluation.actionTaken()).isEqualTo(ShortfallAction.HOLD_PLACED);
        assertThat(evaluation.hasShortfall()).isTrue();
        assertThat(evaluation.shortfallPesewas()).isEqualTo(200_000L);
        assertThat(evaluation.shortfallId()).isNotNull();

        // Now deposit sufficient prefunding
        treasuryService.depositPrefunding(enterpriseId, 300_000L, "GHS", "DEP-SUFF-" + UUID.randomUUID(), "Sufficient prefund");

        // Now available = 300,000, required = 200,000 (150% coverage > 120%)
        var evalAfter = treasuryService.checkFundingSufficiency(
                enterpriseId, requiredPesewas, "GHS", "SETTLEMENT_RELEASE"
        );

        assertThat(evalAfter.status()).isEqualTo(ShortfallStatus.SUFFICIENT);
        assertThat(evalAfter.actionTaken()).isEqualTo(ShortfallAction.NONE);
        assertThat(evalAfter.hasShortfall()).isFalse();
    }

    @Test
    @DisplayName("Liquidity Forecasting: Calculates forward-looking obligations and liquidity position")
    void testLiquidityForecastGeneration() {
        treasuryService.depositPrefunding(enterpriseId, 500_000L, "GHS", "PREFUND-FC-" + UUID.randomUUID(), "Forecast prefund");
        treasuryService.recordReceivable(enterpriseId, "FC-ORD-" + UUID.randomUUID(), 100_000L, "GHS");

        LiquidityForecast forecast = treasuryService.generateLiquidityForecast(enterpriseId, "GHS");
        assertThat(forecast).isNotNull();
        assertThat(forecast.currentPrefundingPesewas()).isEqualTo(500_000L);
        assertThat(forecast.pendingReceivablesPesewas()).isEqualTo(100_000L);
        assertThat(forecast.status()).isEqualTo(ShortfallStatus.SUFFICIENT);
    }
}
