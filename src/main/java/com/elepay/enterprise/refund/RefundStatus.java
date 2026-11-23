package com.elepay.enterprise.refund;

/**
 * Execution and reconciliation lifecycle states for enterprise refunds.
 */
public enum RefundStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    REJECTED
}
