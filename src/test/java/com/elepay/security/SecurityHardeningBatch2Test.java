package com.elepay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

class SecurityHardeningBatch2Test {

    @Test
    void kmsSecretProviderStrictModeRejectsPlaintext() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        SecretKey kek = kg.generateKey();
        KmsSecretProvider kms = new KmsSecretProvider(new KmsSecretProvider.KeyManagementBackend() {
            @Override public SecretKey loadKek() { return kek; }
            @Override public String description() { return "test-kek"; }
        });

        String wrapped = kms.wrapSecret("sec_super_secret", "api_credential:123");
        assertThat(wrapped).startsWith("ENC:v1:");
        assertThat(kms.unwrapSecretStrict(wrapped, "api_credential:123")).isEqualTo("sec_super_secret");

        assertThatThrownBy(() -> kms.unwrapSecretStrict("sec_plaintext", "api_credential:123"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Plaintext secrets are forbidden");
    }

    @Test
    void envelopeEncryptionSanitizesErrorsAndMapsDeserializeFailures() {
        assertThatThrownBy(() -> EnvelopeEncryption.EncryptedField.deserialize("ENC:v1:kid:notanumber:iv:dek:ct:YQ=="))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Malformed serialized ciphertext envelope");
    }

    @Test
    void jwksServiceRejectsMissingExpSupportsArrayAudAndEnforcesNbf() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        JwksService jwks = new JwksService(new ObjectMapper());
        jwks.registerKey("k1", "RS256", "sig", kp.getPublic(), true);

        long now = Instant.now().getEpochSecond();
        // 1. Missing exp -> rejected
        String noExpJwt = signJwt(kp, "k1", "{\"iss\":\"https://id\",\"aud\":\"api\"}");
        assertThat(jwks.verifyJwtSignature(noExpJwt, "https://id", "api")).isFalse();

        // 2. Array aud + valid exp -> accepted
        String arrayAudJwt = signJwt(kp, "k1",
                "{\"iss\":\"https://id\",\"aud\":[\"other\",\"api\"],\"exp\":" + (now + 300) + ",\"nbf\":" + (now + 30) + "}");
        assertThat(jwks.verifyJwtSignature(arrayAudJwt, "https://id", "api")).isTrue();

        // 3. nbf beyond 60s skew -> rejected
        String futureNbfJwt = signJwt(kp, "k1",
                "{\"iss\":\"https://id\",\"aud\":\"api\",\"exp\":" + (now + 600) + ",\"nbf\":" + (now + 120) + "}");
        assertThat(jwks.verifyJwtSignature(futureNbfJwt, "https://id", "api")).isFalse();
    }

    @Test
    void sessionDeviceBindingRequiresNonBlankFingerprintWhenSessionHasFingerprint() {
        var service = new SessionDeviceBindingService(null);
        var pair = service.createSession(UUID.randomUUID(), "dev-1", "fp-abc", Duration.ofHours(1));

        assertThatThrownBy(() -> service.rotateRefreshToken(pair.sessionId(), pair.refreshToken(), "dev-1", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Device fingerprint mismatch");

        assertThatThrownBy(() -> service.rotateRefreshToken(pair.sessionId(), pair.refreshToken(), "dev-1", "   "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Device fingerprint mismatch");
    }

    @Test
    void makerCheckerValidatesPayloadPerActionType() {
        var service = new MakerCheckerService(null, null, null, new ObjectMapper(), null, null);

        assertThatThrownBy(() -> service.propose("KILL_SWITCH_CHANGE", "kill_switch", "global", null,
                Map.of("target", "invalid"), "reason", "ops-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("KILL_SWITCH_CHANGE");

        assertThatThrownBy(() -> service.propose("MODULE_ENABLE", "module", "lending", null,
                Map.of(), "reason", "ops-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("MODULE_ENABLE");

        assertThatThrownBy(() -> service.propose("MERCHANT_UNFREEZE", "merchant", "m1", null,
                Map.of(), "reason", "ops-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("MERCHANT_UNFREEZE");
    }

    @Test
    void productionSafetyCheckEnforcesStrictKillSwitchAndCidrOverlap() {
        var base = new ProductionSafetyCheckTest().safe();

        var relaxedStage1 = new MockEnvironment();
        for (String key : new String[]{
                "elepay.rails.sandbox.enabled", "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                "elepay.security.signing-secret", "elepay.vault.master-key", "elepay.outbound.allow-http",
                "elepay.outbound.allow-private-hosts", "elepay.integrity.mac-key",
                "elepay.integrity.ed25519-private-key", "elepay.integrity.mldsa65-private-key",
                "spring.security.oauth2.resourceserver.jwt.audiences", "server.forward-headers-strategy",
                "elepay.pos.dukpt.bdk-hex", "elepay.environment", "elepay.security.trusted-proxies",
                "elepay.outbound.partner-egress.allowed-hosts"}) {
            relaxedStage1.setProperty(key, base.getProperty(key));
        }
        relaxedStage1.setProperty("elepay.stage1.strict-kill-switch-enabled", "false");
        assertThat(ProductionSafetyCheck.problems(relaxedStage1))
                .anyMatch(p -> p.contains("elepay.stage1.strict-kill-switch-enabled"));

        relaxedStage1.setProperty("elepay.stage1.strict-kill-switch-enabled", "true");
        relaxedStage1.setProperty("elepay.security.trusted-proxies", "0.0.0.0/1");
        assertThat(ProductionSafetyCheck.problems(relaxedStage1))
                .anyMatch(p -> p.contains("trusted-proxies"));
    }

    private static String signJwt(KeyPair kp, String kid, String payloadJson) throws Exception {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"alg\":\"RS256\",\"kid\":\"" + kid + "\"}").getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        String s = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        return header + "." + payload + "." + s;
    }
}
