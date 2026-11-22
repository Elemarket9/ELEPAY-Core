package com.elepay.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ProportionalAllocationTest {

    @Test
    void exactDivisibleSplits() {
        List<Long> result = ProportionalAllocation.allocate(1000, List.of(50L, 50L));
        assertEquals(List.of(500L, 500L), result);
        assertEquals(1000L, result.stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void threeWayOddSplitWithLargestRemainder() {
        // 100 / 3 = 33 r 1 -> index 0 gets remainder 1
        List<Long> result = ProportionalAllocation.allocate(100, List.of(1L, 1L, 1L));
        assertEquals(List.of(34L, 33L, 33L), result);
        assertEquals(100L, result.stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void deterministicTieBreaking() {
        // 2 units across 4 equal weights: indices 0 and 1 should each receive 1, indices 2 and 3 get 0
        List<Long> result = ProportionalAllocation.allocate(2, List.of(1L, 1L, 1L, 1L));
        assertEquals(List.of(1L, 1L, 0L, 0L), result);
        assertEquals(2L, result.stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void singlePesewaSplit() {
        List<Long> result = ProportionalAllocation.allocate(1, List.of(10L, 20L, 30L));
        // Weights: 10, 20, 30 => totals 60. Remainder for index 2 is 1*30 = 30 / 60 = 0.5 (largest)
        assertEquals(List.of(0L, 0L, 1L), result);
        assertEquals(1L, result.stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void zeroWeightShareReceivesZero() {
        List<Long> result = ProportionalAllocation.allocate(100, List.of(0L, 50L, 50L));
        assertEquals(List.of(0L, 50L, 50L), result);
        assertEquals(100L, result.stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void zeroTotalReturnsAllZeros() {
        List<Long> result = ProportionalAllocation.allocate(0, List.of(10L, 20L, 30L));
        assertEquals(List.of(0L, 0L, 0L), result);
        assertEquals(0L, result.stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void arrayOverloadMatchesList() {
        long[] arrayResult = ProportionalAllocation.allocate(100, new long[]{70, 20, 10});
        assertArrayEquals(new long[]{70, 20, 10}, arrayResult);
    }

    @Test
    void invalidInputsThrowIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> ProportionalAllocation.allocate(-1, List.of(1L, 2L)));
        assertThrows(IllegalArgumentException.class, () -> ProportionalAllocation.allocate(100, List.of(1L, -2L)));
        assertThrows(IllegalArgumentException.class, () -> ProportionalAllocation.allocate(100, List.of(0L, 0L)));
        assertThrows(IllegalArgumentException.class, () -> ProportionalAllocation.allocate(100, List.of()));
        assertThrows(NullPointerException.class, () -> ProportionalAllocation.allocate(100, (List<Long>) null));
    }

    @Test
    void randomizedExtensivePropertyTest() {
        Random rng = new Random(42);
        for (int iter = 0; iter < 1000; iter++) {
            long total = rng.nextInt(1_000_000) + 1; // 1 to 1,000,000 minor units
            int numShares = rng.nextInt(20) + 1;     // 1 to 20 shares
            long[] weights = new long[numShares];
            boolean hasPositive = false;
            for (int i = 0; i < numShares; i++) {
                weights[i] = rng.nextInt(10_000);
                if (weights[i] > 0) hasPositive = true;
            }
            if (!hasPositive) {
                weights[0] = 1;
            }

            long[] allocated = ProportionalAllocation.allocate(total, weights);
            assertEquals(numShares, allocated.length);

            long sum = 0;
            for (int i = 0; i < numShares; i++) {
                assertTrue(allocated[i] >= 0, "Allocated share must be non-negative");
                sum += allocated[i];
            }
            assertEquals(total, sum, "Sum of allocated units must strictly match total on iteration " + iter);
        }
    }
}
