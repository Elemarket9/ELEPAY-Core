package com.elepay.enterprise.payout;

import java.util.Map;
import java.util.UUID;

/**
 * Customer withdrawal request from an enterprise-owned wallet (e.g. a refund credited there) to domestic payout rails (MTN MoMo, Bank).
 */
public record EnterpriseWithdrawalRequest(
        String idempotencyKey,
        UUID enterpriseId,
        String partnerId,
        String externalCustomerId,
        String delegatedTokenId,
        String customerName,
        String nationalId,
        int kycTier,
        long amountPesewas,
        String currency,
        WithdrawalDestinationType destinationType,
        String destinationNetwork,
        String destinationAccount,
        Map<String, String> metadata
) {
    public EnterpriseWithdrawalRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (enterpriseId == null) {
            throw new IllegalArgumentException("enterpriseId is required");
        }
        if (externalCustomerId == null || externalCustomerId.isBlank()) {
            throw new IllegalArgumentException("externalCustomerId is required");
        }
        if (customerName == null || customerName.isBlank()) {
            throw new IllegalArgumentException("customerName is required");
        }
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("amountPesewas must be positive: " + amountPesewas);
        }
        if (currency == null || currency.isBlank()) {
            currency = "GHS";
        }
        if (destinationType == null) {
            destinationType = WithdrawalDestinationType.MOMO;
        }
        if (destinationAccount == null || destinationAccount.isBlank()) {
            throw new IllegalArgumentException("destinationAccount is required");
        }
        if (kycTier < 1 || kycTier > 3) {
            kycTier = 1;
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
