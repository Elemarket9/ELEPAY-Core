package com.elepay.security;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the {@code prod} profile with settings that would put money or card data at risk:
 * simulated rails, development tokens, missing or publicly known secrets, an unusable card vault key, or
 * outbound calls to private networks. Fail closed: a misconfigured production pod never serves traffic.
 */
@Component
@Profile("prod | (!test & !sandbox & !dev)")
public class ProductionSafetyCheck {
    /** Secrets that have appeared in this repository and are therefore public. */
    static final Set<String> KNOWN_SECRETS = Set.of(
            "elepay-default-signing-secret-key-32b", "elepay-default-secret-card-vault-key-32b!",
            "dev-only-secret-change-me-at-least-32-bytes", "sandbox-only-dev-secret-change-me-32bytes",
            "dev-only-signing-secret-change-me-32-bytes", "sandbox-ussd-secret",
            "ZGV2LW9ubHktY2FyZC12YXVsdC1rZXktMzItYnl0ZXM=", "dGVzdC1vbmx5LWNhcmQtdmF1bHQta2V5LTMyLWJ5dGU=",
            "test-only-signing-secret-at-least-32-bytes",
            "ZGV2LW9ubHktaW50ZWdyaXR5LW1hYy1rZXktZm9yLWF1ZGl0LWNoYWluLTY0LWJ5dGVzLWxvbmctZGF0YSE=");

    public ProductionSafetyCheck(Environment env) {
        List<String> problems = problems(env);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe production configuration:\n - " + String.join("\n - ", problems));
        }
    }

    static List<String> problems(Environment env) {
        List<String> p = new ArrayList<>();
        if (env.getProperty("elepay.rails.sandbox.enabled", Boolean.class, false)) {
            p.add("elepay.rails.sandbox.enabled must be false (simulated money)");
        }
        if (has(env, "elepay.security.dev-jwt-secret")) {
            p.add("elepay.security.dev-jwt-secret must not be set (development tokens)");
        }
        if (!has(env, "spring.security.oauth2.resourceserver.jwt.issuer-uri")) {
            p.add("spring.security.oauth2.resourceserver.jwt.issuer-uri (ELEPAY_OIDC_ISSUER) is required");
        }
        if (!has(env, "spring.security.oauth2.resourceserver.jwt.audiences")) {
            p.add("spring.security.oauth2.resourceserver.jwt.audiences (ELEPAY_OIDC_AUDIENCE) is required");
        }
        if ("framework".equalsIgnoreCase(env.getProperty("server.forward-headers-strategy", ""))) {
            p.add("server.forward-headers-strategy must be native: framework trusts spoofed X-Forwarded-* headers");
        }
        if (!env.getProperty("elepay.rate-limits.enabled", Boolean.class, true)) {
            p.add("elepay.rate-limits.enabled must be true");
        }
        if (!env.getProperty("elepay.security.request-signing.enforce", Boolean.class, true)
                || !env.getProperty("elepay.security.request-signing.require-v2", Boolean.class, true)) {
            p.add("Production merchant API signing and signature version 2 must be enforced");
        }
        if (!env.getProperty("elepay.rate-limits.shared-required", Boolean.class, true)) {
            p.add("Production rate limiting must use shared storage");
        }
        String signing = env.getProperty("elepay.security.signing-secret", "");
        if (!signing.isBlank() && (signing.length() < 32 || KNOWN_SECRETS.contains(signing))) {
            p.add("elepay.security.signing-secret must be at least 32 random characters and not a published value");
        }
        String vault = env.getProperty("elepay.vault.master-key", "");
        if (KNOWN_SECRETS.contains(vault) || !isBase64Key(vault)) {
            p.add("elepay.vault.master-key must be base64 of 32 random bytes (openssl rand -base64 32)");
        }
        String ussd = env.getProperty("elepay.ussd.generic.secret", "");
        if (!ussd.isBlank() && (ussd.length() < 32 || KNOWN_SECRETS.contains(ussd))) {
            p.add("elepay.ussd.generic.secret must be at least 32 random characters");
        }
        String mac = env.getProperty("elepay.integrity.mac-key", "");
        if (KNOWN_SECRETS.contains(mac) || !base64AtLeast(mac, 32)) {
            p.add("elepay.integrity.mac-key must be base64 of at least 32 random bytes (64 recommended)");
        }
        if (!has(env, "elepay.integrity.ed25519-private-key") || !has(env, "elepay.integrity.mldsa65-private-key")) {
            p.add("elepay.integrity.ed25519-private-key and mldsa65-private-key are required (see IntegrityKeys.main)");
        }
        if (env.getProperty("elepay.integrity.allow-ephemeral-signing-keys", Boolean.class, false)) {
            p.add("elepay.integrity.allow-ephemeral-signing-keys must be false");
        }
        if (env.getProperty("elepay.outbound.allow-http", Boolean.class, false)
                || env.getProperty("elepay.outbound.allow-private-hosts", Boolean.class, false)) {
            p.add("elepay.outbound.allow-http and allow-private-hosts must be false (webhook SSRF)");
        }
        int coolingOffHours = env.getProperty("elepay.security.destination-cooling-off-hours", Integer.class, 24);
        if (coolingOffHours < 24) {
            p.add("elepay.security.destination-cooling-off-hours must be >= 24 in production");
        }
        for (String secretProp : List.of(
                "elepay.security.signing-secret",
                "elepay.vault.master-key",
                "elepay.integrity.mac-key",
                "elepay.integrity.ed25519-private-key",
                "elepay.integrity.mldsa65-private-key",
                "elepay.ussd.generic.secret"
        )) {
            String val = env.getProperty(secretProp, "");
            if (KmsSecretProvider.isDiskFileSecret(val)) {
                p.add(secretProp + " must be provided via KMS/HSM, never a disk secret file path (" + val + ")");
            }
        }
        return p;
    }

    private static boolean has(Environment env, String key) {
        String v = env.getProperty(key);
        return v != null && !v.isBlank();
    }

    static boolean base64AtLeast(String value, int bytes) {
        try {
            return value != null && Base64.getDecoder().decode(value.trim()).length >= bytes;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static boolean isBase64Key(String value) {
        try {
            return value != null && Base64.getDecoder().decode(value.trim()).length == 32;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
