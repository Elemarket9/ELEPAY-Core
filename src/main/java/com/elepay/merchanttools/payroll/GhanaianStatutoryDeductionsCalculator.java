package com.elepay.merchanttools.payroll;

import org.springframework.stereotype.Component;

/**
 * Domain 6: Ghanaian Statutory Deductions Calculator.
 * Implements:
 * 1. Social Security and National Insurance Trust (SSNIT) Act 766:
 *    - Tier 1 (Employer Mandatory): 13.5% of basic salary
 *    - Tier 2 (Employee Mandatory): 5.5% of basic salary
 *    - Tier 3 (Voluntary Provident Fund): Optional employee/employer contribution (tax-exempt up to 16.5%)
 * 2. Ghana Revenue Authority (GRA) PAYE (Pay-As-You-Earn) Graduated Monthly Tax Bands (Income Tax Act 2024/2025).
 */
@Component
public class GhanaianStatutoryDeductionsCalculator {

    public static final int SSNIT_TIER1_EMPLOYER_BPS = 1350; // 13.5%
    public static final int SSNIT_TIER2_EMPLOYEE_BPS = 550;  // 5.5%
    public static final int MAX_TIER3_TAX_EXEMPT_BPS = 1650; // 16.5%

    // GRA Monthly PAYE Tax Bands (in pesewas)
    public static final long BAND_1_LIMIT_PESEWAS = 49_000;    // First GHS 490 @ 0%
    public static final long BAND_2_CHUNK_PESEWAS = 11_000;    // Next GHS 110 @ 5%
    public static final long BAND_3_CHUNK_PESEWAS = 13_000;    // Next GHS 130 @ 10%
    public static final long BAND_4_CHUNK_PESEWAS = 316_667;   // Next GHS 3,166.67 @ 17.5%
    public static final long BAND_5_CHUNK_PESEWAS = 1_600_000; // Next GHS 16,000 @ 25%
    public static final long BAND_6_CHUNK_PESEWAS = 3_052_000; // Next GHS 30,520 @ 30%
    // Remaining balance @ 35%

    public record StatutoryDeductionsResult(
            long baseSalaryPesewas,
            long allowancesPesewas,
            long grossSalaryPesewas,
            long employeeSsnitTier2Pesewas,
            long employerSsnitTier1Pesewas,
            long employeeTier3VoluntaryPesewas,
            long employerTier3VoluntaryPesewas,
            long reliefsPesewas,
            long taxableIncomePesewas,
            long graPayeTaxPesewas,
            long totalEmployeeDeductionsPesewas,
            long netPayPesewas,
            long totalCostToEmployerPesewas
    ) {}

    /**
     * Calculates all Ghanaian statutory deductions and net salary.
     * All monetary inputs and outputs are in minor units (pesewas).
     */
    public StatutoryDeductionsResult calculate(long baseSalaryPesewas,
                                              long allowancesPesewas,
                                              int employeeTier3Bps,
                                              int employerTier3Bps,
                                              long reliefsPesewas,
                                              long otherDeductionsPesewas) {
        if (baseSalaryPesewas < 0) {
            throw new IllegalArgumentException("Base salary cannot be negative");
        }

        long grossSalary = baseSalaryPesewas + Math.max(0, allowancesPesewas);

        // SSNIT Tier 1 & Tier 2 calculated strictly on base salary
        long ssnitTier2Employee = (baseSalaryPesewas * SSNIT_TIER2_EMPLOYEE_BPS) / 10000;
        long ssnitTier1Employer = (baseSalaryPesewas * SSNIT_TIER1_EMPLOYER_BPS) / 10000;

        // Tier 3 voluntary
        long tier3Employee = (baseSalaryPesewas * employeeTier3Bps) / 10000;
        long tier3Employer = (baseSalaryPesewas * employerTier3Bps) / 10000;

        // Tax exempt portion of Tier 3 (capped at 16.5% of basic salary)
        long tier3TaxExemptMax = (baseSalaryPesewas * MAX_TIER3_TAX_EXEMPT_BPS) / 10000;
        long taxDeductibleTier3 = Math.min(tier3Employee, tier3TaxExemptMax);

        // Taxable income = Gross Salary - Employee SSNIT Tier 2 - Tax Deductible Tier 3 - Reliefs
        long taxableIncome = Math.max(0, grossSalary - ssnitTier2Employee - taxDeductibleTier3 - reliefsPesewas);

        // GRA PAYE graduated tax
        long payeTax = calculateGraPayeTax(taxableIncome);

        long totalDeductions = ssnitTier2Employee + tier3Employee + payeTax + otherDeductionsPesewas;
        long netPay = Math.max(0, grossSalary - totalDeductions);

        long totalEmployerCost = grossSalary + ssnitTier1Employer + tier3Employer;

        return new StatutoryDeductionsResult(
                baseSalaryPesewas,
                allowancesPesewas,
                grossSalary,
                ssnitTier2Employee,
                ssnitTier1Employer,
                tier3Employee,
                tier3Employer,
                reliefsPesewas,
                taxableIncome,
                payeTax,
                totalDeductions,
                netPay,
                totalEmployerCost
        );
    }

    /**
     * Computes GRA PAYE graduated monthly income tax.
     */
    public long calculateGraPayeTax(long taxableIncomePesewas) {
        if (taxableIncomePesewas <= BAND_1_LIMIT_PESEWAS) {
            return 0; // First GHS 490 is tax-free (0%)
        }

        long remaining = taxableIncomePesewas - BAND_1_LIMIT_PESEWAS;
        long tax = 0;

        // Band 2: Next GHS 110 (11,000 pesewas) @ 5%
        long inBand2 = Math.min(remaining, BAND_2_CHUNK_PESEWAS);
        tax += (inBand2 * 500) / 10000; // 5%
        remaining -= inBand2;
        if (remaining <= 0) return tax;

        // Band 3: Next GHS 130 (13,000 pesewas) @ 10%
        long inBand3 = Math.min(remaining, BAND_3_CHUNK_PESEWAS);
        tax += (inBand3 * 1000) / 10000; // 10%
        remaining -= inBand3;
        if (remaining <= 0) return tax;

        // Band 4: Next GHS 3,166.67 (316,667 pesewas) @ 17.5%
        long inBand4 = Math.min(remaining, BAND_4_CHUNK_PESEWAS);
        tax += (inBand4 * 1750) / 10000; // 17.5%
        remaining -= inBand4;
        if (remaining <= 0) return tax;

        // Band 5: Next GHS 16,000 (1,600,000 pesewas) @ 25%
        long inBand5 = Math.min(remaining, BAND_5_CHUNK_PESEWAS);
        tax += (inBand5 * 2500) / 10000; // 25%
        remaining -= inBand5;
        if (remaining <= 0) return tax;

        // Band 6: Next GHS 30,520 (3,052,000 pesewas) @ 30%
        long inBand6 = Math.min(remaining, BAND_6_CHUNK_PESEWAS);
        tax += (inBand6 * 3000) / 10000; // 30%
        remaining -= inBand6;
        if (remaining <= 0) return tax;

        // Band 7: Exceeding amount @ 35%
        tax += (remaining * 3500) / 10000;

        return tax;
    }
}
