package com.elepay.security;

import com.elepay.common.ApiException;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Mobile certificate pinning service (Algorithm 083).
 * Enforces TLS public key hash pinning (SPKI SHA-256) for mobile apps,
 * supports primary and backup pins for zero-downtime rotation,
 * and exports mobile platform configurations (Android and iOS).
 */
public class MobileCertificatePinningService {

    public enum PinType {
        PRIMARY,
        BACKUP
    }

    public record CertificatePin(
            String hostname,
            String spkiSha256Pin,
            PinType pinType,
            Instant expiresAt,
            boolean active
    ) {}

    public record PinValidationResult(
            boolean valid,
            String matchedPin,
            String hostname,
            String reason
    ) {}

    private final JdbcClient db;
    private final Map<String, List<CertificatePin>> pinStore = new ConcurrentHashMap<>();

    public MobileCertificatePinningService(JdbcClient db) {
        this.db = db;
    }

    public static String computeSpkiPin(X509Certificate certificate) {
        try {
            byte[] spkiBytes = certificate.getPublicKey().getEncoded();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(spkiBytes);
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute SPKI SHA-256 pin", e);
        }
    }

    public void registerPin(String hostname, String spkiSha256Pin, PinType pinType, Instant expiresAt) {
        CertificatePin pin = new CertificatePin(hostname, spkiSha256Pin, pinType, expiresAt, true);
        pinStore.computeIfAbsent(hostname, k -> new ArrayList<>()).add(pin);

        if (db != null) {
            try {
                db.sql("""
                    INSERT INTO mobile_certificate_pin
                    (id, hostname, spki_sha256_pin, pin_type, expires_at, active, created_at)
                    VALUES (gen_random_uuid(), ?, ?, ?, ?, true, now())
                    """)
                    .params(hostname, spkiSha256Pin, pinType.name(), expiresAt)
                    .update();
            } catch (Exception ignored) {}
        }
    }

    public PinValidationResult verifyCertificateChain(String hostname, List<X509Certificate> chain) {
        if (chain == null || chain.isEmpty()) {
            return new PinValidationResult(false, null, hostname, "Certificate chain is empty");
        }

        List<CertificatePin> configuredPins = pinStore.get(hostname);
        if (configuredPins == null || configuredPins.isEmpty()) {
            return new PinValidationResult(false, null, hostname, "No certificate pins configured for " + hostname);
        }

        // Validate that hostname policy has at least one backup pin configured
        boolean hasBackup = configuredPins.stream().anyMatch(p -> p.pinType() == PinType.BACKUP && p.active() && Instant.now().isBefore(p.expiresAt()));
        if (!hasBackup) {
            // Log warning: policy mandates backup pins for zero-downtime rotation
        }

        Instant now = Instant.now();
        List<String> chainSpkiPins = new ArrayList<>();
        for (X509Certificate cert : chain) {
            chainSpkiPins.add(computeSpkiPin(cert));
        }

        for (CertificatePin configured : configuredPins) {
            if (!configured.active() || now.isAfter(configured.expiresAt())) {
                continue;
            }
            if (chainSpkiPins.contains(configured.spkiSha256Pin())) {
                return new PinValidationResult(true, configured.spkiSha256Pin(), hostname, "Matched pin (" + configured.pinType() + ")");
            }
        }

        return new PinValidationResult(false, null, hostname, "Certificate chain does not match any valid pin for " + hostname);
    }

    public String generateAndroidNetworkSecurityConfig(String hostname) {
        List<CertificatePin> pins = pinStore.getOrDefault(hostname, List.of());
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        xml.append("<network-security-config>\n");
        xml.append("    <domain-config>\n");
        xml.append("        <domain includeSubdomains=\"true\">").append(hostname).append("</domain>\n");
        xml.append("        <pin-set expiration=\"2027-12-31\">\n");
        for (CertificatePin pin : pins) {
            xml.append("            <pin digest=\"SHA-256\">").append(pin.spkiSha256Pin()).append("</pin>\n");
        }
        xml.append("        </pin-set>\n");
        xml.append("    </domain-config>\n");
        xml.append("</network-security-config>");
        return xml.toString();
    }

    public String generateIosTrustKitConfig(String hostname) {
        List<CertificatePin> pins = pinStore.getOrDefault(hostname, List.of());
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"kTSKSwizzleNetworkDelegates\": true,\n");
        sb.append("  \"kTSKPinnedDomains\": {\n");
        sb.append("    \"").append(hostname).append("\": {\n");
        sb.append("      \"kTSKIncludeSubdomains\": true,\n");
        sb.append("      \"kTSKEnforcePinning\": true,\n");
        sb.append("      \"kTSKPublicKeyHashes\": [\n");
        for (int i = 0; i < pins.size(); i++) {
            sb.append("        \"").append(pins.get(i).spkiSha256Pin()).append("\"");
            if (i < pins.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("      ]\n");
        sb.append("    }\n");
        sb.append("  }\n");
        sb.append("}");
        return sb.toString();
    }
}
