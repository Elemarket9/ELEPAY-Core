package com.elepay.enterprise.wallet;

import java.time.Instant;

/**
 * Balance query request for an external or white-label customer wallet.
 */
public record ExternalBalanceQueryRequest(
        String partnerId,
        String externalCustomerId,
        String currency
) {
    public ExternalBalanceQueryRequest {
        if (partnerId == null || partnerId.isBlank()) {
            throw new IllegalArgumentException("Partner ID is required");
        }
        if (externalCustomerId == null || externalCustomerId.isBlank()) {
            throw new IllegalArgumentException("External customer ID is required");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency is required");
        }
    }
}
