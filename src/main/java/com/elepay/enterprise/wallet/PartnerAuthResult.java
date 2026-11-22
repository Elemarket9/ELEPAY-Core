package com.elepay.enterprise.wallet;

import java.util.Set;

/**
 * Result of partner authentication, containing issued access token and granted scopes.
 */
public record PartnerAuthResult(
        boolean authenticated,
        String partnerId,
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        Set<PartnerScope> grantedScopes,
        String errorMessage
) {
    public static PartnerAuthResult success(String partnerId, String accessToken, long expiresInSeconds, Set<PartnerScope> scopes) {
        return new PartnerAuthResult(true, partnerId, accessToken, "Bearer", expiresInSeconds, scopes, null);
    }

    public static PartnerAuthResult failure(String errorMessage) {
        return new PartnerAuthResult(false, null, null, null, 0L, Set.of(), errorMessage);
    }
}
