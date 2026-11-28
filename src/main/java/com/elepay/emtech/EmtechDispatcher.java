package com.elepay.emtech;

import com.elepay.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Sends queued Emtech submissions. HTTP happens outside the claim transaction. */
@Component
public class EmtechDispatcher {
    private static final Logger log = LoggerFactory.getLogger(EmtechDispatcher.class);

    private final EmtechSettings settings;
    private final EmtechOutbox outbox;
    private final EmtechClient client;
    private final ObjectMapper json;
    private final EmtechTransactionMapper transactions;
    private final EmtechDeviceMapper devices;
    private final EmtechDeviceEventMapper events;
    private final EmtechComplaintMapper complaints;

    public EmtechDispatcher(EmtechSettings settings, EmtechOutbox outbox, EmtechClient client, ObjectMapper json) {
        this.settings = settings;
        this.outbox = outbox;
        this.client = client;
        this.json = json;
        this.transactions = new EmtechTransactionMapper(json, settings.emoneyAccountType());
        this.devices = new EmtechDeviceMapper(json);
        this.events = new EmtechDeviceEventMapper(json);
        this.complaints = new EmtechComplaintMapper(json);
    }

    @Scheduled(fixedDelayString = "${elepay.emtech.poll-ms:5000}",
            initialDelayString = "${elepay.emtech.poll-initial-delay-ms:15000}")
    void poll() {
        if (settings.enabled()) dispatchAvailable(50);
    }

    public int dispatchAvailable(int limit) {
        if (!settings.enabled()) return 0;
        var ids = outbox.claim(limit, java.time.Duration.ofMinutes(5));
        int submitted = 0;
        for (var id : ids) {
            var row = outbox.find(id).orElse(null);
            if (row == null) continue;
            if (deliver(row)) submitted++;
        }
        return submitted;
    }

    private boolean deliver(EmtechOutbox.Row row) {
        JsonNode payload;
        try {
            payload = map(row);
        } catch (EmtechValidationException e) {
            outbox.markRejected(row.id(), row.attempts() + 1, 0, "validation", e.getMessage());
            return false;
        } catch (RuntimeException e) {
            outbox.markRejected(row.id(), row.attempts() + 1, 0, "mapping", e.getMessage());
            return false;
        }
        EmtechClient.SendResult result;
        try {
            result = client.submit(row.resource(), payload);
        } catch (ApiException | IllegalStateException | RestClientException e) {
            releaseClaim(row, e);
            return false;
        }
        int attempts = row.attempts() + 1;
        switch (result.outcome()) {
            case SUBMITTED -> {
                outbox.markSubmitted(row.id(), attempts);
                return true;
            }
            case REJECTED -> {
                outbox.markRejected(row.id(), attempts, result.httpStatus(), result.code(), result.message());
                log.warn("Emtech submission {} rejected: HTTP {} {}", row.id(), result.httpStatus(), result.code());
                return false;
            }
            case RETRY -> {
                long delay = settings.retryBaseMs() <= 0 ? 0 : Math.min(30_000, settings.retryBaseMs() * (1L << Math.min(attempts, 8)));
                outbox.markRetry(row.id(), attempts, result.httpStatus(), result.code(), result.message(), delay, settings.maxAttempts());
                log.warn("Emtech submission {} will be retried: HTTP {} {}", row.id(), result.httpStatus(), result.code());
                return false;
            }
        }
        return false;
    }

    /**
     * A guard refusal or a token failure must not leave the row claimed until the lease expires.
     * A blocked address or a token 4xx will not succeed on the next poll, so those are dead-lettered.
     * DNS and token 5xx are retried with backoff.
     */
    private void releaseClaim(EmtechOutbox.Row row, RuntimeException failure) {
        int attempts = row.attempts() + 1;
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        boolean permanent = permanentFailure(failure);
        if (permanent) {
            outbox.markRejected(row.id(), attempts, 0, failure instanceof ApiException api ? api.code() : "token", message);
            log.warn("Emtech submission {} dead-lettered: {}", row.id(), message);
            return;
        }
        long delay = settings.retryBaseMs() <= 0 ? 0 : Math.min(30_000, settings.retryBaseMs() * (1L << Math.min(attempts, 8)));
        outbox.markRetry(row.id(), attempts, 0, "retry", message, delay, settings.maxAttempts());
        log.warn("Emtech submission {} released for retry: {}", row.id(), message);
    }

    static boolean permanentFailure(RuntimeException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (failure instanceof ApiException) {
            return message.contains("blocked address range")
                    || message.contains("must use https")
                    || message.contains("not valid")
                    || message.contains("port 443")
                    || message.contains("public host")
                    || message.contains("not allowlisted")
                    || message.contains("credentials");
        }
        if (message.contains("HTTP ")) {
            int status = httpStatus(message);
            return status >= 400 && status < 500 && status != 429;
        }
        return false;
    }

    private static int httpStatus(String message) {
        int at = message.indexOf("HTTP ");
        if (at < 0) return 0;
        int start = at + 5;
        int end = start;
        while (end < message.length() && Character.isDigit(message.charAt(end))) end++;
        if (end == start) return 0;
        return Integer.parseInt(message.substring(start, end));
    }

    private JsonNode map(EmtechOutbox.Row row) {
        return switch (row.resource()) {
            case "DEVICE" -> devices.toJson(json.readValue(row.fact(), RegulatoryFacts.Device.class));
            case "TRANSACTION" -> transactions.toJson(json.readValue(row.fact(), RegulatoryFacts.Transaction.class));
            case "DEVICE_EVENT" -> events.toJson(json.readValue(row.fact(), RegulatoryFacts.DeviceEvent.class));
            case "COMPLAINT" -> complaints.toJson(json.readValue(row.fact(), RegulatoryFacts.Complaint.class));
            default -> throw new EmtechValidationException("resource", "Unknown resource " + row.resource());
        };
    }
}
