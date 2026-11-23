package com.elepay.enterprise.settlement;

/**
 * Delivery status of an order item held in conditional settlement escrow.
 */
public enum DeliveryStatus {
    /** Awaiting courier dispatch or delivery confirmation. */
    PENDING,

    /** Confirmed delivered by customer or marketplace logistics webhook. Eligible for settlement release. */
    DELIVERED,

    /** Missing delivery confirmation: does NOT prove delivery; held in escrow until confirmed or escalated. */
    MISSING_CONFIRMATION,

    /** Rejected by customer upon delivery. */
    REJECTED,

    /** Cancelled prior to delivery. */
    CANCELLED,

    /** Under dispute review. */
    DISPUTED
}
