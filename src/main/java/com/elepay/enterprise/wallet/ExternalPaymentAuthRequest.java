package com.elepay.enterprise.wallet;

import java.time.Instant;
import java.util.Map;

/**
 * Request to authorize a payment through an external wallet connector.
 */
public record ExternalPaymentAuthRequest(
        String idempotencyKey,
        String partnerId,
        WalletOperatingMode operatingMode,
        String transactionReference,
        String externalCustomerId,
        long amountMinorUnits,
        String currency,
        String cryptographicAuthorization,
        Map<String, Object> metadata
) {
    public ExternalPaymentAuthRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
        if (amountMinorUnits <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amountMinorUnits);
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency is required");
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }
}
