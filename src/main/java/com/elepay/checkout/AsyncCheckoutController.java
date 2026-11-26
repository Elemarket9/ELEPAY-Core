package com.elepay.checkout;

import static com.elepay.merchant.MerchantAccess.Permission.COLLECT;
import static com.elepay.merchant.MerchantAccess.Permission.VIEW;

import com.elepay.common.ApiException;
import com.elepay.ingestion.IngestionCommand;
import com.elepay.ingestion.IngestionQueue;
import com.elepay.ingestion.IngestionReceipt;
import com.elepay.ingestion.IngestionStatus;
import com.elepay.merchant.MerchantAccess;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * High-throughput asynchronous and batch checkout ingestion endpoints plus caller polling.
 * Enforces the same merchant authorization and branch scoping rules as {@link Checkouts}.
 */
@RestController
@RequestMapping("/v1/merchants/{merchantId}")
class AsyncCheckoutController {
    static final int MAX_BATCH_SIZE = 100;

    private final MerchantAccess access;
    private final IngestionQueue ingestion;
    private final ObjectMapper json;

    AsyncCheckoutController(MerchantAccess access, IngestionQueue ingestion, ObjectMapper json) {
        this.access = access;
        this.ingestion = ingestion;
        this.json = json;
    }

    /** Internal envelope serialized into {@code ingestion_event.payload} for {@link AsyncCheckoutHandler}. */
    record AsyncCheckoutPayload(UUID merchantId, UUID branchId, String createdBy, Checkouts.CreateCheckout request) {}

    record AsyncCheckoutAccepted(UUID ingestionId, String status, String statusUrl, boolean duplicate) {}

    record BatchCheckoutRequest(List<Checkouts.@Valid CreateCheckout> items) {}

    record BatchCheckoutAccepted(int batchSize, List<AsyncCheckoutAccepted> items) {}

    record IngestionStatusResponse(UUID ingestionId, String eventType, String status, int attempts,
                                   Object result, String lastError, Instant createdAt, Instant completedAt) {}

    @PostMapping("/checkouts/async")
    public ResponseEntity<AsyncCheckoutAccepted> createAsync(
            @PathVariable UUID merchantId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Checkouts.CreateCheckout request) {
        var a = access.require(merchantId, COLLECT);
        String idempotencyKey = requireIdempotencyKey(key);
        validateCheckoutShape(request);
        UUID branch = branchFor(a, request.branchId());

        String payloadJson = json.writeValueAsString(
                new AsyncCheckoutPayload(merchantId, branch, a.actor().subject(), request));
        IngestionReceipt receipt = ingestion.enqueue(new IngestionCommand(
                merchantId,
                AsyncCheckoutHandler.EVENT_TYPE,
                "checkout-async:" + merchantId,
                idempotencyKey,
                payloadJson));

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toAccepted(merchantId, receipt));
    }

    @PostMapping("/checkouts/batch")
    public ResponseEntity<BatchCheckoutAccepted> createBatch(
            @PathVariable UUID merchantId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody BatchCheckoutRequest body) {
        var a = access.require(merchantId, COLLECT);
        String idempotencyKey = requireIdempotencyKey(key);
        if (idempotencyKey.length() > 190) {
            throw ApiException.badRequest("idempotency_key_too_long",
                    "Batch Idempotency-Key must be at most 190 characters");
        }
        if (body == null || body.items() == null || body.items().isEmpty()) {
            throw ApiException.badRequest("empty_batch", "Batch must contain between 1 and " + MAX_BATCH_SIZE + " items");
        }
        if (body.items().size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("batch_too_large", "Batch cannot exceed " + MAX_BATCH_SIZE + " items");
        }

        List<IngestionCommand> commands = new ArrayList<>(body.items().size());
        String scope = "checkout-async:" + merchantId;
        String subject = a.actor().subject();
        for (int idx = 0; idx < body.items().size(); idx++) {
            Checkouts.CreateCheckout item = body.items().get(idx);
            if (item == null) {
                throw ApiException.badRequest("invalid_batch_item", "Batch item at index " + idx + " must not be null");
            }
            validateCheckoutShape(item);
            UUID branch = branchFor(a, item.branchId());
            String payloadJson = json.writeValueAsString(
                    new AsyncCheckoutPayload(merchantId, branch, subject, item));
            commands.add(new IngestionCommand(
                    merchantId,
                    AsyncCheckoutHandler.EVENT_TYPE,
                    scope,
                    idempotencyKey + ":" + idx,
                    payloadJson));
        }

        List<IngestionReceipt> receipts = ingestion.enqueueBatch(commands);
        List<AsyncCheckoutAccepted> accepted = receipts.stream()
                .map(r -> toAccepted(merchantId, r))
                .toList();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new BatchCheckoutAccepted(accepted.size(), accepted));
    }

    @GetMapping("/ingestions/{ingestionId}")
    public IngestionStatusResponse ingestionStatus(
            @PathVariable UUID merchantId,
            @PathVariable UUID ingestionId) {
        access.require(merchantId, VIEW);
        IngestionStatus status = ingestion.findStatus(ingestionId, merchantId)
                .orElseThrow(() -> ApiException.notFound("Ingestion not found"));
        return new IngestionStatusResponse(
                status.ingestionId(),
                status.eventType(),
                status.status(),
                status.attempts(),
                parseResult(status.resultJson()),
                status.lastError(),
                status.createdAt(),
                status.completedAt());
    }

    private static String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("missing_idempotency_key", "Idempotency-Key header is required");
        }
        return key.trim();
    }

    private static void validateCheckoutShape(Checkouts.CreateCheckout request) {
        boolean hasAllocations = request.allocations() != null && !request.allocations().isEmpty();
        if (!hasAllocations && request.amount() == null) {
            throw ApiException.badRequest("amount_required", "Provide an amount or allocations");
        }
        if (hasAllocations && request.amount() != null) {
            throw ApiException.badRequest("ambiguous_amount", "Provide either amount or allocations");
        }
        if (request.payerPhone() != null && !request.payerPhone().isBlank()) {
            com.elepay.common.References.msisdn(request.payerPhone());
        }
    }

    private UUID branchFor(MerchantAccess.Access a, UUID requested) {
        if (a.branchScoped()) {
            if (requested != null && !requested.equals(a.branchId())) {
                throw ApiException.forbidden("Cashiers collect for their own branch");
            }
            return a.branchId();
        }
        if (requested != null && !access.branchBelongs(a.merchantId(), requested)) {
            throw ApiException.badRequest("unknown_branch", "Branch not found");
        }
        return requested;
    }

    private static AsyncCheckoutAccepted toAccepted(UUID merchantId, IngestionReceipt receipt) {
        return new AsyncCheckoutAccepted(
                receipt.ingestionId(),
                receipt.status(),
                "/v1/merchants/" + merchantId + "/ingestions/" + receipt.ingestionId(),
                receipt.duplicate());
    }

    private Object parseResult(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        try {
            return json.readTree(resultJson);
        } catch (RuntimeException e) {
            return resultJson;
        }
    }
}
