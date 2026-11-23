package com.elepay.enterprise.settlement;

/**
 * Settlement schedule and trigger types for enterprise embedded finance.
 */
public enum SettlementScheduleType {
    /** Immediate settlement upon delivery confirmation. */
    IMMEDIATE,

    /** Scheduled daily, weekly, or T+N settlement. */
    SCHEDULED,

    /** End-of-day daily settlement cut-off. */
    DAILY,

    /** End-of-week settlement cut-off. */
    WEEKLY,

    /** Explicitly triggered by marketplace/enterprise instruction. */
    ENTERPRISE_TRIGGERED,

    /** Requested on-demand by sub-merchant. */
    MERCHANT_REQUESTED,

    /** Triggered once accrued merchant balance reaches a minimum monetary threshold. */
    THRESHOLD,

    /** Partial settlement release for multi-item orders where some items are delivered. */
    PARTIAL,

    /** Batch settlement aggregating multiple delivered orders across multiple merchants. */
    BATCH
}
