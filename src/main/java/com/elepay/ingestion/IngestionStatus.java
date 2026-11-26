package com.elepay.ingestion;

import java.time.Instant;
import java.util.UUID;

/**
 * Current status snapshot of an ingestion event for caller polling and ops DLQ inspection.
 */
public record IngestionStatus(
        UUID ingestionId,
        UUID tenantId,
        String eventType,
        String status,
        int attempts,
        String resultJson,
        String lastError,
        Instant createdAt,
        Instant completedAt
) {}
