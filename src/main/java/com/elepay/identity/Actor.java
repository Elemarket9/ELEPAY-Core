package com.elepay.identity;

import com.elepay.common.ApiException;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The authenticated caller. Either an OIDC user (subject from the JWT) or a
 * merchant API credential scoped to exactly one merchant.
 */
public record Actor(String subject, Set<String> roles, UUID apiKeyMerchant, Set<String> scopes) {

    public static final String OPS = "ops";
    public static final String OPS_ADMIN = "ops_admin";

    public boolean isOps() { return roles.contains(OPS) || roles.contains(OPS_ADMIN); }
    public boolean isApiKey() { return apiKeyMerchant != null; }
    public boolean isCustomer() { return !isOps() && !isApiKey(); }

    public static Actor current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ApiKeyAuthentication key) {
            return new Actor("apikey:" + key.credentialId(), Set.of(), key.merchantId(), key.scopes());
        }
        if (auth instanceof JwtAuthenticationToken jwt) {
            Set<String> roles = new java.util.HashSet<>();
            for (GrantedAuthority authority : jwt.getAuthorities()) {
                if (authority.getAuthority().startsWith("ROLE_")) roles.add(authority.getAuthority().substring(5));
            }
            return new Actor(jwt.getToken().getSubject(), Set.copyOf(roles), null, Set.of());
        }
        throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required");
    }

    public static java.util.Optional<Actor> optional() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ApiKeyAuthentication || auth instanceof JwtAuthenticationToken) return java.util.Optional.of(current());
        return java.util.Optional.empty();
    }

    public void requireOps() {
        if (!isOps()) throw ApiException.forbidden("Operations role required");
    }

    public void requireUser() {
        if (isApiKey()) throw ApiException.forbidden("This operation requires a signed-in user");
    }

    public void requireCustomer() {
        if (!isCustomer()) throw ApiException.forbidden("Customer role required");
    }
}
