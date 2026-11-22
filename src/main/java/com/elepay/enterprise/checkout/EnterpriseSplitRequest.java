package com.elepay.enterprise.checkout;

import com.elepay.ledger.Ledger;
import java.util.List;
import java.util.Objects;

/**
 * Request payload specifying multi-merchant split parameters.
 */
public record EnterpriseSplitRequest(
        long totalPesewas,
        String currency,
        List<SubMerchantItemAllocation> items,
        List<DeliveryAllocation> deliveryAllocations
) {
    public EnterpriseSplitRequest {
        if (totalPesewas <= 0) {
            throw new IllegalArgumentException("totalPesewas must be strictly positive: " + totalPesewas);
        }
        if (currency == null || currency.isBlank()) {
            currency = Ledger.GHS;
        }
        items = items == null ? List.of() : List.copyOf(items);
        deliveryAllocations = deliveryAllocations == null ? List.of() : List.copyOf(deliveryAllocations);
    }

    public static EnterpriseSplitRequest of(long totalPesewas, List<SubMerchantItemAllocation> items) {
        return new EnterpriseSplitRequest(totalPesewas, Ledger.GHS, items, List.of());
    }

    public static EnterpriseSplitRequest of(long totalPesewas, List<SubMerchantItemAllocation> items,
                                            List<DeliveryAllocation> deliveryAllocations) {
        return new EnterpriseSplitRequest(totalPesewas, Ledger.GHS, items, deliveryAllocations);
    }
}
