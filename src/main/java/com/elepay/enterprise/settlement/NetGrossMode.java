package com.elepay.enterprise.settlement;

/**
 * Mode of settlement calculation.
 */
public enum NetGrossMode {
    /** Net settlement: seller receives gross minus commission, platform fee, and refund liabilities. */
    NET,

    /** Gross settlement: seller receives gross amount, fees and liabilities invoiced or debited separately. */
    GROSS
}
