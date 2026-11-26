package com.elepay.checkout;

import com.elepay.common.ApiException;
import com.elepay.common.References;
import com.elepay.ingestion.IngestionHandler;
import com.elepay.ingestion.IngestionRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link IngestionHandler} for asynchronous {@code CHECKOUT_CREATE} events enqueued by
 * {@link AsyncCheckoutController}. Delegates to {@link CheckoutService#create} inside a
 * transaction and returns the serialized {@link Checkouts.CheckoutCreated} summary JSON.
 */
@Component
class AsyncCheckoutHandler implements IngestionHandler {
    static final String EVENT_TYPE = "CHECKOUT_CREATE";

    private final CheckoutService checkouts;
    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final String publicBaseUrl;

    AsyncCheckoutHandler(CheckoutService checkouts,
                         JdbcClient db,
                         PlatformTransactionManager tm,
                         ObjectMapper json,
                         @Value("${elepay.public-base-url}") String publicBaseUrl) {
        this.checkouts = checkouts;
        this.db = db;
        this.tx = new TransactionTemplate(tm);
        this.json = json;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public String handle(IngestionRecord record) {
        JsonNode root = json.readTree(record.payloadJson());
        UUID merchantId;
        UUID branchId;
        String createdBy;
        Checkouts.CreateCheckout request;

        if (root != null && root.has("request") && root.get("request").isObject()) {
            var envelope = json.readValue(record.payloadJson(), AsyncCheckoutController.AsyncCheckoutPayload.class);
            merchantId = envelope.merchantId() != null ? envelope.merchantId() : record.tenantId();
            branchId = envelope.branchId();
            createdBy = (envelope.createdBy() != null && !envelope.createdBy().isBlank())
                    ? envelope.createdBy() : "async-ingestion";
            request = envelope.request();
        } else {
            request = json.readValue(record.payloadJson(), Checkouts.CreateCheckout.class);
            merchantId = record.tenantId();
            branchId = request.branchId();
            createdBy = "async-ingestion";
        }

        if (merchantId == null || request == null) {
            throw ApiException.badRequest("invalid_checkout_payload", "Checkout merchantId and request are required");
        }

        List<CheckoutService.AllocationRequest> allocations;
        if (request.allocations() == null || request.allocations().isEmpty()) {
            if (request.amount() == null) {
                throw ApiException.badRequest("amount_required", "Provide an amount or allocations");
            }
            allocations = List.of(new CheckoutService.AllocationRequest(merchantId, "sale", request.amount(), null));
        } else {
            if (request.amount() != null) {
                throw ApiException.badRequest("ambiguous_amount", "Provide either amount or allocations");
            }
            allocations = request.allocations().stream()
                    .map(x -> new CheckoutService.AllocationRequest(
                            x.recipient(merchantId, db), x.kind(), x.amount(), x.description(), x.category(),
                            x.reserveBps(), x.releaseCondition()))
                    .toList();
        }

        Checkouts.CheckoutCreated created = tx.execute(s -> {
            var c = checkouts.create(
                    merchantId,
                    branchId,
                    null,
                    allocations,
                    request.description(),
                    request.expiresInMinutes() == null ? 60 : request.expiresInMinutes(),
                    createdBy,
                    request.orderReference());
            if (request.payerPhone() != null && !request.payerPhone().isBlank()) {
                db.sql("UPDATE checkout SET payer_phone = :p WHERE reference = :r")
                        .param("p", References.msisdn(request.payerPhone()))
                        .param("r", c.reference())
                        .update();
            }
            return new Checkouts.CheckoutCreated(
                    c.reference(),
                    request.orderReference(),
                    c.status(),
                    c.subtotal(),
                    publicBaseUrl + "/pay/" + c.reference(),
                    c.expiresAt());
        });

        return json.writeValueAsString(created);
    }
}
