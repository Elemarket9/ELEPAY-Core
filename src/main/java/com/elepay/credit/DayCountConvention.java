package com.elepay.credit;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Supported financial day-count conventions for interest and yield calculation.
 */
public enum DayCountConvention {
    ACT_365 {
        @Override
        public double yearFraction(LocalDate startDate, LocalDate endDate) {
            long days = ChronoUnit.DAYS.between(startDate, endDate);
            return (double) days / 365.0;
        }
    },
    ACT_360 {
        @Override
        public double yearFraction(LocalDate startDate, LocalDate endDate) {
            long days = ChronoUnit.DAYS.between(startDate, endDate);
            return (double) days / 360.0;
        }
    },
    THIRTY_360 {
        @Override
        public double yearFraction(LocalDate startDate, LocalDate endDate) {
            int d1 = Math.min(startDate.getDayOfMonth(), 30);
            int d2 = Math.min(endDate.getDayOfMonth(), 30);
            int m1 = startDate.getMonthValue();
            int m2 = endDate.getMonthValue();
            int y1 = startDate.getYear();
            int y2 = endDate.getYear();

            long days = 360L * (y2 - y1) + 30L * (m2 - m1) + (d2 - d1);
            return (double) days / 360.0;
        }
    };

    public abstract double yearFraction(LocalDate startDate, LocalDate endDate);
}
