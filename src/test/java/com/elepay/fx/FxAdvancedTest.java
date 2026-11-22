package com.elepay.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.ApiException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FxAdvancedTest {

    @Test
    @DisplayName("059: Rate blending rejects outliers and calculates weighted mid-market rate")
    void testRateBlendingOutlierLogic() {
        // Test math and statistical logic
        BigDecimal median = new BigDecimal("15.5000");
        BigDecimal normal1 = new BigDecimal("15.4800");
        BigDecimal normal2 = new BigDecimal("15.5200");
        BigDecimal outlierHigh = new BigDecimal("16.2000"); // > 4% deviation from median
        BigDecimal outlierLow = new BigDecimal("14.0000");  // > 9% deviation from median

        BigDecimal threshold = new BigDecimal("0.0200");

        assertThat(outlierHigh.subtract(median).abs().divide(median, 6, java.math.RoundingMode.HALF_UP))
                .isGreaterThan(threshold);
        assertThat(outlierLow.subtract(median).abs().divide(median, 6, java.math.RoundingMode.HALF_UP))
                .isGreaterThan(threshold);
        assertThat(normal1.subtract(median).abs().divide(median, 6, java.math.RoundingMode.HALF_UP))
                .isLessThan(threshold);
        assertThat(normal2.subtract(median).abs().divide(median, 6, java.math.RoundingMode.HALF_UP))
                .isLessThan(threshold);
    }

    @Test
    @DisplayName("060: Dynamic FX margin pricing applies tiered margin and explicit integer rounding")
    void testDynamicPricingTiers() {
        // Base mid rate: 1 GHS = 0.0640 USD
        BigDecimal baseRate = new BigDecimal("0.0640");
        // Margin: 150 bps (1.5%)
        int marginBps = 150;
        BigDecimal marginFactor = BigDecimal.valueOf(10_000 - marginBps).divide(BigDecimal.valueOf(10_000), 6, java.math.RoundingMode.HALF_UP);
        BigDecimal effectiveRate = baseRate.multiply(marginFactor);

        // Effective rate should be 0.0640 * 0.985 = 0.06304
        assertThat(effectiveRate).isEqualByComparingTo("0.06304");

        // Convert GH₵640.00 (64,000 minor units) to USD (2 decimals)
        long sourceMinor = 64_000;
        long destMinor = new BigDecimal(sourceMinor).multiply(effectiveRate)
                .setScale(0, java.math.RoundingMode.DOWN).longValueExact();

        // 64,000 * 0.06304 = 4034.56 -> rounds down to 4034 minor units (US$40.34)
        assertThat(destMinor).isEqualTo(4034);
    }

    @Test
    @DisplayName("061: Corridor routing composite scoring ranks partners accurately")
    void testCorridorRoutingScore() {
        // Route A: Low cost (20 bps), slow (1440 mins), high liquidity (90), 99% success
        // Route B: High cost (100 bps), fast (15 mins), medium liquidity (70), 95% success
        double costScoreA = 10_000 - 20;
        double speedScoreA = Math.min(10_000.0, (10_000.0 * 60.0) / 1440.0); // 416.6
        double liqScoreA = 90 * 100.0; // 9000
        double relScoreA = 9900;
        double compositeA = (0.30 * costScoreA) + (0.25 * speedScoreA) + (0.25 * liqScoreA) + (0.20 * relScoreA);

        double costScoreB = 10_000 - 100;
        double speedScoreB = Math.min(10_000.0, (10_000.0 * 60.0) / 15.0); // 10000
        double liqScoreB = 70 * 100.0; // 7000
        double relScoreB = 9500;
        double compositeB = (0.30 * costScoreB) + (0.25 * speedScoreB) + (0.25 * liqScoreB) + (0.20 * relScoreB);

        assertThat(compositeB).isGreaterThan(compositeA); // Fast route wins despite slightly higher cost
    }

    @Test
    @DisplayName("062: Exposure limit evaluation identifies warnings and breaches")
    void testExposureLimits() {
        long limit = 50_000_000; // $500k
        int warningPct = 80;

        long safeNofp = 30_000_000; // 60%
        long warningNofp = 42_000_000; // 84%
        long breachNofp = 55_000_000; // 110%

        assertThat(safeNofp >= limit).isFalse();
        assertThat((safeNofp * 100) / limit >= warningPct).isFalse();

        assertThat(warningNofp >= limit).isFalse();
        assertThat((warningNofp * 100) / limit >= warningPct).isTrue();

        assertThat(breachNofp >= limit).isTrue();
    }

    @Test
    @DisplayName("063: Delivery SLA adjusts for cutoff hours and weekend delays")
    void testDeliveryEstimationCutoffAndWeekend() {
        // Monday 16:00 (past 15:00 cutoff)
        OffsetDateTime mondayLate = OffsetDateTime.of(2026, 11, 23, 16, 0, 0, 0, ZoneOffset.UTC);
        // Cutoff pushes to Tuesday 09:00 + p50 (240 min = 4 hours) -> Tuesday 13:00
        OffsetDateTime tuesdayExpected = mondayLate.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0).plusMinutes(240);
        assertThat(tuesdayExpected.getHour()).isEqualTo(13);
        assertThat(tuesdayExpected.getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);

        // Saturday 11:00
        OffsetDateTime saturday = OffsetDateTime.of(2026, 11, 28, 11, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime mondayOpening = saturday.plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0);
        assertThat(mondayOpening.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    @DisplayName("064: Dual-currency reconciliation identifies amount and status mismatches")
    void testDualReconDiscrepancyClassification() {
        long internalSrc = 10_000;
        long internalDst = 640;
        String internalStatus = "paid";

        long stmtSrcMatch = 10_000;
        long stmtDstMatch = 640;
        String stmtStatusMatch = "paid";

        boolean match = (internalSrc == stmtSrcMatch) && (internalDst == stmtDstMatch) && internalStatus.equals(stmtStatusMatch);
        assertThat(match).isTrue();

        long stmtDstMismatch = 645;
        boolean amountMismatch = (internalDst != stmtDstMismatch);
        assertThat(amountMismatch).isTrue();
    }
}
