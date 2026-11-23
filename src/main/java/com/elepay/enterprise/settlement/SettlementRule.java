package com.elepay.enterprise.settlement;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Configurable settlement rule and schedule for an enterprise or specific sub-merchant.
 */
public record SettlementRule(
        UUID id,
        UUID enterpriseId,
        UUID merchantId,
        SettlementScheduleType scheduleType,
        int settlementTimingDays,
        long minThresholdPesewas,
        boolean deliveryConditionRequired,
        int deliveryTimeoutDays,
        NetGrossMode netGrossMode,
        String currency,
        boolean autoPayoutEnabled,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static SettlementRule defaultRule(UUID enterpriseId, String currency) {
        return new SettlementRule(
                UUID.randomUUID(),
                enterpriseId,
                null,
                SettlementScheduleType.DAILY,
                0, // T+0
                0L,
                true, // Delivery condition strictly required
                14,
                NetGrossMode.NET,
                currency != null ? currency : "GHS",
                true,
                "ACTIVE",
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }
}
