package com.elepay.fx;

import com.elepay.PostgresTestBase;
import com.elepay.ledger.Ledger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FxTreasuryRevaluationTest extends PostgresTestBase {

    @Autowired
    private FxTreasuryRevaluationService revalService;

    @Autowired
    private Ledger ledger;

    @Test
    @DisplayName("Pillar 1: Multi-currency treasury mark-to-market revaluation against BoG benchmark rates and gain/loss ledger balancing")
    void testTreasuryFxRevaluationLifecycle() {
        LocalDate valDate = LocalDate.of(2030, 1, 1)
                .plusDays(java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 20_000));
        String usdRevalAccount = "system:fx:position_reval_USD:GHS";
        String eurRevalAccount = "system:fx:position_reval_EUR:GHS";
        String unrealizedGainLossAccount = "system:fx:unrealized_gain_loss:GHS";

        long baseUsd = ledger.balance(usdRevalAccount);
        long baseEur = ledger.balance(eurRevalAccount);
        long baseGainLoss = ledger.balance(unrealizedGainLossAccount);

        // 1. Revaluation Scenario A: USD Treasury Position with Unrealized Gain
        // Position: $10,000.00 (1,000,000 cents)
        // Historical Cost: GHS 145,000.00 (14,500,000 pesewas) @ 14.50 GHS/USD
        // BoG Benchmark: 15.50 GHS/USD (155,000 e4)
        // Revalued GHS: 1,000,000 * 155,000 / 10,000 = 15,500,000 pesewas
        // Unrealized Gain: 15,500,000 - 14,500,000 = 1,000,000 pesewas (+GHS 10,000.00)
        var usdSnapshot = revalService.recordDailyPositionSnapshot(
                valDate,
                "USD",
                1_000_000L,
                155_000L,
                14_500_000L
        );

        assertThat(usdSnapshot.currency()).isEqualTo("USD");
        assertThat(usdSnapshot.revaluedGhsAmount()).isEqualTo(15_500_000L);
        assertThat(usdSnapshot.unrealizedGainLoss()).isEqualTo(1_000_000L);

        // Verify ledger entries
        assertThat(ledger.balance(usdRevalAccount)).isEqualTo(baseUsd + 1_000_000L);
        assertThat(ledger.balance(unrealizedGainLossAccount)).isEqualTo(baseGainLoss - 1_000_000L);

        // 2. Revaluation Scenario B: EUR Treasury Position with Unrealized Loss
        // Position: €5,000.00 (500,000 cents)
        // Historical Cost: GHS 85,000.00 (8,500,000 pesewas) @ 17.00 GHS/EUR
        // BoG Benchmark: 16.20 GHS/EUR (162,000 e4)
        // Revalued GHS: 500,000 * 162,000 / 10,000 = 8,100,000 pesewas
        // Unrealized Loss: 8,100,000 - 8,500,000 = -400,000 pesewas (-GHS 4,000.00)
        var eurSnapshot = revalService.recordDailyPositionSnapshot(
                valDate,
                "EUR",
                500_000L,
                162_000L,
                8_500_000L
        );

        assertThat(eurSnapshot.currency()).isEqualTo("EUR");
        assertThat(eurSnapshot.revaluedGhsAmount()).isEqualTo(8_100_000L);
        assertThat(eurSnapshot.unrealizedGainLoss()).isEqualTo(-400_000L);

        assertThat(ledger.balance(eurRevalAccount)).isEqualTo(baseEur - 400_000L);
        // Net unrealized gain/loss equity: -1,000,000 + 400,000 = -600,000
        assertThat(ledger.balance(unrealizedGainLossAccount)).isEqualTo(baseGainLoss - 600_000L);

        // 3. Query snapshots for the day
        var allSnapshots = revalService.listSnapshotsForDate(valDate);
        assertThat(allSnapshots).hasSize(2);
        assertThat(allSnapshots).extracting("currency").containsExactly("EUR", "USD");
    }

    @Test
    @DisplayName("Regression: Repeated recordDailyPositionSnapshot for same date and currency does not post duplicate ledger entries")
    void testRepeatedSnapshotSameDateCurrencyIsIdempotent() {
        LocalDate valDate = LocalDate.of(2090, 1, 1)
                .plusDays(java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 20_000));
        String gbpRevalAccount = "system:fx:position_reval_GBP:GHS";
        long baseGbp = ledger.balance(gbpRevalAccount);

        var first = revalService.recordDailyPositionSnapshot(valDate, "GBP", 100_00L, 195_000L, 180_000L);
        assertThat(first.unrealizedGainLoss()).isEqualTo(15_000L);
        assertThat(ledger.balance(gbpRevalAccount)).isEqualTo(baseGbp + 15_000L);

        // Re-run with identical parameters: must be idempotent (no second posting)
        var second = revalService.recordDailyPositionSnapshot(valDate, "GBP", 100_00L, 195_000L, 180_000L);
        assertThat(second.unrealizedGainLoss()).isEqualTo(15_000L);
        assertThat(ledger.balance(gbpRevalAccount)).isEqualTo(baseGbp + 15_000L);
        assertThat(ledger.integrity().ok()).isTrue();
    }
}
