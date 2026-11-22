package com.elepay.enterprise.wallet;

import java.util.Set;

/**
 * Partner authentication request using OAuth2 client credentials.
 */
public record PartnerAuthRequest(
        String clientId,
        String clientSecret,
        Set<PartnerScope> requestedScopes
) {}
