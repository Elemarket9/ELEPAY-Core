package com.elepay.risk;

import com.elepay.PostgresTestBase;
import com.elepay.rails.DirectCarrierSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DisputeEvidenceTest extends PostgresTestBase {

    @Autowired
    private DisputeEvidenceService disputeEvidenceService;

    @Autowired
    private DirectCarrierSessionService carrierSessionService;

    @Test
    @DisplayName("Compile immutable dispute defense dossier with cryptographic evidence hash")
    void testDisputeDossierCompilation() {
        String txRef = "TX-" + UUID.randomUUID();
        UUID ledgerTxId = UUID.randomUUID();

        // Create carrier session for this transaction
        carrierSessionService.createSession(
                DirectCarrierSessionService.Carrier.MTN,
                DirectCarrierSessionService.SessionType.COLLECTION,
                txRef,
                "MOBILE_MONEY",
                "+233240001122",
                50000L,
                "GHS",
                "{}"
        );

        var dossier = disputeEvidenceService.compileDossier(
                "DISP-001",
                txRef,
                50000L,
                "GHS",
                OffsetDateTime.now(),
                "+233240001122",
                "TIER_2",
                "197.251.130.45",
                "DEV-FINGERPRINT-ABC",
                5.6037,
                -0.1870,
                "Accra",
                "260301123456",
                "001234",
                ledgerTxId,
                Map.of("itemDescription", "Solar Inverter 5kW", "deliveryTracking", "GH-POST-999")
        );

        assertThat(dossier).isNotNull();
        assertThat(dossier.disputeReference()).isEqualTo("DISP-001");
        assertThat(dossier.transactionReference()).isEqualTo(txRef);
        assertThat(dossier.railSessionId()).isNotNull();
        assertThat(dossier.evidenceSha256()).hasSize(64);

        // Serialize to JSON
        String json = disputeEvidenceService.exportDossierAsJson(dossier);
        assertThat(json).contains("DISP-001");
        assertThat(json).contains(dossier.evidenceSha256());
    }
}
