package com.elepay.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import com.elepay.common.ProportionalAllocation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SplitReversalInvariantTest {
    @Test
    void repeatedOnePesewaReversalsCannotOverdrawAnyParticipant() {
        long refunded = 0, fee = 0, platform = 0, pending = 70;
        UUID merchant = UUID.randomUUID();
        for (int i = 0; i < 100; i++) {
            var line = new CheckoutService.Allocation(0, merchant, "Test", "sale", null, 100, 10,
                    refunded, fee, 0, pending, 0, null, null, 20, 0, platform, 0, 0,
                    20 - platform, 10 - fee, false, null);
            var take = CheckoutService.take(line, 1);
            assertThat(take.seller() + take.fee() + take.platform()).isEqualTo(1);
            assertThat(take.seller()).isBetween(0L, pending);
            assertThat(take.fee()).isBetween(0L, 10 - fee);
            assertThat(take.platform()).isBetween(0L, 20 - platform);
            pending -= take.seller(); fee += take.fee(); platform += take.platform(); refunded++;
        }
        assertThat(pending).isZero();
        assertThat(fee).isEqualTo(10);
        assertThat(platform).isEqualTo(20);
    }

    @Test
    void largeBasisPointCalculationCannotWrap() {
        assertThat(ProportionalAllocation.basisPoints(Long.MAX_VALUE, 10_000)).isEqualTo(Long.MAX_VALUE);
        assertThat(ProportionalAllocation.basisPoints(Long.MAX_VALUE, 5_000)).isEqualTo(Long.MAX_VALUE / 2);
        assertThat(ProportionalAllocation.allocate(100, List.of(70L, 10L, 20L))).containsExactly(70L, 10L, 20L);
    }
}
