package com.elepay.enterprise.wallet;

/**
 * Operating modes for enterprise wallet integration.
 */
public enum WalletOperatingMode {
    /**
     * Mode A: Enterprise-owned wallet connector (e.g. JumiaPay).
     * The external enterprise maintains the authoritative customer balance and dispatches
     * cryptographically signed payment authorizations. ELEPAY manages multi-merchant splits,
     * escrow allocations, item-level refunds, and settlement payouts.
     */
    MODE_A_ENTERPRISE_OWNED,

    /**
     * Mode B: White-label wallet connector.
     * ELEPAY manages the authoritative ledger, subaccounts, and customer balances, while
     * the enterprise manages brand experience and client interaction.
     */
    MODE_B_WHITE_LABEL,

    /**
     * Mode C: External settlement-only connector.
     * The enterprise collects funds externally (via its own acquiring relationships) and uses
     * ELEPAY exclusively for escrow hold, multi-merchant splitting, and partner payouts.
     */
    MODE_C_SETTLEMENT_ONLY
}
