package com.elepay.rails;

import com.elepay.ingestion.IngestionHandler;
import com.elepay.ingestion.IngestionRecord;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link IngestionHandler} for asynchronous {@code RAIL_CALLBACK} events enqueued by
 * {@link RailCallbacks} when {@code elepay.ingestion.callbacks.async=true}.
 */
@Component
class AsyncRailCallbackHandler implements IngestionHandler {
    static final String EVENT_TYPE = "RAIL_CALLBACK";

    private final RailTransactions rails;
    private final ObjectMapper json;

    AsyncRailCallbackHandler(RailTransactions rails, ObjectMapper json) {
        this.rails = rails;
        this.json = json;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public String handle(IngestionRecord record) {
        RailCallbacks.QueuedCallbackPayload payload =
                json.readValue(record.payloadJson(), RailCallbacks.QueuedCallbackPayload.class);
        Map<String, String> headers = payload.headers() != null ? payload.headers() : Map.of();
        RailTransactions.CallbackOutcome outcome = rails.handleCallback(payload.rail(), headers, payload.body());
        return json.writeValueAsString(Map.of(
                "status", outcome.duplicate() ? "duplicate" : "accepted",
                "outcome", outcome.outcome()));
    }
}
