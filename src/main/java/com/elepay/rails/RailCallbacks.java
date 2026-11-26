package com.elepay.rails;

import com.elepay.common.ApiException;
import com.elepay.identity.Actor;
import com.elepay.ingestion.IngestionCommand;
import com.elepay.ingestion.IngestionQueue;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Inbound rail notifications and operator recovery. Authenticity is verified by each rail. */
@RestController
class RailCallbacks {
    private final RailTransactions rails;
    @Nullable private final RailRegistry railRegistry;
    @Nullable private final IngestionQueue ingestion;
    @Nullable private final ObjectMapper json;
    private final boolean asyncCallbacks;

    RailCallbacks(RailTransactions rails) {
        this(rails, null, null, null, false);
    }

    @Autowired
    RailCallbacks(RailTransactions rails,
                  @Autowired(required = false) @Nullable RailRegistry railRegistry,
                  @Autowired(required = false) @Nullable IngestionQueue ingestion,
                  @Autowired(required = false) @Nullable ObjectMapper json,
                  @Value("${elepay.ingestion.callbacks.async:false}") boolean asyncCallbacks) {
        this.rails = rails;
        this.railRegistry = railRegistry;
        this.ingestion = ingestion;
        this.json = json;
        this.asyncCallbacks = asyncCallbacks;
    }

    /** Payload serialized into {@code ingestion_event.payload} for {@link AsyncRailCallbackHandler}. */
    record QueuedCallbackPayload(String rail, Map<String, String> headers, String body) {}

    @PostMapping("/v1/rails/{rail}/callbacks")
    public Map<String, String> callback(@PathVariable String rail, @RequestBody String body, HttpServletRequest request) {
        Map<String, String> headers = new TreeMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        if (asyncCallbacks && railRegistry != null && ingestion != null && json != null) {
            PaymentRail paymentRail = railRegistry.get(rail);
            PaymentRail.Callback verified;
            try {
                verified = paymentRail.verifyCallback(headers, body);
            } catch (PaymentRail.InvalidCallbackException e) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_callback", e.getMessage());
            }
            String dedupeKey = verified.dedupeKey();
            if (dedupeKey == null || dedupeKey.isBlank() || dedupeKey.length() > 200) {
                dedupeKey = sha256Hex(rail + ":" + (body == null ? "" : body));
            }
            String payloadJson = json.writeValueAsString(new QueuedCallbackPayload(rail, headers, body));
            ingestion.enqueue(new IngestionCommand(
                    null,
                    AsyncRailCallbackHandler.EVENT_TYPE,
                    "rail-callback:" + rail,
                    dedupeKey,
                    payloadJson));
            return Map.of("status", "accepted", "outcome", "queued");
        }
        var outcome = rails.handleCallback(rail, headers, body);
        return Map.of("status", outcome.duplicate() ? "duplicate" : "accepted", "outcome", outcome.outcome());
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @PostMapping("/v1/ops/rails/recover")
    public Map<String, Integer> recover() {
        Actor.current().requireFinance();
        int count = rails.recover(200, true);
        return Map.of("resolved", count, "recovered", count);
    }

    @GetMapping("/v1/ops/rails/open")
    public List<RailTransactions.RailTx> open() {
        Actor.current().requireOps();
        return rails.open();
    }
}
