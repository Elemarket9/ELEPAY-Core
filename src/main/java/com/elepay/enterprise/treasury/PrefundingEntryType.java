package com.elepay.enterprise.treasury;

/**
 * Types of balance movements recorded in the enterprise prefunding & receivables ledger.
 */
public enum PrefundingEntryType {
    /** Enterprise deposited cash into dedicated prefunding safeguarded account. */
    DEPOSIT,

    /** Funds reserved/allocated for upcoming payout or withdrawal batch. */
    ALLOCATION,

    /** Reserved funds released upon payout settlement or cancellation. */
    RELEASE,

    /** External receivable incurred from an externally collected order. */
    RECEIVABLE_INCURRED,

    /** External receivable settled via partner reconciliation transfer. */
    RECEIVABLE_SETTLED,

    /** Authorized accounting adjustment. */
    ADJUSTMENT
}
