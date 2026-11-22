package com.elepay.enterprise.wallet;

/**
 * Request payload and headers for HMAC webhook verification.
 */
public record WebhookSignatureVerificationRequest(
        String partnerId,
        String rawBody,
        String signatureHeader,
        String timestampHeader,
        String nonceHeader,
        String keyVersionHeader
) {
    public WebhookSignatureVerificationRequest {
        if (rawBody == null) rawBody = "";
        if (signatureHeader == null) signatureHeader = "";
        if (timestampHeader == null) timestampHeader = "";
        if (nonceHeader == null) nonceHeader = "";
    }
}
