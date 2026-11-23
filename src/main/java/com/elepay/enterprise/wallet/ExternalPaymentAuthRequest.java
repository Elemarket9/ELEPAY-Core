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
        if (operatingMode == null) {
            operatingMode = WalletOperatingMode.MODE_A_ENTERPRISE_OWNED;
        }
    }

    public ExternalPaymentAuthRequest(
            String idempotencyKey,
            String partnerId,
            String transactionReference,
            String externalCustomerId,
            long amountMinorUnits,
            String currency,
            String cryptographicAuthorization
    ) {
        this(idempotencyKey, partnerId, WalletOperatingMode.MODE_A_ENTERPRISE_OWNED,
                transactionReference, externalCustomerId, amountMinorUnits, currency,
                cryptographicAuthorization, Map.of());
    }

    public ExternalPaymentAuthRequest(
            String idempotencyKey,
            String transactionReference,
            String externalCustomerId,
            long amountMinorUnits,
            String currency,
            String partnerId,
            String token,
            String cryptographicAuthorization
    ) {
        this(idempotencyKey, partnerId, WalletOperatingMode.MODE_A_ENTERPRISE_OWNED,
                transactionReference, externalCustomerId, amountMinorUnits, currency,
                cryptographicAuthorization, Map.of());
    }
}
