package com.elepay.rails;

/**
 * Direct Ghanaian rail status enumeration.
 * Provides explicit status representation across all network adapters.
 */
public enum RailStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    NOT_FOUND,
    RETURNED,
    UNAVAILABLE;

    public PaymentRail.Status toStatus() {
        return PaymentRail.Status.valueOf(name());
    }

    public static RailStatus fromStatus(PaymentRail.Status status) {
        if (status == null) return UNKNOWN;
        return RailStatus.valueOf(status.name());
    }
}
