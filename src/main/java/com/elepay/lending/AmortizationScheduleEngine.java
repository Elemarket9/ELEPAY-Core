package com.elepay.lending;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * High-precision integer minor-unit loan amortization schedule calculator.
 * Supports French (Equal Total Installment), German (Equal Principal), and Bullet repayment structures.
 * Guarantees zero-leakage money conservation by absorbing integer rounding remainders into the final installment.
 */
@Service
public class AmortizationScheduleEngine {

    public enum AmortizationMethod {
        EQUAL_INSTALLMENT_FRENCH,
        EQUAL_PRINCIPAL_GERMAN,
        BULLET_BALLOON
    }

    public record InstallmentPlanItem(
            int installmentNumber,
            LocalDate dueDate,
            long principalDuePesewas,
            long interestDuePesewas,
            long totalDuePesewas,
            long remainingPrincipalPesewas
    ) {}

    public record AmortizationSchedule(
            AmortizationMethod method,
            long principalPesewas,
            int annualInterestRateBps,
            int tenorMonths,
            LocalDate startDate,
            long totalInterestPesewas,
            long totalRepaymentPesewas,
            List<InstallmentPlanItem> installments
    ) {}

    public AmortizationSchedule generateSchedule(
            long principalPesewas,
            int annualInterestRateBps,
            int tenorMonths,
            LocalDate startDate,
            AmortizationMethod method
    ) {
        if (principalPesewas <= 0) {
            throw new IllegalArgumentException("Principal must be positive integer pesewas");
        }
        if (tenorMonths <= 0) {
            throw new IllegalArgumentException("Tenor must be at least 1 month");
        }
        if (startDate == null) {
            startDate = LocalDate.now();
        }
        if (method == null) {
            method = AmortizationMethod.EQUAL_INSTALLMENT_FRENCH;
        }

        // Monthly interest rate = (bps / 10,000) / 12
        BigDecimal monthlyRate = BigDecimal.valueOf(annualInterestRateBps)
                .divide(BigDecimal.valueOf(120_000), 10, RoundingMode.HALF_UP);

        return switch (method) {
            case EQUAL_PRINCIPAL_GERMAN -> calculateGerman(principalPesewas, monthlyRate, annualInterestRateBps, tenorMonths, startDate);
            case BULLET_BALLOON -> calculateBullet(principalPesewas, monthlyRate, annualInterestRateBps, tenorMonths, startDate);
            case EQUAL_INSTALLMENT_FRENCH -> calculateFrench(principalPesewas, monthlyRate, annualInterestRateBps, tenorMonths, startDate);
        };
    }

    private AmortizationSchedule calculateFrench(
            long principalPesewas,
            BigDecimal r,
            int annualBps,
            int n,
            LocalDate startDate
    ) {
        // EMI formula: P * [r(1+r)^n] / [(1+r)^n - 1]
        long emiPesewas;
        if (r.compareTo(BigDecimal.ZERO) == 0) {
            emiPesewas = principalPesewas / n;
        } else {
            BigDecimal onePlusR = BigDecimal.ONE.add(r);
            BigDecimal onePlusRPowN = onePlusR.pow(n);
            BigDecimal numerator = BigDecimal.valueOf(principalPesewas).multiply(r).multiply(onePlusRPowN);
            BigDecimal denominator = onePlusRPowN.subtract(BigDecimal.ONE);
            emiPesewas = numerator.divide(denominator, 0, RoundingMode.HALF_UP).longValueExact();
        }

        List<InstallmentPlanItem> items = new ArrayList<>();
        long remaining = principalPesewas;
        long cumulativePrincipal = 0L;
        long cumulativeInterest = 0L;

        for (int i = 1; i <= n; i++) {
            LocalDate dueDate = startDate.plusMonths(i);
            long interestPesewas = BigDecimal.valueOf(remaining).multiply(r).setScale(0, RoundingMode.HALF_UP).longValueExact();
            long principalPesewasForInstallment;

            if (i == n) {
                // Final installment absorbs all remaining principal
                principalPesewasForInstallment = remaining;
            } else {
                principalPesewasForInstallment = Math.min(remaining, emiPesewas - interestPesewas);
            }

            remaining -= principalPesewasForInstallment;
            long totalDue = principalPesewasForInstallment + interestPesewas;

            cumulativePrincipal += principalPesewasForInstallment;
            cumulativeInterest += interestPesewas;

            items.add(new InstallmentPlanItem(i, dueDate, principalPesewasForInstallment, interestPesewas, totalDue, remaining));
        }

        return new AmortizationSchedule(
                AmortizationMethod.EQUAL_INSTALLMENT_FRENCH,
                principalPesewas,
                annualBps,
                n,
                startDate,
                cumulativeInterest,
                cumulativePrincipal + cumulativeInterest,
                items
        );
    }

    private AmortizationSchedule calculateGerman(
            long principalPesewas,
            BigDecimal r,
            int annualBps,
            int n,
            LocalDate startDate
    ) {
        long fixedPrincipal = principalPesewas / n;
        List<InstallmentPlanItem> items = new ArrayList<>();
        long remaining = principalPesewas;
        long cumulativePrincipal = 0L;
        long cumulativeInterest = 0L;

        for (int i = 1; i <= n; i++) {
            LocalDate dueDate = startDate.plusMonths(i);
            long interestPesewas = BigDecimal.valueOf(remaining).multiply(r).setScale(0, RoundingMode.HALF_UP).longValueExact();
            long principalForInstallment = (i == n) ? remaining : fixedPrincipal;

            remaining -= principalForInstallment;
            long totalDue = principalForInstallment + interestPesewas;

            cumulativePrincipal += principalForInstallment;
            cumulativeInterest += interestPesewas;

            items.add(new InstallmentPlanItem(i, dueDate, principalForInstallment, interestPesewas, totalDue, remaining));
        }

        return new AmortizationSchedule(
                AmortizationMethod.EQUAL_PRINCIPAL_GERMAN,
                principalPesewas,
                annualBps,
                n,
                startDate,
                cumulativeInterest,
                cumulativePrincipal + cumulativeInterest,
                items
        );
    }

    private AmortizationSchedule calculateBullet(
            long principalPesewas,
            BigDecimal r,
            int annualBps,
            int n,
            LocalDate startDate
    ) {
        List<InstallmentPlanItem> items = new ArrayList<>();
        long cumulativeInterest = 0L;

        for (int i = 1; i <= n; i++) {
            LocalDate dueDate = startDate.plusMonths(i);
            long interestPesewas = BigDecimal.valueOf(principalPesewas).multiply(r).setScale(0, RoundingMode.HALF_UP).longValueExact();
            long principalForInstallment = (i == n) ? principalPesewas : 0L;
            long remaining = (i == n) ? 0L : principalPesewas;
            long totalDue = principalForInstallment + interestPesewas;

            cumulativeInterest += interestPesewas;

            items.add(new InstallmentPlanItem(i, dueDate, principalForInstallment, interestPesewas, totalDue, remaining));
        }

        return new AmortizationSchedule(
                AmortizationMethod.BULLET_BALLOON,
                principalPesewas,
                annualBps,
                n,
                startDate,
                cumulativeInterest,
                principalPesewas + cumulativeInterest,
                items
        );
    }
}
