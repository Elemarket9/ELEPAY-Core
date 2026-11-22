package com.elepay.common;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class HmacVerificationTest {

    @Test
    void rfc4231TestCase1Vectors() {
        // RFC 4231 Test Case 1: Key = 20 bytes 0x0b, Data = "Hi There"
        byte[] key = new byte[20];
        java.util.Arrays.fill(key, (byte) 0x0b);
        byte[] data = "Hi There".getBytes(StandardCharsets.UTF_8);

        byte[] digest = HmacVerification.hmacSha256(key, data);
        String hex = HexFormat.of().formatHex(digest);
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", hex);
    }

    @Test
    void constantTimeVerification() {
        String secret = "whsec_test_secret_123456789";
        String payload = "{\"amount\":50000,\"currency\":\"GHS\"}";
        String sig = HmacVerification.computeHex(secret, payload);

        assertTrue(HmacVerification.verify(secret, payload, sig));
        assertTrue(HmacVerification.verify(secret, payload, sig.toUpperCase()), "Hex comparison should be case-insensitive");

        // Tampered payload
        assertFalse(HmacVerification.verify(secret, payload + " ", sig));
        // Wrong secret
        assertFalse(HmacVerification.verify("wrong_secret", payload, sig));
        // Tampered signature
        assertFalse(HmacVerification.verify(secret, payload, sig.substring(0, sig.length() - 1) + "0"));
        // Null inputs
        assertFalse(HmacVerification.verify(null, payload, sig));
        assertFalse(HmacVerification.verify(secret, null, sig));
        assertFalse(HmacVerification.verify(secret, payload, null));
    }

    @Test
    void timestampReplayProtection() {
        String secret = "test_webhook_secret";
        String payload = "{\"event\":\"transfer.completed\"}";
        Instant now = Instant.parse("2026-11-22T10:00:00Z");
        long timestamp = now.getEpochSecond();

        String canonical = timestamp + "." + payload;
        String signature = HmacVerification.computeHex(secret, canonical);

        // Within 300 second tolerance
        assertTrue(HmacVerification.verifyWithTimestamp(secret, timestamp, payload, signature, now, 300));
        assertTrue(HmacVerification.verifyWithTimestamp(secret, timestamp - 299, payload,
                HmacVerification.computeHex(secret, (timestamp - 299) + "." + payload), now, 300));

        // Replay window expired (301 seconds old)
        assertFalse(HmacVerification.verifyWithTimestamp(secret, timestamp - 301, payload,
                HmacVerification.computeHex(secret, (timestamp - 301) + "." + payload), now, 300));

        // Future clock skew beyond tolerance (301 seconds in future)
        assertFalse(HmacVerification.verifyWithTimestamp(secret, timestamp + 301, payload,
                HmacVerification.computeHex(secret, (timestamp + 301) + "." + payload), now, 300));
    }
}
