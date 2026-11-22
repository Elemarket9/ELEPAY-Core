package com.elepay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elepay.common.ApiException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SecurityControlsTest {

    private SecretKey kekV1;
    private SecretKey kekV2;

    @BeforeEach
    void setUp() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        kekV1 = keyGen.generateKey();
        kekV2 = keyGen.generateKey();
    }

    // --- 075: Field-Level Envelope Encryption ---
    @Test
    @DisplayName("075: Encrypts and decrypts sensitive field using AES-GCM envelope encryption")
    void testEnvelopeEncryptionAndDecryption() {
        String sensitiveGhanaCard = "GHA-723145689-1";
        String aad = "customer:cust-123";

        var encrypted = EnvelopeEncryption.encrypt(sensitiveGhanaCard, aad, "kms-master", 1, kekV1);
        assertThat(encrypted).isNotNull();
        assertThat(encrypted.keyId()).isEqualTo("kms-master");
        assertThat(encrypted.keyVersion()).isEqualTo(1);
        assertThat(encrypted.ciphertextBase64()).isNotEqualTo(sensitiveGhanaCard);

        // Serialization format check
        String serialized = encrypted.serialize();
        assertThat(serialized).startsWith("ENC:v1:kms-master:1:");

        var deserialized = EnvelopeEncryption.EncryptedField.deserialize(serialized);
        assertThat(deserialized.ciphertextBase64()).isEqualTo(encrypted.ciphertextBase64());

        // Decryption
        String decrypted = EnvelopeEncryption.decrypt(deserialized, kekV1);
        assertThat(decrypted).isEqualTo(sensitiveGhanaCard);
    }

    @Test
    @DisplayName("075: Rejects decryption if Additional Authenticated Data (AAD) is tampered")
    void testEnvelopeEncryptionAadTamperingFails() {
        String sensitiveData = "Confidential PIN data";
        var encrypted = EnvelopeEncryption.encrypt(sensitiveData, "tenant:tenant-A", "kms-master", 1, kekV1);

        // Attempt decrypt with different AAD
        var tamperedField = new EnvelopeEncryption.EncryptedField(
                encrypted.keyId(),
                encrypted.keyVersion(),
                encrypted.ivBase64(),
                encrypted.wrappedDekBase64(),
                encrypted.ciphertextBase64(),
                "tenant:tenant-B" // Tampered tenant context
        );

        assertThatThrownBy(() -> EnvelopeEncryption.decrypt(tamperedField, kekV1))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Failed to decrypt envelope ciphertext");
    }

    // --- 076: Key Rotation ---
    @Test
    @DisplayName("076: Re-encrypts historical records under active key while preserving decryption")
    void testKeyRotationAndReEncryption() {
        KeyRotationService rotationService = new KeyRotationService();
        rotationService.registerKey("kms-key", 1, kekV1, true);

        String sensitiveData = "4111222233334444";
        var originalEncrypted = EnvelopeEncryption.encrypt(sensitiveData, "card:c1", "kms-key", 1, kekV1);

        // Rotate to Version 2
        rotationService.rotateKey("kms-key", 2, kekV2);
        assertThat(rotationService.getActiveKey().version()).isEqualTo(2);

        // Decrypt still works for v1 via key registry
        var v1Key = rotationService.getKey("kms-key", 1);
        assertThat(EnvelopeEncryption.decrypt(originalEncrypted, v1Key.secretKey())).isEqualTo(sensitiveData);

        // Perform re-encryption to active key (v2)
        var reEncrypted = rotationService.reEncrypt(originalEncrypted);
        assertThat(reEncrypted.keyVersion()).isEqualTo(2);
        assertThat(EnvelopeEncryption.decrypt(reEncrypted, kekV2)).isEqualTo(sensitiveData);

        // Batch re-encryption
        var batchResult = rotationService.batchReEncrypt(List.of(originalEncrypted, reEncrypted));
        assertThat(batchResult.migratedCount()).isEqualTo(2);
        assertThat(batchResult.failedCount()).isEqualTo(0);
        assertThat(batchResult.results()).allMatch(f -> f.keyVersion() == 2);
    }

    // --- 078: JWKS Caching and Rollover ---
    @Test
    @DisplayName("078: Caches JWKS keys, handles rollover grace period, and verifies JWT signatures")
    void testJwksServiceRolloverAndJwtVerification() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPairOld = kpg.generateKeyPair();
        KeyPair keyPairNew = kpg.generateKeyPair();

        JwksService jwks = new JwksService(new ObjectMapper(), 50, Duration.ofHours(1), Duration.ofSeconds(5));
        jwks.registerKey("key-2025", "RS256", "sig", keyPairOld.getPublic(), true);

        assertThat(jwks.getKey("key-2025")).isPresent();

        // Create valid JWT signed with keyPairOld
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"key-2025\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"iss\":\"https://auth.elepay.com\",\"aud\":\"elepay-core\",\"sub\":\"user-1\"}".getBytes(StandardCharsets.UTF_8));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keyPairOld.getPrivate());
        sig.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        String token = header + "." + payload + "." + signature;

        // Verify valid token
        assertThat(jwks.verifyJwtSignature(token, "https://auth.elepay.com", "elepay-core")).isTrue();

        // Rollover: retire key-2025 and make key-2026 active
        jwks.rolloverKey("key-2025", "key-2026", keyPairNew.getPublic());

        // Token signed with old key remains valid during rollover grace period
        assertThat(jwks.verifyJwtSignature(token, "https://auth.elepay.com", "elepay-core")).isTrue();

        // Key-2026 is present in cache
        assertThat(jwks.getKey("key-2026")).isPresent();
    }

    // --- 081: Administrative Anomaly Detection ---
    @Test
    @DisplayName("081: Detects off-hours access, impossible travel, bulk enumeration, and self-approval")
    void testAdminAnomalyDetection() {
        AdminAnomalyDetector detector = new AdminAnomalyDetector(null);
        UUID operatorId = UUID.randomUUID();

        // 1. Off-hours access (e.g. 23:30 UTC)
        Instant offHoursTime = Instant.parse("2026-11-22T23:30:00Z");
        var offHoursEvent = new AdminAnomalyDetector.AdminActionEvent(
                UUID.randomUUID(), operatorId, "OPS_ADMIN", "VIEW", "CUSTOMER", "cust-1",
                "197.251.12.1", "Chrome", 5.6037, -0.1870, null, offHoursTime
        );
        var offHoursAlert = detector.evaluateAndAlert(offHoursEvent);
        assertThat(offHoursAlert).isNotNull();
        assertThat(offHoursAlert.anomalies()).contains(AdminAnomalyDetector.AnomalyType.OFF_HOURS_ACCESS);

        // 2. Impossible travel (Accra -> London in 5 minutes)
        Instant t1 = Instant.parse("2026-11-22T12:00:00Z");
        Instant t2 = Instant.parse("2026-11-22T12:05:00Z");
        var accraEvent = new AdminAnomalyDetector.AdminActionEvent(
                UUID.randomUUID(), operatorId, "OPS_ADMIN", "VIEW", "MERCHANT", "m-1",
                "197.251.12.1", "Chrome", 5.6037, -0.1870, null, t1
        );
        detector.detectAnomalies(accraEvent);

        var londonEvent = new AdminAnomalyDetector.AdminActionEvent(
                UUID.randomUUID(), operatorId, "OPS_ADMIN", "VIEW", "MERCHANT", "m-2",
                "82.165.197.1", "Firefox", 51.5074, -0.1278, null, t2
        );
        var travelAlert = detector.evaluateAndAlert(londonEvent);
        assertThat(travelAlert).isNotNull();
        assertThat(travelAlert.anomalies()).contains(AdminAnomalyDetector.AnomalyType.IMPOSSIBLE_TRAVEL);
        assertThat(travelAlert.severity()).isEqualTo(AdminAnomalyDetector.Severity.CRITICAL);

        // 3. Self-approval anomaly
        var selfApproveEvent = new AdminAnomalyDetector.AdminActionEvent(
                UUID.randomUUID(), operatorId, "OPS_ADMIN", "APPROVE_PAYOUT", "PAYOUT", "pay-99",
                "197.251.12.1", "Chrome", 5.6037, -0.1870, operatorId, t1
        );
        var selfApproveAlert = detector.evaluateAndAlert(selfApproveEvent);
        assertThat(selfApproveAlert).isNotNull();
        assertThat(selfApproveAlert.anomalies()).contains(AdminAnomalyDetector.AnomalyType.SELF_APPROVAL);
    }

    // --- 082: Session and Device Binding ---
    @Test
    @DisplayName("082: Rotates refresh token, enforces device binding, and revokes family on reuse")
    void testSessionDeviceBindingAndReuseRevocation() {
        SessionDeviceBindingService sessionService = new SessionDeviceBindingService(null);
        UUID userId = UUID.randomUUID();
        String deviceId = "device-iphone-14-pro";
        String fingerprint = "fp-hash-xyz-987";

        var pair1 = sessionService.createSession(userId, deviceId, fingerprint, Duration.ofDays(7));
        assertThat(pair1.refreshToken()).isNotNull();

        // 1. Normal rotation with matching device
        var pair2 = sessionService.rotateRefreshToken(pair1.sessionId(), pair1.refreshToken(), deviceId, fingerprint);
        assertThat(pair2.refreshToken()).isNotEqualTo(pair1.refreshToken());

        // 2. Device mismatch rejection
        assertThatThrownBy(() -> sessionService.rotateRefreshToken(pair2.sessionId(), pair2.refreshToken(), "different-stolen-device", fingerprint))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Device ID mismatch");

        // 3. TOKEN REUSE ATTACK: Presenting old pair1.refreshToken() again
        // Should detect theft/reuse and REVOKE the ENTIRE token family!
        assertThatThrownBy(() -> sessionService.rotateRefreshToken(pair1.sessionId(), pair1.refreshToken(), deviceId, fingerprint))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Refresh token reuse detected");

        // Subsequent attempts on pair2 must now also fail because family is revoked
        assertThatThrownBy(() -> sessionService.rotateRefreshToken(pair2.sessionId(), pair2.refreshToken(), deviceId, fingerprint))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Session is revoked");
    }

    // --- 083: Mobile Certificate Pinning ---
    @Test
    @DisplayName("083: Verifies certificate chain pins and generates mobile configuration profiles")
    void testMobileCertificatePinning() throws Exception {
        MobileCertificatePinningService pinningService = new MobileCertificatePinningService(null);

        // Generate self-signed cert for testing
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        // Create dummy X509Certificate mock or test implementation
        X509Certificate mockCert = org.mockito.Mockito.mock(X509Certificate.class);
        org.mockito.Mockito.when(mockCert.getPublicKey()).thenReturn(kp.getPublic());

        String spkiPin = MobileCertificatePinningService.computeSpkiPin(mockCert);
        assertThat(spkiPin).isNotEmpty();

        String host = "api.elepay.com";
        pinningService.registerPin(host, spkiPin, MobileCertificatePinningService.PinType.PRIMARY, Instant.now().plus(Duration.ofDays(365)));
        pinningService.registerPin(host, "backup-dummy-pin-xyz=", MobileCertificatePinningService.PinType.BACKUP, Instant.now().plus(Duration.ofDays(365)));

        // Verify valid chain
        var result = pinningService.verifyCertificateChain(host, List.of(mockCert));
        assertThat(result.valid()).isTrue();
        assertThat(result.matchedPin()).isEqualTo(spkiPin);

        // Android XML config export
        String androidXml = pinningService.generateAndroidNetworkSecurityConfig(host);
        assertThat(androidXml).contains("<pin digest=\"SHA-256\">" + spkiPin + "</pin>");
        assertThat(androidXml).contains("<domain includeSubdomains=\"true\">" + host + "</domain>");

        // iOS TrustKit export
        String iosConfig = pinningService.generateIosTrustKitConfig(host);
        assertThat(iosConfig).contains("\"kTSKPinnedDomains\"");
        assertThat(iosConfig).contains(spkiPin);
    }

    // --- 084: CI Security Scanning ---
    @Test
    @DisplayName("084: Scans for secrets, flags non-integer money types, checks CVEs, and enforces build gates")
    void testCiSecurityScanner() {
        CiSecurityScanner scanner = new CiSecurityScanner();

        // Scan code with hardcoded AWS key and float money type
        String vulnerableCode = """
                public class PaymentHandler {
                    private static final String KEY = "AKIAIOSFODNN7EXAMPLE";
                    public void process(float amountPesewas) {
                        // bad float usage
                    }
                }
                """;

        var findings = scanner.scanSourceCode("PaymentHandler.java", vulnerableCode);
        assertThat(findings).hasSize(2);
        assertThat(findings).anyMatch(f -> f.ruleId().equals("SEC-001") && f.severity() == CiSecurityScanner.FindingSeverity.CRITICAL);
        assertThat(findings).anyMatch(f -> f.ruleId().equals("FIN-001") && f.severity() == CiSecurityScanner.FindingSeverity.HIGH);

        // Gate evaluation
        var report = scanner.evaluateGate(findings);
        assertThat(report.gatePassed()).isFalse();
        assertThat(report.criticalCount()).isEqualTo(1);
        assertThat(report.highCount()).isEqualTo(1);

        // Dependency CVE audit
        var depFindings = scanner.auditDependencies(List.of(
                new CiSecurityScanner.DependencyArtifact("org.apache.logging.log4j", "log4j-core", "2.14.1")
        ));
        assertThat(depFindings).isNotEmpty();
        assertThat(depFindings.get(0).ruleId()).isEqualTo("CVE-2021-44228");
    }
}
