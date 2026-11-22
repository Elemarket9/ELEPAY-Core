package com.elepay.enterprise.wallet;

import java.time.Instant;

/**
 * Result of querying an external or white-label customer wallet balance.
 */
public record ExternalBalanceQueryResult(
        String partnerId,
        String externalCustomerId,
        long availableBalanceMinorUnits,
        long reservedBalanceMinorUnits,
        String currency,
        Instant queriedAt,
        boolean success,
        String errorMessage
) {
    public static ExternalBalanceQueryResult success(
            String partnerId,
            String externalCustomerId,
            long availableBalanceMinorUnits,
            long reservedBalanceMinorUnits,
            String currency
    ) {
        return new ExternalBalanceQueryResult(
                partnerId,
                externalCustomerId,
                availableBalanceMinorUnits,
                reservedBalanceMinorUnits,
                currency,
                Instant.now(),
                true,
                null
        );
    }

    public static ExternalBalanceQueryResult failure(
            String partnerId,
            String externalCustomerId,
            String errorMessage
    ) {
        return new ExternalBalanceQueryResult(
                partnerId,
                externalCustomerId,
                0L,
                0L,
                null,
                Instant.now(),
                false,
                errorMessage
        );
    }
}
