package com.elepay.fees;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * Pure integer-minor-unit fee calculation. Configuration must be validated before activation.
 * No floating point, implicit currency conversion or hidden minimum charges.
 */
public final class FeeSchedule {
    private FeeSchedule() {}

    public record Tier(long upperInclusive, int basisPoints) {
        public Tier {
            if (upperInclusive <= 0 || basisPoints < 0 || basisPoints > 10_000)
                throw new IllegalArgumentException("Invalid tier");
        }
    }

    public record Plan(String name, long fixedMinor, long capMinor, List<Tier> tiers) {
        public Plan {
            Objects.requireNonNull(name);
            if (name.isBlank() || fixedMinor < 0 || capMinor < 0)
                throw new IllegalArgumentException("Invalid plan");
            tiers = List.copyOf(Objects.requireNonNull(tiers));
            long previous = 0;
            for (Tier tier : tiers) {
                if (tier.upperInclusive() <= previous)
                    throw new IllegalArgumentException("Tiers must be strictly ascending");
                previous = tier.upperInclusive();
            }
            if (tiers.isEmpty() || previous != Long.MAX_VALUE)
                throw new IllegalArgumentException("Last tier must be unbounded");
        }
    }

    /** Progressive tiers; basis points rounded half-up once at the total level. */
    public static long calculate(Plan plan, long amountMinor) {
        Objects.requireNonNull(plan);
        if (amountMinor < 0) throw new IllegalArgumentException("Negative amount");
        BigInteger weighted = BigInteger.ZERO;
        long lower = 0;
        for (Tier tier : plan.tiers()) {
            if (amountMinor <= lower) break;
            long portion = Math.min(amountMinor, tier.upperInclusive()) - lower;
            weighted = weighted.add(BigInteger.valueOf(portion)
                    .multiply(BigInteger.valueOf(tier.basisPoints())));
            lower = tier.upperInclusive();
        }
        BigInteger variable = weighted.add(BigInteger.valueOf(5_000))
                .divide(BigInteger.valueOf(10_000));
        BigInteger total = variable.add(BigInteger.valueOf(plan.fixedMinor()));
        return total.min(BigInteger.valueOf(plan.capMinor())).longValueExact();
    }
}
