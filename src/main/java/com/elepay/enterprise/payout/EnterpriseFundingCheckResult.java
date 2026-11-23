package com.elepay.enterprise.payout;

/**
 * Result of checking available enterprise prefunding or settlement funding.
 */
public record EnterpriseFundingCheckResult(
        boolean sufficient,
        long availableBalancePesewas,
        long requestedAmountPesewas,
        String message
) {
    public static EnterpriseFundingCheckResult ok(long available, long requested) {
        return new EnterpriseFundingCheckResult(true, available, requested, "Funding check passed");
    }

    public static EnterpriseFundingCheckResult insufficient(long available, long requested) {
        return new EnterpriseFundingCheckResult(
                false,
                available,
                requested,
                "Insufficient enterprise settlement funding: available=" + available + ", required=" + requested
        );
    }
}
