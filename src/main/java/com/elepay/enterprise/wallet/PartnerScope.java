package com.elepay.enterprise.wallet;

/**
 * Enterprise partner authorization scopes for OAuth2 / OIDC delegation.
 */
public enum PartnerScope {
    WALLET_READ,
    WALLET_AUTHORIZE,
    WALLET_CAPTURE,
    WALLET_CANCEL,
    WALLET_REFUND,
    WALLET_WITHDRAW,
    SETTLEMENT_SUBMIT,
    RECONCILIATION_SYNC
}
