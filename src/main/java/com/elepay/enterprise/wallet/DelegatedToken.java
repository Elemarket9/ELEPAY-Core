package com.elepay.enterprise.wallet;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;

/**
 * Cryptographically signed delegated authorization token for OAuth2/OIDC partner flows.
 */
public record DelegatedToken(
        String tokenId,
        String partnerId,
        String customerId,
        String externalCustomerId,
        long maxAuthorizedMinorUnits,
        String currency,
        Set<PartnerScope> scopes,
        Instant issuedAt,
        Instant expiresAt,
        String cryptographicSignature
) {
    public DelegatedToken {
        if (scopes == null) {
            scopes = Collections.emptySet();
        } else {
            scopes = Collections.unmodifiableSet(scopes);
        }
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean hasScope(PartnerScope scope) {
        return scopes.contains(scope);
    }
}
