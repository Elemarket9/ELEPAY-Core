package com.elepay.enterprise.treasury;

/**
 * Designated segregated treasury account types for enterprise embedded finance.
 * Enforces Bank of Ghana and Basel III segregation requirements:
 * separate operational funds from safeguarded customer/merchant funds.
 */
public enum TreasuryAccountType {
    /** Enterprise pre-deposited liquidity buffer used to fund early payouts and withdrawals. */
    PREFUNDING,

    /** Segregated safeguarded bank/escrow settlement account. */
    SAFEGUARDED_SETTLEMENT,

    /** Authoritative ledger tracking receivables owed by external enterprise/wallet. */
    EXTERNAL_RECEIVABLES,

    /** Operational commission and platform revenue (strictly segregated). */
    OPERATIONAL_REVENUE
}
