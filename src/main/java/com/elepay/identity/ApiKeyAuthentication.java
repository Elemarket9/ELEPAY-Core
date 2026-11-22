package com.elepay.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Authentication for server-to-server developer API calls. */
public final class ApiKeyAuthentication extends AbstractAuthenticationToken {
    private final UUID credentialId;
    private final UUID merchantId;
    private final java.util.Set<String> scopes;
    private final String signingSecret;

    public ApiKeyAuthentication(UUID credentialId, UUID merchantId, java.util.Set<String> scopes) {
        this(credentialId, merchantId, scopes, null);
    }

    public ApiKeyAuthentication(UUID credentialId, UUID merchantId, java.util.Set<String> scopes, String signingSecret) {
        super(List.of(new SimpleGrantedAuthority("ROLE_merchant_api")));
        this.credentialId = credentialId;
        this.merchantId = merchantId;
        this.scopes = java.util.Set.copyOf(scopes);
        this.signingSecret = signingSecret;
        setAuthenticated(true);
    }

    public UUID credentialId() { return credentialId; }
    public UUID merchantId() { return merchantId; }
    public java.util.Set<String> scopes() { return scopes; }
    public String signingSecret() { return signingSecret; }

    @Override public Object getCredentials() { return ""; }
    @Override public Object getPrincipal() { return credentialId; }
}
