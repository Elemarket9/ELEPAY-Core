package com.elepay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSafetyCheckTest {

    MockEnvironment safe() {
        return new MockEnvironment()
                .withProperty("elepay.rails.sandbox.enabled", "false")
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://id.elepay.example/realms/elepay")
                .withProperty("elepay.security.signing-secret", "Qm9kZ2V0LXJhbmRvbS1zZWNyZXQtdmFsdWUtNDJ4eXo=")
                .withProperty("elepay.vault.master-key", "o3Hc0gWk0Gk2cJ7Vq0r9b2p8Yz1mN4tE6aS5dF7gH8k=")
                .withProperty("elepay.outbound.allow-http", "false")
                .withProperty("elepay.outbound.allow-private-hosts", "false");
    }

    @Test
    void aProperlyConfiguredProductionStarts() {
        assertThat(ProductionSafetyCheck.problems(safe())).isEmpty();
        new ProductionSafetyCheck(safe());
    }

    @Test
    void theOldDefaultsAreRefused() {
        var env = safe()
                .withProperty("elepay.rails.sandbox.enabled", "true")
                .withProperty("elepay.security.dev-jwt-secret", "dev-only-secret-change-me-at-least-32-bytes")
                .withProperty("elepay.security.signing-secret", "elepay-default-signing-secret-key-32b")
                .withProperty("elepay.vault.master-key", "elepay-default-secret-card-vault-key-32b!")
                .withProperty("elepay.outbound.allow-private-hosts", "true");
        assertThat(ProductionSafetyCheck.problems(env)).hasSize(5);
        assertThatThrownBy(() -> new ProductionSafetyCheck(env)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox");
    }

    @Test
    void aMissingVaultKeyOrIssuerIsRefused() {
        var env = new MockEnvironment();
        assertThat(ProductionSafetyCheck.problems(env)).anyMatch(p -> p.contains("vault"))
                .anyMatch(p -> p.contains("issuer"));
    }
}
