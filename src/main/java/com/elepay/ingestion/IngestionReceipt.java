package com.elepay.ingestion;

import java.util.UUID;

/**
 * Receipt returned to callers after an ingestion command is durably committed.
 */
public record IngestionReceipt(
        UUID ingestionId,
        String status,
        boolean duplicate
) {}
