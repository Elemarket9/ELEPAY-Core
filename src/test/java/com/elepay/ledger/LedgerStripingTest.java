package com.elepay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class LedgerStripingTest {

    private final JdbcClient db = Mockito.mock(JdbcClient.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void validatesStripeCountBounds() {
        assertThatThrownBy(() -> new Ledger(db, json, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ledger(db, json, -5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ledger(db, json, 257))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(new Ledger(db, json, 1).stripeCount()).isEqualTo(1);
        assertThat(new Ledger(db, json, 64).stripeCount()).isEqualTo(64);
        assertThat(new Ledger(db, json, 256).stripeCount()).isEqualTo(256);
        assertThat(new Ledger(db, json).stripeCount()).isEqualTo(64);
    }

    @Test
    void stripeIndexIsDeterministicSafeAndWellDistributed() {
        Ledger ledger = new Ledger(db, json, 64);

        assertThat(ledger.stripeIndex(null)).isEqualTo(0);
        assertThat(new Ledger(db, json, 1).stripeIndex(UUID.randomUUID())).isEqualTo(0);

        UUID key = UUID.fromString("12345678-1234-5678-1234-567812345678");
        int first = ledger.stripeIndex(key);
        for (int i = 0; i < 20; i++) {
            assertThat(ledger.stripeIndex(key)).isEqualTo(first);
        }

        // Integer.MIN_VALUE and String with hashCode() == Integer.MIN_VALUE
        assertThat(ledger.stripeIndex(Integer.MIN_VALUE)).isBetween(0, 63);
        assertThat(ledger.stripeIndex("polygenelubricants")).isBetween(0, 63);

        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            int idx = ledger.stripeIndex(UUID.randomUUID());
            assertThat(idx).isBetween(0, 63);
            seen.add(idx);
        }
        assertThat(seen.size()).isGreaterThanOrEqualTo(55);
    }

    @Test
    void stripedCodeFormattingHandlesSingleDefaultAndMaxStripes() {
        Ledger single = new Ledger(db, json, 1);
        assertThat(single.stripedCode(Ledger.REVENUE, UUID.randomUUID())).isEqualTo(Ledger.REVENUE);

        Ledger defaultStripes = new Ledger(db, json, 64);
        String code64 = defaultStripes.stripedCode(Ledger.DEFERRED_REVENUE, UUID.randomUUID());
        assertThat(code64).matches("^system:deferred_revenue:GHS:s\\d{2}$");
        assertThat(Ledger.isStripeCode(code64)).isTrue();

        Ledger maxStripes = new Ledger(db, json, 256);
        boolean sawThreeDigitStripe = false;
        for (int i = 0; i < 500; i++) {
            String code256 = maxStripes.stripedCode(Ledger.REVENUE, "key-" + i);
            assertThat(Ledger.isStripeCode(code256)).isTrue();
            if (code256.matches(".*:s\\d{3}$")) {
                sawThreeDigitStripe = true;
            }
        }
        assertThat(sawThreeDigitStripe).isTrue();
    }

    @Test
    void isStripeCodeDistinguishesStripesFromBaseAndSpecialSystemCodes() {
        assertThat(Ledger.isStripeCode(null)).isFalse();
        assertThat(Ledger.isStripeCode("")).isFalse();
        assertThat(Ledger.isStripeCode(":s00")).isFalse();
        assertThat(Ledger.isStripeCode(Ledger.REVENUE)).isFalse();
        assertThat(Ledger.isStripeCode(Ledger.REVENUE_EARNED)).isFalse();
        assertThat(Ledger.isStripeCode(Ledger.DEFERRED_REVENUE)).isFalse();
        assertThat(Ledger.isStripeCode(Ledger.CLEARING_MOMO)).isFalse();
        assertThat(Ledger.isStripeCode("system:revenue:GHS:s0")).isFalse();
        assertThat(Ledger.isStripeCode("system:revenue:GHS:s0a")).isFalse();
        assertThat(Ledger.isStripeCode("system:revenue:GHS:s1234")).isFalse();

        assertThat(Ledger.isStripeCode("system:revenue:GHS:s00")).isTrue();
        assertThat(Ledger.isStripeCode("system:revenue:GHS:s63")).isTrue();
        assertThat(Ledger.isStripeCode("system:revenue:GHS:s127")).isTrue();
        assertThat(Ledger.isStripeCode("system:deferred_revenue:GHS:s05")).isTrue();
    }
}
