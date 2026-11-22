package com.elepay.enterprise.wallet;

/**
 * Declares the capabilities supported by an external wallet connector.
 */
public record WalletCapabilities(
        boolean supportsExternalBalances,
        boolean supportsCryptoAuthorizations,
        boolean supportsInternalLedger,
        boolean supportsDirectRefund,
        boolean supportsItemLevelRefund,
        boolean supportsWithdrawals,
        boolean supportsMultiMerchantSplit,
        boolean supportsOAuth2Delegation,
        boolean supportsHmacWebhooks,
        boolean supportsBatchSettlement
) {
    public static WalletCapabilities modeADefaults() {
        return new WalletCapabilities(
                true,  // external balances managed by partner
                true,  // cryptographic authorizations
                false, // internal ledger not authoritative for customer
                true,  // direct refund-to-wallet
                true,  // item-level refunds
                true,  // wallet withdrawals to mobile money/bank
                true,  // multi-merchant split allocations
                true,  // OAuth2 delegation
                true,  // HMAC webhooks
                true   // batch settlement
        );
    }

    public static WalletCapabilities modeBDefaults() {
        return new WalletCapabilities(
                false, // external balances
                false, // crypto auth (uses internal auth token)
                true,  // internal ledger authoritative
                true,  // direct refund
                true,  // item-level refunds
                true,  // withdrawals
                true,  // multi-merchant split
                true,  // OAuth2 delegation
                true,  // HMAC webhooks
                true   // batch settlement
        );
    }

    public static WalletCapabilities modeCDefaults() {
        return new WalletCapabilities(
                false, // external balances
                false, // crypto auth
                false, // internal ledger for customer
                false, // direct wallet refund (settled via merchant reversal)
                true,  // item-level refund allocation
                false, // customer wallet withdrawals
                true,  // multi-merchant split
                true,  // OAuth2 delegation
                true,  // HMAC webhooks
                true   // batch settlement
        );
    }
}
