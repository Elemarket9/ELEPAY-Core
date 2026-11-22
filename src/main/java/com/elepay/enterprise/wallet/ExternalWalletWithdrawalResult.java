package com.elepay.enterprise.wallet;

import java.time.Instant;

/**
 * Result of wallet withdrawal to domestic payout rails.
 */
public record ExternalWalletWithdrawalResult(
        String withdrawalId,
        String transactionReference,
        String state,
        long amountMinorUnits,
        String currency,
        String destinationRail,
        String destinationAccount,
        Instant initiatedAt,
        boolean success,
        String errorMessage
) {
    public static ExternalWalletWithdrawalResult success(
            String withdrawalId,
            String transactionReference,
            long amountMinorUnits,
            String currency,
            String destinationRail,
            String destinationAccount
    ) {
        return new ExternalWalletWithdrawalResult(
                withdrawalId,
                transactionReference,
                "SUBMITTED",
                amountMinorUnits,
                currency,
                destinationRail,
                destinationAccount,
                Instant.now(),
                true,
                null
        );
    }

    public static ExternalWalletWithdrawalResult failure(
            String transactionReference,
            String errorMessage
    ) {
        return new ExternalWalletWithdrawalResult(
                null,
                transactionReference,
                "FAILED",
                0L,
                null,
                null,
                null,
                Instant.now(),
                false,
                errorMessage
        );
    }
}
