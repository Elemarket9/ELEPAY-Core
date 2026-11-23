package com.elepay.enterprise.checkout;

import java.util.UUID;

/**
 * Specification of a delivery fulfillment leg payable to a logistics partner.
 * All amounts are represented in integer minor units (pesewas).
 */
public record DeliveryAllocation(
        UUID deliveryPartnerId,
        String deliveryPartnerName,
        long deliveryFeePesewas,
        Integer marketplaceCommissionBps,
        Integer platformFeeBps,
        String description
) {
    public DeliveryAllocation {
        if (deliveryPartnerId == null) {
            throw new IllegalArgumentException("deliveryPartnerId cannot be null");
        }
        if (deliveryFeePesewas < 0) {
            throw new IllegalArgumentException("deliveryFeePesewas cannot be negative: " + deliveryFeePesewas);
        }
        if (marketplaceCommissionBps != null && (marketplaceCommissionBps < 0 || marketplaceCommissionBps > 10000)) {
            throw new IllegalArgumentException("marketplaceCommissionBps must be between 0 and 10000: " + marketplaceCommissionBps);
        }
        if (platformFeeBps != null && (platformFeeBps < 0 || platformFeeBps > 10000)) {
            throw new IllegalArgumentException("platformFeeBps must be between 0 and 10000: " + platformFeeBps);
        }
    }

    public DeliveryAllocation(UUID deliveryPartnerId, String deliveryPartnerName, long deliveryFeePesewas,
                              Integer marketplaceCommissionBps, Integer platformFeeBps) {
        this(deliveryPartnerId, deliveryPartnerName, deliveryFeePesewas, marketplaceCommissionBps, platformFeeBps, "Delivery fulfillment");
    }

    public static DeliveryAllocation of(UUID deliveryPartnerId, String deliveryPartnerName, long deliveryFeePesewas) {
        return new DeliveryAllocation(deliveryPartnerId, deliveryPartnerName, deliveryFeePesewas, 0, 0, "Delivery fulfillment");
    }
}
