package com.elepay.payout;

import java.util.UUID;

/** Events the payout module publishes for other modules. */
public final class PayoutEvents {
    private PayoutEvents() {}

    /** A payout reached {@code succeeded}, {@code failed}, {@code rejected} or {@code returned}. */
    public record StatusChanged(UUID payoutId, String ownerType, UUID ownerId, String status) {}
}
