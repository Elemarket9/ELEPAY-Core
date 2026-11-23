package com.elepay.enterprise.settlement;

/**
 * Escrow status of funds held conditionally in delivery escrow.
 */
public enum EscrowStatus {
    /** Funds held safely in delivery escrow pending delivery confirmation. */
    HELD,

    /** Funds released to merchant available balance following confirmed delivery. */
    RELEASED,

    /** Funds refunded to customer/marketplace following rejection or cancellation. */
    REFUNDED,

    /** Under dispute hold. */
    DISPUTED
}
