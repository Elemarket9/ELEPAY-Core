package com.elepay.rails;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DirectCarrierSessionTest extends PostgresTestBase {

    @Autowired
    private DirectCarrierSessionService sessionService;

    @Test
    @DisplayName("Direct carrier session full lifecycle: creation, update, retry, and query")
    void testCarrierSessionLifecycle() {
        String referenceId = "REF-MTN-" + UUID.randomUUID();
        long amountPesewas = 25000L; // 250.00 GHS

        var session = sessionService.createSession(
                DirectCarrierSessionService.Carrier.MTN,
                DirectCarrierSessionService.SessionType.COLLECTION,
                referenceId,
                "MOBILE_MONEY",
                "+233241234567",
                amountPesewas,
                "GHS",
                "{\"payer\": \"0241234567\"}"
        );

        assertThat(session).isNotNull();
        assertThat(session.id()).isNotNull();
        assertThat(session.status()).isEqualTo("INITIATED");
        assertThat(session.amountPesewas()).isEqualTo(25000L);
        assertThat(session.carrier()).isEqualTo("MTN");

        // Update to SUCCESS
        String externalTxId = "MTN-EXT-" + UUID.randomUUID();
        sessionService.markSuccess(session.id(), externalTxId, "{\"status\": \"SUCCESSFUL\"}");

        var updated = sessionService.findById(session.id());
        assertThat(updated).isPresent();
        assertThat(updated.get().status()).isEqualTo("SUCCESS");
        assertThat(updated.get().externalTransactionId()).isEqualTo(externalTxId);

        // Find by carrier and reference
        var foundByRef = sessionService.findByCarrierAndReference(
                DirectCarrierSessionService.Carrier.MTN,
                referenceId
        );
        assertThat(foundByRef).isPresent();
        assertThat(foundByRef.get().id()).isEqualTo(session.id());

        // Increment retry
        sessionService.incrementRetry(session.id());
        var retried = sessionService.findById(session.id());
        assertThat(retried).isPresent();
        assertThat(retried.get().retryCount()).isEqualTo(1);
    }
}
