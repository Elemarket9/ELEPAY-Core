package com.elepay.enterprise.treasury;

import java.time.Instant;
import java.util.UUID;

/**
 * Liquidity forecast representation for enterprise obligations.
 */
public record LiquidityForecast(
        UUID enterpriseId,
        String currency,
        long currentPrefundingPesewas,
        long currentSafeguardedPesewas,
        long pendingReceivablesPesewas,
        long pendingDeliveryEscrowPesewas,
        long projectedObligationsNext24hPesewas,
        long projectedObligationsNext7dDaysPesewas,
        long netLiquidityPositionPesewas,
        ShortfallStatus status,
        Instant forecastGeneratedAt
) {}
