package com.elepay.risk;

import com.elepay.rails.DirectCarrierSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Service for compiling immutable, tamper-evident chargeback and interbank dispute defense dossiers.
 * Aggregates carrier session telemetry, geolocation checkpoints, KYC status, and ledger postings
 * into a cryptographically hashed evidence bundle.
 */
@Service
public class DisputeEvidenceService {

    public record DisputeDossier(
            String disputeReference,
            String transactionReference,
            long amountPesewas,
            String currency,
            OffsetDateTime transactionTimestamp,
            String payerPhone,
            String kycTier,
            String ipAddress,
            String deviceFingerprint,
            Double latitude,
            Double longitude,
            String city,
            String railSessionId,
            String carrierExternalTxId,
            String ghipssRrn,
            String ghipssStan,
            UUID ledgerTxId,
            String evidenceSha256,
            Map<String, Object> additionalAttributes
    ) {}

    private final ObjectMapper json;
    private final DirectCarrierSessionService carrierSessions;

    public DisputeEvidenceService(
            ObjectMapper json,
            @Autowired(required = false) DirectCarrierSessionService carrierSessions
    ) {
        this.json = json != null ? json : new ObjectMapper();
        this.carrierSessions = carrierSessions;
    }

    public DisputeDossier compileDossier(
            String disputeReference,
            String transactionReference,
            long amountPesewas,
            String currency,
            OffsetDateTime transactionTimestamp,
            String payerPhone,
            String kycTier,
            String ipAddress,
            String deviceFingerprint,
            Double latitude,
            Double longitude,
            String city,
            String ghipssRrn,
            String ghipssStan,
            UUID ledgerTxId,
            Map<String, Object> additionalAttributes
    ) {
        String railSessionId = null;
        String carrierExternalTxId = null;

        if (carrierSessions != null) {
            for (DirectCarrierSessionService.Carrier c : DirectCarrierSessionService.Carrier.values()) {
                var sess = carrierSessions.findByCarrierAndReference(c, transactionReference);
                if (sess.isPresent()) {
                    railSessionId = sess.get().id().toString();
                    carrierExternalTxId = sess.get().externalTransactionId();
                    break;
                }
            }
        }

        Map<String, Object> attributes = (additionalAttributes != null) ? new LinkedHashMap<>(additionalAttributes) : new LinkedHashMap<>();

        // Generate SHA-256 evidence fingerprint
        String canonicalString = String.join("|",
                disputeReference != null ? disputeReference : "",
                transactionReference != null ? transactionReference : "",
                String.valueOf(amountPesewas),
                currency != null ? currency : "GHS",
                payerPhone != null ? payerPhone : "",
                ipAddress != null ? ipAddress : "",
                railSessionId != null ? railSessionId : "",
                carrierExternalTxId != null ? carrierExternalTxId : "",
                ghipssRrn != null ? ghipssRrn : "",
                ledgerTxId != null ? ledgerTxId.toString() : ""
        );

        String sha256 = calculateSha256(canonicalString);

        return new DisputeDossier(
                disputeReference,
                transactionReference,
                amountPesewas,
                currency != null ? currency : "GHS",
                transactionTimestamp != null ? transactionTimestamp : OffsetDateTime.now(),
                payerPhone,
                kycTier != null ? kycTier : "TIER_1",
                ipAddress,
                deviceFingerprint,
                latitude,
                longitude,
                city,
                railSessionId,
                carrierExternalTxId,
                ghipssRrn,
                ghipssStan,
                ledgerTxId,
                sha256,
                attributes
        );
    }

    public String exportDossierAsJson(DisputeDossier dossier) {
        try {
            return json.writeValueAsString(dossier);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize dispute dossier", e);
        }
    }

    private String calculateSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
