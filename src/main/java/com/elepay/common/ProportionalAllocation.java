package com.elepay.common;

import java.math.BigInteger;
import java.util.*;

/**
 * Pure integer minor unit allocation using the Hare-Niemeyer (Largest Remainder) method.
 *
 * <p>Guarantees financial correctness:
 * <ul>
 *   <li>The sum of allocated integer minor units strictly equals the input total.</li>
 *   <li>No fractional or floating-point currency units are ever created, truncated, or lost.</li>
 *   <li>Each share receives its integer floor allocation: floor(total * weight / totalWeight).</li>
 *   <li>Any remaining units are distributed deterministically to the shares with the largest fractional remainders.</li>
 *   <li>Ties in fractional remainders are broken deterministically by original input index.</li>
 * </ul>
 */
public final class ProportionalAllocation {
    private ProportionalAllocation() {}

    /** Exact floor of amount times basis points without intermediate long overflow. */
    public static long basisPoints(long amount, int bps) {
        if (amount < 0 || bps < 0 || bps > 10_000)
            throw new IllegalArgumentException("Amount must be non-negative and basis points between 0 and 10000");
        return Math.addExact(Math.multiplyExact(amount / 10_000L, bps),
                Math.multiplyExact(amount % 10_000L, bps) / 10_000L);
    }

    /**
     * Allocates {@code totalMinor} units across the given {@code weights}.
     *
     * @param totalMinor total integer minor units (e.g. pesewas or cents) to distribute
     * @param weights list of non-negative integer weights/shares
     * @return list of allocated integer minor units in the same order as weights, whose sum equals totalMinor
     */
    public static List<Long> allocate(long totalMinor, List<Long> weights) {
        Objects.requireNonNull(weights, "weights cannot be null");
        if (totalMinor < 0) {
            throw new IllegalArgumentException("Total amount must be non-negative: " + totalMinor);
        }
        if (weights.isEmpty()) {
            if (totalMinor == 0) return List.of();
            throw new IllegalArgumentException("Cannot distribute positive amount across empty weights");
        }

        int n = weights.size();
        BigInteger totalWeight = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            Long w = weights.get(i);
            if (w == null || w < 0) {
                throw new IllegalArgumentException("Weight at index " + i + " must be non-negative: " + w);
            }
            totalWeight = totalWeight.add(BigInteger.valueOf(w));
        }

        if (totalWeight.signum() == 0) {
            if (totalMinor == 0) {
                return Collections.nCopies(n, 0L);
            }
            throw new IllegalArgumentException("Sum of weights must be positive to distribute " + totalMinor);
        }

        if (totalMinor == 0) {
            return Collections.nCopies(n, 0L);
        }

        BigInteger totalBig = BigInteger.valueOf(totalMinor);
        long[] result = new long[n];
        record RemainderItem(int index, BigInteger remainder) {}
        List<RemainderItem> remainders = new ArrayList<>(n);

        long sumFloor = 0;
        for (int i = 0; i < n; i++) {
            long w = weights.get(i);
            if (w == 0) {
                result[i] = 0;
                remainders.add(new RemainderItem(i, BigInteger.ZERO));
                continue;
            }
            BigInteger product = totalBig.multiply(BigInteger.valueOf(w));
            BigInteger[] divRem = product.divideAndRemainder(totalWeight);
            long floor = divRem[0].longValueExact();
            result[i] = floor;
            sumFloor = Math.addExact(sumFloor, floor);
            remainders.add(new RemainderItem(i, divRem[1]));
        }

        long remainderToDistribute = Math.subtractExact(totalMinor, sumFloor);
        if (remainderToDistribute > 0) {
            // Sort by remainder descending, tie-break by original index ascending
            remainders.sort((a, b) -> {
                int cmp = b.remainder().compareTo(a.remainder());
                if (cmp != 0) return cmp;
                return Integer.compare(a.index(), b.index());
            });

            for (int k = 0; k < remainderToDistribute; k++) {
                int targetIndex = remainders.get(k).index();
                result[targetIndex] = Math.addExact(result[targetIndex], 1L);
            }
        }

        List<Long> allocated = new ArrayList<>(n);
        long verifiedSum = 0;
        for (long val : result) {
            allocated.add(val);
            verifiedSum = Math.addExact(verifiedSum, val);
        }

        if (verifiedSum != totalMinor) {
            throw new IllegalStateException("Allocation invariant failed: sum " + verifiedSum + " != total " + totalMinor);
        }

        return Collections.unmodifiableList(allocated);
    }

    /**
     * Allocates {@code totalMinor} units across the given {@code weights} array.
     */
    public static long[] allocate(long totalMinor, long[] weights) {
        Objects.requireNonNull(weights, "weights cannot be null");
        List<Long> list = new ArrayList<>(weights.length);
        for (long w : weights) list.add(w);
        List<Long> res = allocate(totalMinor, list);
        long[] out = new long[res.size()];
        for (int i = 0; i < out.length; i++) out[i] = res.get(i);
        return out;
    }
}
