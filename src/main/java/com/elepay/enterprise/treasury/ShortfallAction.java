package com.elepay.enterprise.treasury;

/**
 * Action taken when a shortfall evaluation is processed.
 */
public enum ShortfallAction {
    /** No adverse action needed. */
    NONE,

    /** Settlement release or withdrawal payout placed on hold pending prefunding deposit. */
    HOLD_PLACED,

    /** Immediate high-priority treasury shortfall alert dispatched. */
    ALERT_DISPATCHED
}
