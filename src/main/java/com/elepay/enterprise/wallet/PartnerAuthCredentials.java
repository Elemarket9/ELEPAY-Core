package com.elepay.enterprise.wallet;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;

/**
 * Credentials and cryptographic keys configuration for an enterprise partner.
 */
public record PartnerAuthCredentials(
        String partnerId,
        String enterpriseName,
        String clientId,
        String clientSecret,
        String activeSigningKey,
        String previousSigningKey,
        int activeKeyVersion,
        Set<PartnerScope> allowedScopes,
        boolean active,
        Instant createdAt,
        Instant rotatedAt
) {
    public PartnerAuthCredentials {
        if (allowedScopes == null) {
            allowedScopes = Collections.emptySet();
        } else {
            allowedScopes = Collections.unmodifiableSet(allowedScopes);
        }
    }

    public static PartnerAuthCredentials create(
            String partnerId,
            String enterpriseName,
            String clientId,
            String clientSecret,
            String signingKey,
            Set<PartnerScope> scopes
    ) {
        return new PartnerAuthCredentials(
                partnerId,
                enterpriseName,
                clientId,
                clientSecret,
                signingKey,
                null,
                1,
                scopes,
                true,
                Instant.now(),
                null
        );
    }

    public PartnerAuthCredentials withRotatedKey(String newSigningKey) {
        return new PartnerAuthCredentials(
                this.partnerId,
                this.enterpriseName,
                this.clientId,
                this.clientSecret,
                newSigningKey,
                this.activeSigningKey,
                this.activeKeyVersion + 1,
                this.allowedScopes,
                this.active,
                this.createdAt,
                Instant.now()
        );
    }

    public boolean hasScope(PartnerScope scope) {
        return allowedScopes.contains(scope);
    }
}
