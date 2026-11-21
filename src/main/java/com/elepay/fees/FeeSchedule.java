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

    /** Parse ascending inclusive-upper-bound:bps tiers, with * for the final unbounded tier. */
    public static Plan configured(String name, long fixed, long cap, int fallbackBps, String tiers) {
        if (tiers == null || tiers.isBlank()) return new Plan(name, fixed, cap, List.of(new Tier(Long.MAX_VALUE, fallbackBps)));
        var parsed = new java.util.ArrayList<Tier>();
        for (String segment : tiers.split(",", -1)) {
            String[] fields = segment.trim().split(":", -1);
            if (fields.length != 2) throw new IllegalArgumentException("Invalid fee tier: " + segment);
            long upper = fields[0].trim().equals("*") ? Long.MAX_VALUE : Long.parseLong(fields[0].trim());
            parsed.add(new Tier(upper, Integer.parseInt(fields[1].trim())));
        }
        return new Plan(name, fixed, cap, parsed);
    }

    /** Progressive tiers; basis points rounded half-up once at the total level. */
    public static long calculate(Plan plan, long amountMinor) {
        return calculate(plan, amountMinor, true);
    }

    /** Legacy checkout rounds down; this overload preserves existing contractual pricing. */
    public static long calculateFloor(Plan plan, long amountMinor) {
        return calculate(plan, amountMinor, false);
    }

    private static long calculate(Plan plan, long amountMinor, boolean halfUp) {
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
        BigInteger variable = weighted.add(BigInteger.valueOf(halfUp ? 5_000 : 0))
                .divide(BigInteger.valueOf(10_000));
        BigInteger total = variable.add(BigInteger.valueOf(plan.fixedMinor()));
        return total.min(BigInteger.valueOf(plan.capMinor())).longValueExact();
    }
}
