package com.elepay.enterprise.wallet;

import java.util.Set;

/**
 * Request to issue an OAuth2/OIDC delegated authorization token.
 */
public record DelegatedTokenRequest(
        String partnerId,
        String customerId,
        String externalCustomerId,
        long maxAuthorizedMinorUnits,
        String currency,
        Set<PartnerScope> scopes,
        long ttlSeconds
) {
    public DelegatedTokenRequest {
        if (ttlSeconds <= 0) {
            ttlSeconds = 3600L; // default 1 hour
        }
        if (maxAuthorizedMinorUnits < 0) {
            throw new IllegalArgumentException("Max authorized amount cannot be negative");
        }
    }
}
