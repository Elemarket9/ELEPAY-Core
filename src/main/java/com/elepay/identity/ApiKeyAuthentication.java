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
    private final String keyType;
    private final List<String> allowedIps;

    public ApiKeyAuthentication(UUID credentialId, UUID merchantId, java.util.Set<String> scopes) {
        this(credentialId, merchantId, scopes, null, "secret", List.of());
    }

    public ApiKeyAuthentication(UUID credentialId, UUID merchantId, java.util.Set<String> scopes, String signingSecret) {
        this(credentialId, merchantId, scopes, signingSecret, "secret", List.of());
    }

    public ApiKeyAuthentication(UUID credentialId, UUID merchantId, java.util.Set<String> scopes, String signingSecret,
                                String keyType, List<String> allowedIps) {
        super(List.of(new SimpleGrantedAuthority("ROLE_merchant_api")));
        this.credentialId = credentialId;
        this.merchantId = merchantId;
        this.scopes = java.util.Set.copyOf(scopes);
        this.signingSecret = signingSecret;
        this.keyType = keyType != null ? keyType : "secret";
        this.allowedIps = allowedIps != null ? List.copyOf(allowedIps) : List.of();
        setAuthenticated(true);
    }

    public UUID credentialId() { return credentialId; }
    public UUID merchantId() { return merchantId; }
    public java.util.Set<String> scopes() { return scopes; }
    public String signingSecret() { return signingSecret; }
    public String keyType() { return keyType; }
    public List<String> allowedIps() { return allowedIps; }

    @Override public Object getCredentials() { return ""; }
    @Override public Object getPrincipal() { return credentialId; }
}
