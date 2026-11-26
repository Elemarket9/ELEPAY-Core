package com.elepay.ingestion;

import java.util.UUID;

/**
 * Claimed ingestion event passed to an {@link IngestionHandler} for asynchronous processing.
 */
public record IngestionRecord(
        UUID id,
        UUID tenantId,
        String eventType,
        String scope,
        String idempotencyKey,
        String payloadJson,
        int attempts
) {}
