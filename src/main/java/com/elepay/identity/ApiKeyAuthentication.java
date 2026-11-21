package com.elepay.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Authentication for server-to-server developer API calls. */
public final class ApiKeyAuthentication extends AbstractAuthenticationToken {
    private final UUID credentialId;
    private final UUID merchantId;

    ApiKeyAuthentication(UUID credentialId, UUID merchantId) {
        super(List.of(new SimpleGrantedAuthority("ROLE_merchant_api")));
        this.credentialId = credentialId;
        this.merchantId = merchantId;
        setAuthenticated(true);
    }

    public UUID credentialId() { return credentialId; }
    public UUID merchantId() { return merchantId; }

    @Override public Object getCredentials() { return ""; }
    @Override public Object getPrincipal() { return credentialId; }
}
