package com.elepay.enterprise.refund;

/**
 * Permitted settlement destination channels for enterprise refunds.
 */
public enum RefundDestination {
    /**
     * Credit back directly to customer's external enterprise wallet (Mode A).
     */
    EXTERNAL_WALLET,

    /**
     * Credit back directly to customer's mobile money wallet (MTN, Telecel, AirtelTigo).
     */
    DOMESTIC_MOMO,

    /**
     * Credit back to customer's commercial bank account.
     */
    BANK_ACCOUNT,

    /**
     * Credit to customer's ELEPAY-managed subaccount on the double-entry ledger (Mode B).
     */
    INTERNAL_WALLET
}
