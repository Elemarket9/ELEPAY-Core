package com.elepay.credit;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 066: Earned-wage access (EWA) eligibility.
 * Validates accrued salary, existing obligations, and employer advance limits.
 */
@Service
public class EwaEligibilityService {

    private final JdbcClient db;

    public EwaEligibilityService(JdbcClient db) {
        this.db = db;
    }

    public record EwaAssessment(
            UUID id,
            UUID employeeId,
            UUID employerId,
            long monthlySalaryPesewas,
            int daysWorked,
            long accruedSalaryPesewas,
            long maxAdvanceLimitPesewas,
            long activeAdvancesPesewas,
            long eligibleAmountPesewas,
            String status
    ) {}

    @Transactional
    public EwaAssessment calculateEligibility(UUID employeeId, UUID employerId, long monthlySalaryPesewas,
                                              int daysWorked, int totalDaysInCycle, long activeAdvancesPesewas,
                                              double maxAdvanceRatio) {
        if (daysWorked < 5) {
            return saveAndReturn(employeeId, employerId, monthlySalaryPesewas, daysWorked, 0L, 0L, activeAdvancesPesewas, 0L, "INELIGIBLE_MINIMUM_DAYS_NOT_MET");
        }

        if (monthlySalaryPesewas <= 0 || totalDaysInCycle <= 0) {
            return saveAndReturn(employeeId, employerId, monthlySalaryPesewas, daysWorked, 0L, 0L, activeAdvancesPesewas, 0L, "INELIGIBLE_INVALID_SALARY");
        }

        // Accrued earnings = floor(monthlySalary * (daysWorked / totalCycleDays))
        double dailyRate = (double) monthlySalaryPesewas / totalDaysInCycle;
        long accruedSalary = (long) Math.floor(dailyRate * daysWorked);

        // Max advance limit per employer policy (default e.g. 50% of accrued earnings)
        double ratio = (maxAdvanceRatio > 0 && maxAdvanceRatio <= 0.8) ? maxAdvanceRatio : 0.50;
        long maxAdvanceLimit = (long) Math.floor(accruedSalary * ratio);

        // Net eligible advance
        long eligibleAmount = Math.max(0L, maxAdvanceLimit - activeAdvancesPesewas);

        String status = eligibleAmount >= 5000L ? "ELIGIBLE" : "INELIGIBLE_BELOW_MINIMUM_ADVANCE"; // Minimum 50 GHS (5000 pesewas)

        return saveAndReturn(employeeId, employerId, monthlySalaryPesewas, daysWorked, accruedSalary, maxAdvanceLimit, activeAdvancesPesewas, eligibleAmount, status);
    }

    private EwaAssessment saveAndReturn(UUID employeeId, UUID employerId, long salary, int daysWorked,
                                        long accruedSalary, long maxLimit, long activeAdvances, long eligible, String status) {
        UUID id = UUID.randomUUID();
        db.sql("""
            INSERT INTO ewa_eligibility
            (id, employee_id, employer_id, monthly_salary_pesewas, days_worked, accrued_salary_pesewas,
             max_advance_limit_pesewas, active_advances_pesewas, eligible_amount_pesewas, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).params(id, employeeId, employerId, salary, daysWorked, accruedSalary, maxLimit, activeAdvances, eligible, status)
        .update();

        return new EwaAssessment(id, employeeId, employerId, salary, daysWorked, accruedSalary, maxLimit, activeAdvances, eligible, status);
    }
}
