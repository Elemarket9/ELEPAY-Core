package com.elepay.enterprise.treasury;

/**
 * Status of funding sufficiency evaluation.
 */
public enum ShortfallStatus {
    /** Available funding is fully sufficient to cover obligations with surplus buffer. */
    SUFFICIENT,

    /** Available funding is tight (less than 120% of upcoming obligations). */
    WARNING,

    /** Available funding is insufficient to cover immediate obligations. */
    SHORTFALL
}
