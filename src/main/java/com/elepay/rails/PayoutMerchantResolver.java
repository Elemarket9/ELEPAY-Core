package com.elepay.rails;

import java.util.Optional;
import java.util.UUID;

/**
 * SPI allowing {@link RailTransactions} to resolve the merchant owner of a payout
 * without introducing a compile-time or Spring Modulith dependency from {@code rails}
 * to {@code payout}. Implemented by {@code com.elepay.payout.Payouts}.
 */
@FunctionalInterface
public interface PayoutMerchantResolver {
    Optional<UUID> findMerchantOwner(UUID payoutId);
}
