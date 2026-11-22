package com.elepay.enterprise.wallet;

/**
 * State machine representation for an external wallet payment lifecycle.
 */
public enum PaymentExecutionState {
    AUTHORIZED,
    CAPTURED,
    CANCELLED,
    REFUNDED,
    PARTIALLY_REFUNDED,
    FAILED,
    PENDING_RECONCILIATION
}
