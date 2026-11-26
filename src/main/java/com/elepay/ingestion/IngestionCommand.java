package com.elepay.ingestion;

import com.elepay.common.ApiException;
import java.util.UUID;

/**
 * Command to enqueue an asynchronous event into the durable ingestion buffer.
 */
public record IngestionCommand(
        UUID tenantId,
        String eventType,
        String scope,
        String idempotencyKey,
        String payloadJson
) {
    public IngestionCommand {
        if (eventType == null || eventType.isBlank()) {
            throw ApiException.badRequest("event_type_required", "Ingestion eventType is required");
        }
        if (scope == null || scope.isBlank()) {
            throw ApiException.badRequest("scope_required", "Ingestion scope is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw ApiException.badRequest("idempotency_key_required",
                    "An Idempotency-Key of 1 to 200 characters is required");
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            throw ApiException.badRequest("payload_required", "Ingestion payloadJson is required");
        }
    }
}
