package com.elepay.enterprise.payout;

/**
 * Processing states for an external customer wallet withdrawal.
 */
public enum WithdrawalStatus {
    PENDING,
    SUBMITTED,
    SUCCEEDED,
    FAILED,
    RETURNED
}
