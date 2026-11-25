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
public record Actor(String subject, Set<String> roles, UUID apiKeyMerchant, Set<String> scopes,
                    String keyType, java.util.List<String> allowedIps) {

    public Actor(String subject, Set<String> roles, UUID apiKeyMerchant, Set<String> scopes) {
        this(subject, roles, apiKeyMerchant, scopes, apiKeyMerchant != null ? "legacy" : null, java.util.List.of());
    }

    public static final String OPS = "ops";
    public static final String OPS_ADMIN = "ops_admin";
    public static final String SUPPORT_AGENT = "support_agent";
    public static final String ONBOARDING_OFFICER = "onboarding_officer";
    public static final String FINANCE_OPS = "finance_ops";
    public static final String RISK_COMPLIANCE = "risk_compliance";

    public boolean isOps() {
        return roles.contains(OPS)
                || roles.contains(OPS_ADMIN)
                || roles.contains(SUPPORT_AGENT)
                || roles.contains(ONBOARDING_OFFICER)
                || roles.contains(FINANCE_OPS)
                || roles.contains(RISK_COMPLIANCE);
    }
    public boolean isOpsAdmin() { return roles.contains(OPS_ADMIN); }
    public boolean canManageSupport() { return roles.contains(SUPPORT_AGENT) || roles.contains(RISK_COMPLIANCE) || roles.contains(OPS) || isOpsAdmin(); }
    public boolean canOnboardMerchants() { return roles.contains(ONBOARDING_OFFICER) || roles.contains(RISK_COMPLIANCE) || roles.contains(OPS) || isOpsAdmin(); }
    public boolean canManageFinance() { return roles.contains(FINANCE_OPS) || roles.contains(OPS) || isOpsAdmin(); }
    public boolean canFreezeRisk() { return roles.contains(RISK_COMPLIANCE) || roles.contains(OPS) || isOpsAdmin(); }
    public String primaryRole() {
        if (isOpsAdmin()) return OPS_ADMIN;
        if (roles.contains(RISK_COMPLIANCE)) return RISK_COMPLIANCE;
        if (roles.contains(FINANCE_OPS)) return FINANCE_OPS;
        if (roles.contains(ONBOARDING_OFFICER)) return ONBOARDING_OFFICER;
        if (roles.contains(SUPPORT_AGENT)) return SUPPORT_AGENT;
        if (roles.contains(OPS)) return OPS;
        return "customer";
    }
    public boolean isApiKey() { return apiKeyMerchant != null; }
    public boolean isCustomer() { return !isOps() && !isApiKey(); }
    public boolean isPublishableKey() { return "publishable".equalsIgnoreCase(keyType); }
    public boolean isSecretKey() { return "secret".equalsIgnoreCase(keyType); }
    public boolean isPayoutKey() { return "payout".equalsIgnoreCase(keyType); }
    public boolean isPosTerminalKey() { return "pos_terminal".equalsIgnoreCase(keyType); }

    public UUID apiKeyCredentialId() {
        if (!isApiKey() || subject == null || !subject.startsWith("apikey:")) return null;
        try {
            return UUID.fromString(subject.substring(7));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Actor current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ApiKeyAuthentication key) {
            return new Actor("apikey:" + key.credentialId(), Set.of(), key.merchantId(), key.scopes(),
                    key.keyType(), key.allowedIps());
        }
        if (auth instanceof JwtAuthenticationToken jwt) {
            Set<String> roles = new java.util.HashSet<>();
            for (GrantedAuthority authority : jwt.getAuthorities()) {
                if (authority.getAuthority().startsWith("ROLE_")) roles.add(authority.getAuthority().substring(5));
            }
            return new Actor(jwt.getToken().getSubject(), Set.copyOf(roles), null, Set.of(), null, java.util.List.of());
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

    public void requireOpsAdmin() {
        if (!isOpsAdmin()) throw ApiException.forbidden("Operations administrator role required");
    }

    public void requireFreezeRisk() {
        if (!canFreezeRisk()) throw ApiException.forbidden("Risk and compliance role required");
    }

    public void requireFinance() {
        if (!canManageFinance()) throw ApiException.forbidden("Finance operations role required");
    }

    public void requireOnboarding() {
        if (!canOnboardMerchants()) throw ApiException.forbidden("Merchant onboarding role required");
    }

    public void requireSupport() {
        if (!canManageSupport()) throw ApiException.forbidden("Support role required");
    }

    public void requireUser() {
        if (isApiKey()) throw ApiException.forbidden("This operation requires a signed-in user");
    }

    public void requireCustomer() {
        if (!isCustomer()) throw ApiException.forbidden("Customer role required");
    }
}
