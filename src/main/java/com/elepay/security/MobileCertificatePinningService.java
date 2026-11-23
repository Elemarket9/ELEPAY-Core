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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Mobile certificate pinning service (Algorithm 083).
 * Enforces TLS public key hash pinning (SPKI SHA-256) for mobile apps,
 * supports primary and backup pins for zero-downtime rotation,
 * and exports mobile platform configurations (Android and iOS).
 *
 * <p><b>Config-driven, safe by default.</b> Pins come only from configuration
 * ({@code elepay.mobile.cert-pinning.pins}); none are hardcoded. With no configured pins the service
 * is a no-op: {@link #verifyCertificateChain} reports "no pins configured" and {@link #isEnforced()}
 * is false, so wiring the bean cannot accidentally break TLS. Supply pins (and at least one BACKUP per
 * host for zero-downtime rotation) to turn enforcement on.
 *
 * <p><b>Enforcement seam.</b> This bean computes and matches SPKI pins but does not itself intercept
 * TLS — that hook lives in the HTTP/TLS client layer this module does not own. A mobile-facing egress
 * client (OkHttp {@code CertificatePinner}, JDK {@code HttpClient} with a custom {@code X509TrustManager},
 * or an inbound mTLS verifier) should call {@link #verifyCertificateChain(String, List)} (or
 * {@link #assertPinned(String, List)} to fail closed) with the peer chain. The exported
 * {@link #generateAndroidNetworkSecurityConfig(String)} / {@link #generateIosTrustKitConfig(String)}
 * feed the on-device pinning for the mobile apps themselves.
 */
@Service
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

    @Autowired
    public MobileCertificatePinningService(
            @Autowired(required = false) JdbcClient db,
            @Value("${elepay.mobile.cert-pinning.pins:}") String configuredPins) {
        this.db = db;
        seedFromConfig(configuredPins);
    }

    /** Test/explicit constructor; registers no pins (no-op enforcement until pins are added). */
    public MobileCertificatePinningService(JdbcClient db) {
        this.db = db;
    }

    /**
     * Seeds the in-memory pin store from configuration. Each entry is
     * {@code host|SPKI_SHA256_BASE64|PRIMARY|BACKUP|<ISO-8601 expiry>}, entries separated by {@code ;}.
     * Example: {@code api.elepay.com|abc...=|PRIMARY|2027-12-31T00:00:00Z}. Malformed entries fail
     * closed at startup rather than being silently skipped. No database writes happen here, so restarts
     * do not duplicate rows and a database outage never blocks startup.
     */
    private void seedFromConfig(String configuredPins) {
        if (configuredPins == null || configuredPins.isBlank()) return; // no-op default
        for (String raw : configuredPins.split(";")) {
            String entry = raw.trim();
            if (entry.isEmpty()) continue;
            String[] parts = entry.split("\\|");
            if (parts.length != 4) {
                throw new IllegalStateException("elepay.mobile.cert-pinning.pins entry must be "
                        + "host|SPKI_SHA256|PIN_TYPE|EXPIRY_ISO8601: " + entry);
            }
            String host = parts[0].trim();
            String pin = parts[1].trim();
            PinType type;
            Instant expiresAt;
            try {
                type = PinType.valueOf(parts[2].trim().toUpperCase(java.util.Locale.ROOT));
                expiresAt = Instant.parse(parts[3].trim());
            } catch (RuntimeException e) {
                throw new IllegalStateException("Invalid pin type or expiry in cert-pinning entry: " + entry, e);
            }
            if (host.isEmpty() || pin.isEmpty()) {
                throw new IllegalStateException("cert-pinning entry has blank host or pin: " + entry);
            }
            pinStore.computeIfAbsent(host, k -> new ArrayList<>())
                    .add(new CertificatePin(host, pin, type, expiresAt, true));
        }
    }

    /** True once at least one host has configured pins, i.e. enforcement is meaningful. */
    public boolean isEnforced() {
        return !pinStore.isEmpty();
    }

    /**
     * Fail-closed enforcement helper for the TLS-client seam: throws {@code 403 certificate_pin_mismatch}
     * when a configured host's chain does not match a valid pin. Hosts with no configured pins pass
     * through (no-op), so this only tightens hosts an operator has explicitly pinned.
     */
    public void assertPinned(String hostname, List<X509Certificate> chain) {
        List<CertificatePin> configured = pinStore.get(hostname);
        if (configured == null || configured.isEmpty()) return; // not pinned: no-op
        PinValidationResult result = verifyCertificateChain(hostname, chain);
        if (!result.valid()) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "certificate_pin_mismatch", result.reason());
        }
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
            } catch (Exception e) {
                // Do not silently swallow: a failed persist means the pin would be lost on restart.
                throw new IllegalStateException(
                        "Failed to persist mobile certificate pin for host " + hostname, e);
            }
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
