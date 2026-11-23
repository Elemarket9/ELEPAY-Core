package com.elepay.enterprise.connectors;

import com.elepay.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Universal webhook signing and signature verification service using HMAC-SHA256.
 * Enforces timestamp validation to prevent replay attacks (max allowed drift: 300 seconds).
 */
@Component
public class ConnectorWebhookSigner {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final long MAX_ALLOWED_DRIFT_SECONDS = 300L;

    private final Clock clock;

    public ConnectorWebhookSigner(Clock clock) {
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public ConnectorWebhookSigner() {
        this(Clock.systemUTC());
    }

    /**
     * Computes the HMAC-SHA256 signature for payload and timestamp.
     * Signature format: hex(HMAC(secret, timestamp + "." + payload))
     */
    public String sign(String secret, long timestampSeconds, String payload) {
        Objects.requireNonNull(secret, "secret cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");

        try {
            String toSign = timestampSeconds + "." + payload;
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] bytes = mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw ApiException.internal("signing_failure", "Failed to compute HMAC-SHA256 signature: " + e.getMessage());
        }
    }

    /**
     * Verifies the HMAC-SHA256 signature using constant-time comparison.
     */
    public boolean verify(String secret, long timestampSeconds, String payload, String signature) {
        if (secret == null || payload == null || signature == null) {
            return false;
        }

        // Verify timestamp drift
        long nowSeconds = Instant.now(clock).getEpochSecond();
        if (Math.abs(nowSeconds - timestampSeconds) > MAX_ALLOWED_DRIFT_SECONDS) {
            return false;
        }

        try {
            String expectedSignature = sign(secret, timestampSeconds, payload);
            return MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }
}
