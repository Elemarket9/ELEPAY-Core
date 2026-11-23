package com.elepay.enterprise.payout;

/**
 * Result of KYC Tier limits evaluation and sanctions screening.
 */
public record ComplianceCheckResult(
        boolean passed,
        boolean sanctionsHit,
        boolean dailyLimitExceeded,
        long currentDailyTotalPesewas,
        long dailyLimitPesewas,
        String failureReason
) {
    public static ComplianceCheckResult passed(long dailyTotal, long dailyLimit) {
        return new ComplianceCheckResult(true, false, false, dailyTotal, dailyLimit, null);
    }

    public static ComplianceCheckResult sanctionsViolation(String reason) {
        return new ComplianceCheckResult(false, true, false, 0L, 0L, reason);
    }

    public static ComplianceCheckResult dailyLimitExceeded(long dailyTotal, long dailyLimit, String reason) {
        return new ComplianceCheckResult(false, false, true, dailyTotal, dailyLimit, reason);
    }
}
