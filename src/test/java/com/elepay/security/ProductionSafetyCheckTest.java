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
                .withProperty("elepay.outbound.allow-private-hosts", "false")
                .withProperty("elepay.integrity.mac-key", java.util.Base64.getEncoder().encodeToString(new byte[64]))
                .withProperty("elepay.integrity.ed25519-private-key", "configured")
                .withProperty("elepay.integrity.mldsa65-private-key", "configured")
                .withProperty("spring.security.oauth2.resourceserver.jwt.audiences", "elepay-api")
                .withProperty("server.forward-headers-strategy", "native")
                .withProperty("elepay.pos.dukpt.bdk-hex", "A1B2C3D4E5F60718293A4B5C6D7E8F90");
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

    @Test
    void diskSecretFilePathsAndShortCoolingOffAreRefusedInProduction() {
        var env = safe()
                .withProperty("elepay.integrity.ed25519-private-key", "file:/etc/elepay/ed25519.pem")
                .withProperty("elepay.security.destination-cooling-off-hours", "0");
        assertThat(ProductionSafetyCheck.problems(env))
                .anyMatch(p -> p.contains("disk secret file path"))
                .anyMatch(p -> p.contains("destination-cooling-off-hours"));
    }
}
