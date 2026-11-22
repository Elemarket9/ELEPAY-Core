package com.elepay.wallet;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Algorithm 008: Supported financial day-count conventions for interest and yield calculation.
 * Eliminates all binary floating-point (double/float) arithmetic for monetary interest.
 * Uses integer minor units / basis points with checked arithmetic (Math.multiplyExact),
 * ensuring zero floating-point math.
 */
public enum DayCountConvention {
    ACT_365 {
        @Override
        public long daysBetween(LocalDate startDate, LocalDate endDate) {
            if (startDate == null || endDate == null || !endDate.isAfter(startDate)) {
                return 0L;
            }
            return ChronoUnit.DAYS.between(startDate, endDate);
        }

        @Override
        public int daysInYear() {
            return 365;
        }
    },
    ACT_360 {
        @Override
        public long daysBetween(LocalDate startDate, LocalDate endDate) {
            if (startDate == null || endDate == null || !endDate.isAfter(startDate)) {
                return 0L;
            }
            return ChronoUnit.DAYS.between(startDate, endDate);
        }

        @Override
        public int daysInYear() {
            return 360;
        }
    },
    THIRTY_360 {
        @Override
        public long daysBetween(LocalDate startDate, LocalDate endDate) {
            if (startDate == null || endDate == null || !endDate.isAfter(startDate)) {
                return 0L;
            }
            int d1 = Math.min(startDate.getDayOfMonth(), 30);
            int d2 = Math.min(endDate.getDayOfMonth(), 30);
            int m1 = startDate.getMonthValue();
            int m2 = endDate.getMonthValue();
            int y1 = startDate.getYear();
            int y2 = endDate.getYear();

            long days = 360L * (y2 - y1) + 30L * (m2 - m1) + (d2 - d1);
            return Math.max(0L, days);
        }

        @Override
        public int daysInYear() {
            return 360;
        }
    };

    public abstract long daysBetween(LocalDate startDate, LocalDate endDate);
    public abstract int daysInYear();

    /**
     * Calculates the year fraction in basis points (10000 bps = 1.0 year), without floating-point math.
     */
    public long yearFractionBps(LocalDate startDate, LocalDate endDate) {
        long days = daysBetween(startDate, endDate);
        if (days <= 0) {
            return 0L;
        }
        return Math.multiplyExact(days, 10_000L) / daysInYear();
    }

    /**
     * Calculates deterministic accrued interest in integer minor units (e.g. pesewas)
     * using basis points (10000 bps = 100%) and checked integer arithmetic.
     * Accrual = (principal * rateBps * days) / (10000 * daysInYear)
     * Rounds half-up using pure integer arithmetic.
     *
     * @param principalPesewas the principal amount in integer minor units
     * @param rateBps          annual interest rate in basis points (e.g. 1200 for 12.00%)
     * @param startDate        accrual period start date
     * @param endDate          accrual period end date
     * @return accrued interest in minor units (non-negative)
     */
    public long calculateInterest(long principalPesewas, int rateBps, LocalDate startDate, LocalDate endDate) {
        if (principalPesewas <= 0 || rateBps <= 0 || startDate == null || endDate == null || !endDate.isAfter(startDate)) {
            return 0L;
        }
        long days = daysBetween(startDate, endDate);
        if (days <= 0) {
            return 0L;
        }

        // Checked multiplication to prevent silent overflow:
        // numerator = principal * rateBps * days
        long principalByRate = Math.multiplyExact(principalPesewas, (long) rateBps);
        long numerator = Math.multiplyExact(principalByRate, days);
        long divisor = Math.multiplyExact(10_000L, (long) daysInYear());

        // Integer half-up rounding: (numerator + divisor / 2) / divisor
        return (numerator + (divisor / 2L)) / divisor;
    }

    /**
     * Alias for calculateInterest matching accrual service nomenclature.
     */
    public long calculateAccrual(long principalPesewas, int rateBps, LocalDate startDate, LocalDate endDate) {
        return calculateInterest(principalPesewas, rateBps, startDate, endDate);
    }
}
