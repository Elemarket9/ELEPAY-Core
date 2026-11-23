package com.elepay.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AbusePenaltyBoxTest {

    private final AtomicLong clock = new AtomicLong(0);

    private AbusePenaltyBox box(int threshold) {
        return new AbusePenaltyBox(true, threshold, Duration.ofMinutes(10), List.of("10.9.0.0/16", "2001:db8::/32"), null, clock::get);
    }

    @Test
    void bansEscalateOnRepeatOffences() {
        var b = box(10);
        long[] expected = {60, 300, 1_800, 7_200, 86_400, 86_400};
        for (long secs : expected) {
            assertThat(b.strike("1.2.3.4", 10)).isEqualTo(secs);
            assertThat(b.banRemainingSeconds("1.2.3.4")).isEqualTo(secs);
            clock.addAndGet(secs * 1000 + 1);
            assertThat(b.banRemainingSeconds("1.2.3.4")).isZero();
        }
    }

    @Test
    void strikesBelowThresholdDoNotBanAndExpireWithTheWindow() {
        var b = box(10);
        assertThat(b.strike("1.2.3.5", 9)).isZero();
        clock.addAndGet(Duration.ofMinutes(11).toMillis());
        assertThat(b.strike("1.2.3.5", 9)).isZero(); // window reset, so still under
        assertThat(b.banRemainingSeconds("1.2.3.5")).isZero();
    }

    @Test
    void escalationLevelIsForgottenAfter24Hours() {
        var b = box(10);
        b.strike("1.2.3.6", 10);
        clock.addAndGet(Duration.ofMinutes(1).plusHours(24).plusSeconds(1).toMillis());
        assertThat(b.strike("1.2.3.6", 10)).isEqualTo(60);
    }

    @Test
    void exemptRangesIncludingIpv6AreNeverBanned() {
        var b = box(1);
        assertThat(b.strike("10.9.200.1", 100)).isZero();
        assertThat(b.strike("2001:db8::1", 100)).isZero();
        assertThat(b.strike("10.10.0.1", 100)).isEqualTo(60);
    }

    @Test
    void hostNamesAreRejectedInExemptConfiguration() {
        assertThatThrownBy(() -> CidrBlock.parse("partner.example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CidrBlock.parse("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
        assertThat(CidrBlock.literal("evil.example.com:80")).isNull();
    }
}
