package com.elepay.checkout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Algorithm 022: EMVCo QR Code Encoding & Decoding")
class EmvcoQrTest {

    @Test
    @DisplayName("CRC16-CCITT RFC standard test vector: 123456789 -> 29B1")
    void crc16StandardVector() {
        String crc = EmvcoQr.calculateCrc("123456789");
        assertEquals("29B1", crc);
    }

    @Test
    @DisplayName("Encode and decode static merchant EMVCo QR payload")
    void encodeStaticQr() {
        String payload = EmvcoQr.builder()
                .pointOfInitiation(EmvcoQr.POINT_STATIC)
                .merchantId("b3d7a8e2-0000-0000-0000-000000000001")
                .merchantCode("MER123")
                .merchantName("Accra Fresh Store")
                .merchantCity("Accra")
                .currency("936")
                .countryCode("GH")
                .build();

        assertNotNull(payload);
        assertTrue(payload.startsWith("000201010211"));
        assertTrue(payload.contains("5303936"));
        assertTrue(payload.contains("5802GH"));
        assertTrue(payload.contains("6005Accra"));
        assertTrue(payload.contains("6304"));

        // Verify CRC matches EMVCo standard calculation
        String prefix = payload.substring(0, payload.length() - 4);
        String expectedCrc = payload.substring(payload.length() - 4);
        assertEquals(EmvcoQr.calculateCrc(prefix), expectedCrc);

        // Decode
        EmvcoQr.Decoded decoded = EmvcoQr.decode(payload);
        assertTrue(decoded.valid());
        assertEquals("01", decoded.formatIndicator());
        assertEquals(EmvcoQr.POINT_STATIC, decoded.pointOfInitiation());
        assertEquals("MER123", decoded.merchantCode());
        assertEquals("Accra Fresh Store", decoded.merchantName());
        assertEquals("Accra", decoded.merchantCity());
        assertEquals("936", decoded.currency());
        assertEquals("GH", decoded.countryCode());
        assertNull(decoded.amount());
    }

    @Test
    @DisplayName("Encode and decode dynamic EMVCo QR payload with amount and reference")
    void encodeDynamicQrWithAmountAndReference() {
        String payload = EmvcoQr.builder()
                .pointOfInitiation(EmvcoQr.POINT_DYNAMIC)
                .merchantId("b3d7a8e2-0000-0000-0000-000000000002")
                .merchantCode("SHOP88")
                .merchantName("Supermarket QR")
                .merchantCity("Accra")
                .currency("936")
                .countryCode("GH")
                .amount("80.00")
                .reference("ORD-12345")
                .build();

        assertNotNull(payload);
        assertTrue(payload.startsWith("000201010212"));
        assertTrue(payload.contains("540580.00"));
        assertTrue(payload.contains("62")); // Additional data

        EmvcoQr.Decoded decoded = EmvcoQr.decode(payload);
        assertTrue(decoded.valid());
        assertEquals(EmvcoQr.POINT_DYNAMIC, decoded.pointOfInitiation());
        assertEquals("SHOP88", decoded.merchantCode());
        assertEquals("80.00", decoded.amount());
        assertEquals("ORD-12345", decoded.additionalData().get("05"));
    }

    @Test
    @DisplayName("Corrupted QR payload fails validation")
    void corruptedPayloadFails() {
        String payload = EmvcoQr.builder()
                .pointOfInitiation(EmvcoQr.POINT_STATIC)
                .merchantCode("MER123")
                .merchantName("Store")
                .build();

        // Tamper with merchant name character without updating CRC
        String tampered = payload.replace("Store", "Storz");
        EmvcoQr.Decoded decoded = EmvcoQr.decode(tampered);
        assertFalse(decoded.valid());
    }
}
