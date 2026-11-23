package com.elepay.enterprise.wallet;

/**
 * Request to withdraw external/white-label wallet balances to domestic payment rails (e.g. MTN MoMo).
 */
public record ExternalWalletWithdrawalRequest(
        String idempotencyKey,
        String partnerId,
        String externalCustomerId,
        long amountMinorUnits,
        String currency,
        String destinationRail,
        String destinationPhoneOrAccount,
        String kycTier
) {
    public ExternalWalletWithdrawalRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
        if (partnerId == null || partnerId.isBlank()) {
            throw new IllegalArgumentException("Partner id is required");
        }
        if (amountMinorUnits <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be positive: " + amountMinorUnits);
        }
        if (destinationRail == null || destinationRail.isBlank()) {
            throw new IllegalArgumentException("Destination rail is required");
        }
        if (destinationPhoneOrAccount == null || destinationPhoneOrAccount.isBlank()) {
            throw new IllegalArgumentException("Destination phone or account is required");
        }
        if (kycTier == null || kycTier.isBlank()) {
            kycTier = "TIER_1";
        }
    }

}
