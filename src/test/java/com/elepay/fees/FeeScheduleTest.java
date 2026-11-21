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
        assertEquals(0, FeeSchedule.calculate(p, 49));
        assertEquals(1, FeeSchedule.calculate(p, 50));
        assertEquals(922_337_203_685_478L, FeeSchedule.calculate(p, Long.MAX_VALUE));
    }

    @Test void invalidPlansRejected() {
        assertThrows(IllegalArgumentException.class, () -> FeeSchedule.calculate(plan(), -1));
        assertThrows(IllegalArgumentException.class, () -> new FeeSchedule.Plan("bad", 0, 100,
                List.of(new FeeSchedule.Tier(100, 100))));
        assertThrows(IllegalArgumentException.class, () -> new FeeSchedule.Plan("bad", 0, 100,
                List.of(new FeeSchedule.Tier(100, 100), new FeeSchedule.Tier(100, 50))));
    }
}
