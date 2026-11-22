package com.elepay.enterprise.accounts;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EnterpriseTierLimit(
        UUID id,
        UUID enterpriseId,
        int kycTier,
        long maxBalance,
        long dailyLimit,
        long monthlyLimit,
        long perTransactionLimit,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static EnterpriseTierLimit defaultForTier(UUID enterpriseId, int tier) {
        return switch (tier) {
            case 1 -> new EnterpriseTierLimit(
                    UUID.randomUUID(), enterpriseId, 1,
                    100_000L, 100_000L, 1_000_000L, 100_000L,
                    OffsetDateTime.now(), OffsetDateTime.now()
            );
            case 2 -> new EnterpriseTierLimit(
                    UUID.randomUUID(), enterpriseId, 2,
                    1_000_000L, 500_000L, 5_000_000L, 500_000L,
                    OffsetDateTime.now(), OffsetDateTime.now()
            );
            case 3 -> new EnterpriseTierLimit(
                    UUID.randomUUID(), enterpriseId, 3,
                    5_000_000L, 2_000_000L, 20_000_000L, 2_000_000L,
                    OffsetDateTime.now(), OffsetDateTime.now()
            );
            default -> throw new IllegalArgumentException("Invalid KYC tier: " + tier);
        };
    }
}
