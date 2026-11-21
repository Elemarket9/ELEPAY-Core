package com.elepay.fees;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeeScheduleTest {
    private static FeeSchedule.Plan plan() {
        return new FeeSchedule.Plan("checkout", 10, 500,
                List.of(new FeeSchedule.Tier(10_000, 100),
                        new FeeSchedule.Tier(Long.MAX_VALUE, 50)));
    }

    @Test void progressiveTiersAndCap() {
        assertEquals(10, FeeSchedule.calculate(plan(), 0));
        assertEquals(110, FeeSchedule.calculate(plan(), 10_000));
        assertEquals(160, FeeSchedule.calculate(plan(), 20_000));
        assertEquals(500, FeeSchedule.calculate(plan(), 1_000_000));
    }

    @Test void exactRoundingAndLargeValues() {
        var p = new FeeSchedule.Plan("tiny", 0, Long.MAX_VALUE,
                List.of(new FeeSchedule.Tier(Long.MAX_VALUE, 1)));
        assertEquals(0, FeeSchedule.calculate(p, 4_999));
        assertEquals(1, FeeSchedule.calculate(p, 5_000));
        assertEquals(922_337_203_685_478L, FeeSchedule.calculate(p, Long.MAX_VALUE));
    }

    @Test void configuredTiersPreserveLegacyFloorAndCap() {
        var p = FeeSchedule.configured("momo", 3, 100, 0, "10000:100,*:50");
        assertEquals(3, FeeSchedule.calculateFloor(p, 99));
        assertEquals(100, FeeSchedule.calculateFloor(p, 20_000));
        var legacy = FeeSchedule.configured("legacy", 0, Long.MAX_VALUE, 100, "");
        assertEquals(0, FeeSchedule.calculateFloor(legacy, 99));
        assertEquals(1, FeeSchedule.calculateFloor(legacy, 100));
        assertThrows(IllegalArgumentException.class, () -> FeeSchedule.configured("bad", 0, 100, 0, "100:20"));
    }

    @Test void tenThousandLegacyAmountsMatchOriginalArithmetic() {
        var random = new java.util.Random(520);
        for (int i = 0; i < 10_000; i++) {
            long a = random.nextLong(500_000_000L) + 1;
            int bps = random.nextInt(10_001);
            long fixed = random.nextInt(1000);
            var p = FeeSchedule.configured("legacy", fixed, Long.MAX_VALUE, bps, "");
            assertEquals(a * bps / 10_000 + fixed, FeeSchedule.calculateFloor(p, a));
        }
    }

    @Test void invalidPlansRejected() {
        assertThrows(IllegalArgumentException.class, () -> FeeSchedule.calculate(plan(), -1));
        assertThrows(IllegalArgumentException.class, () -> new FeeSchedule.Plan("bad", 0, 100,
                List.of(new FeeSchedule.Tier(100, 100))));
        assertThrows(IllegalArgumentException.class, () -> new FeeSchedule.Plan("bad", 0, 100,
                List.of(new FeeSchedule.Tier(100, 100), new FeeSchedule.Tier(100, 50))));
    }
}
