package com.elepay.lending;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AmortizationScheduleEngineTest {

    private final AmortizationScheduleEngine engine = new AmortizationScheduleEngine();

    @Test
    @DisplayName("French Amortization: Exact money conservation and zero remaining balance")
    void testFrenchAmortizationMoneyConservation() {
        long principal = 100_000L; // 1,000.00 GHS
        int annualRateBps = 2400; // 24% p.a.
        int tenorMonths = 12;
        LocalDate start = LocalDate.of(2026, 1, 1);

        var schedule = engine.generateSchedule(
                principal,
                annualRateBps,
                tenorMonths,
                start,
                AmortizationScheduleEngine.AmortizationMethod.EQUAL_INSTALLMENT_FRENCH
        );

        assertThat(schedule).isNotNull();
        assertThat(schedule.installments()).hasSize(12);

        long sumPrincipal = schedule.installments().stream()
                .mapToLong(AmortizationScheduleEngine.InstallmentPlanItem::principalDuePesewas)
                .sum();
        long sumInterest = schedule.installments().stream()
                .mapToLong(AmortizationScheduleEngine.InstallmentPlanItem::interestDuePesewas)
                .sum();

        assertThat(sumPrincipal).isEqualTo(principal);
        assertThat(sumInterest).isEqualTo(schedule.totalInterestPesewas());
        assertThat(schedule.installments().get(11).remainingPrincipalPesewas()).isZero();
    }

    @Test
    @DisplayName("German Amortization: Fixed principal components and declining interest")
    void testGermanAmortization() {
        long principal = 120_000L; // 1,200.00 GHS
        int annualRateBps = 1800; // 18% p.a.
        int tenorMonths = 6;
        LocalDate start = LocalDate.of(2026, 1, 1);

        var schedule = engine.generateSchedule(
                principal,
                annualRateBps,
                tenorMonths,
                start,
                AmortizationScheduleEngine.AmortizationMethod.EQUAL_PRINCIPAL_GERMAN
        );

        assertThat(schedule).isNotNull();
        assertThat(schedule.installments()).hasSize(6);

        long sumPrincipal = schedule.installments().stream()
                .mapToLong(AmortizationScheduleEngine.InstallmentPlanItem::principalDuePesewas)
                .sum();
        assertThat(sumPrincipal).isEqualTo(principal);
        assertThat(schedule.installments().get(5).remainingPrincipalPesewas()).isZero();

        // Check that interest declines
        long interest1 = schedule.installments().get(0).interestDuePesewas();
        long interest6 = schedule.installments().get(5).interestDuePesewas();
        assertThat(interest1).isGreaterThan(interest6);
    }

    @Test
    @DisplayName("Bullet Repayment: Periodic interest only and 100% principal at maturity")
    void testBulletRepayment() {
        long principal = 500_000L; // 5,000.00 GHS
        int annualRateBps = 1500;
        int tenorMonths = 3;
        LocalDate start = LocalDate.of(2026, 1, 1);

        var schedule = engine.generateSchedule(
                principal,
                annualRateBps,
                tenorMonths,
                start,
                AmortizationScheduleEngine.AmortizationMethod.BULLET_BALLOON
        );

        assertThat(schedule.installments()).hasSize(3);
        assertThat(schedule.installments().get(0).principalDuePesewas()).isZero();
        assertThat(schedule.installments().get(1).principalDuePesewas()).isZero();
        assertThat(schedule.installments().get(2).principalDuePesewas()).isEqualTo(principal);
        assertThat(schedule.installments().get(2).remainingPrincipalPesewas()).isZero();
    }
}
