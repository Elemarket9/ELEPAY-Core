package com.elepay.rails;

import com.elepay.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GhQrDynamicServiceTest extends PostgresTestBase {

    @Autowired
    private GhQrDynamicService qrService;

    @Test
    @DisplayName("Dynamic GhQR lifecycle: generation, CRC-16 validation, and fulfillment")
    void testGhQrLifecycle() {
        UUID merchantId = UUID.randomUUID();
        long amountPesewas = 15000L; // 150.00 GHS

        var intent = qrService.createDynamicIntent(
                merchantId,
                "TERM-001",
                amountPesewas,
                "GHS",
                "Shoprite Mall",
                "Accra",
                Duration.ofMinutes(20)
        );

        assertThat(intent).isNotNull();
        assertThat(intent.qrReference()).startsWith("GHQR-");
        assertThat(intent.status()).isEqualTo("ACTIVE");
        assertThat(intent.crcChecksum()).hasSize(4);
        assertThat(intent.payloadText()).isNotEmpty();

        // Validate payload CRC and fields
        var valResult = qrService.validateQrPayload(intent.payloadText());
        assertThat(valResult.valid()).isTrue();
        assertThat(valResult.qrReference()).isEqualTo(intent.qrReference());
        assertThat(valResult.amountPesewas()).isEqualTo(15000L);
        assertThat(valResult.merchantName()).isEqualTo("Shoprite Mall");

        // Fulfill payment
        String paymentRef = "MOMO-" + UUID.randomUUID();
        var paid = qrService.markPaid(intent.qrReference(), paymentRef);
        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(paid.paymentReference()).isEqualTo(paymentRef);
        assertThat(paid.paidAt()).isNotNull();

        // Expire check
        int expired = qrService.expireStaleIntents();
        assertThat(expired).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("GhQR validation handles merchant names containing '6304' or '63' and bounds checks")
    void testPayloadWithCrcPatternInMerchantNameAndTlvBoundsChecks() {
        UUID merchantId = UUID.randomUUID();
        // Merchant name contains "6304" which would trick a naive lastIndexOf / substring search
        var intent = qrService.createDynamicIntent(
                merchantId,
                "TERM-063",
                25000L,
                "GHS",
                "Store 6304 Accra Branch",
                "Accra",
                Duration.ofMinutes(15)
        );

        var valResult = qrService.validateQrPayload(intent.payloadText());
        assertThat(valResult.valid()).isTrue();
        assertThat(valResult.merchantName()).isEqualTo("Store 6304 Accra Branch");
        assertThat(valResult.amountPesewas()).isEqualTo(25000L);

        // Test EmvcoQr.parseTlv bounds safety
        assertThat(com.elepay.common.qr.EmvcoQr.parseTlv(null)).isEmpty();
        // Negative length field should not crash
        assertThat(com.elepay.common.qr.EmvcoQr.parseTlv("00-1test")).isEmpty();
        // Length overflow should terminate gracefully
        assertThat(com.elepay.common.qr.EmvcoQr.parseTlv("0099short")).isEmpty();
    }
}
