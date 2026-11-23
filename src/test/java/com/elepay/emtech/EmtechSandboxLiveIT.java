package com.elepay.emtech;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.common.OutboundUrlGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import tools.jackson.databind.ObjectMapper;

/**
 * Posts one synthetic day to the Emtech sandbox. Runs only when
 * {@code ELEPAY_EMTECH_CLIENT_ID} and {@code ELEPAY_EMTECH_CLIENT_SECRET} are set.
 * Writes counts and status codes to {@code target/emtech-sandbox-live-summary.json}.
 * Secrets are not written.
 */
class EmtechSandboxLiveIT {
    @Test
    @EnabledIf("credentialsPresent")
    void postsTheDayToTheSandbox() throws Exception {
        String run = "live" + Long.toUnsignedString(System.currentTimeMillis(), 36);
        EmtechSettings settings = new EmtechSettings();
        settings.setEnabled(true);
        settings.setBaseUrl(env("ELEPAY_EMTECH_BASE_URL", "https://api.emtech.com/integration"));
        settings.setClientId(System.getenv("ELEPAY_EMTECH_CLIENT_ID"));
        settings.setClientSecret(System.getenv("ELEPAY_EMTECH_CLIENT_SECRET"));
        settings.setMaxRetries(2);
        settings.setRetryBaseMs(500);
        settings.setEmoneyAccountType("BANK");
        ObjectMapper json = new ObjectMapper();
        EmtechSchemaValidator schemas = new EmtechSchemaValidator(json);
        EmtechClient client = new EmtechClient(settings, new OutboundUrlGuard(false, false), json);
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        try {
            for (AccraDay.Item item : AccraDay.day(run, "BANK")) {
                var payload = payload(json, item);
                schemas.validate(EmtechClient.schema(item.resource()), payload);
                EmtechClient.SendResult sent = client.submit(item.resource(), payload);
                counts.merge(item.resource() + ":" + sent.httpStatus(), 1, Integer::sum);
                results.add(Map.of(
                        "resource", item.resource(),
                        "sourceId", item.sourceId(),
                        "httpStatus", sent.httpStatus(),
                        "code", sent.code() == null ? "" : sent.code()));
            }
        } finally {
            client.closeTransport();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("run", run);
            summary.put("baseUrl", settings.baseUrl());
            summary.put("counts", counts);
            summary.put("results", results);
            Path target = Path.of("target");
            Files.createDirectories(target);
            Files.writeString(target.resolve("emtech-sandbox-live-summary.json"), json.writeValueAsString(summary));
        }
        assertThat(results).allSatisfy(row -> assertThat(row.get("httpStatus")).isEqualTo(200));
    }

    public static boolean credentialsPresent() {
        String id = System.getenv("ELEPAY_EMTECH_CLIENT_ID");
        String secret = System.getenv("ELEPAY_EMTECH_CLIENT_SECRET");
        return id != null && !id.isBlank() && secret != null && !secret.isBlank();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static tools.jackson.databind.JsonNode payload(ObjectMapper json, AccraDay.Item item) {
        return switch (item.resource()) {
            case "DEVICE" -> new EmtechDeviceMapper(json).toJson((RegulatoryFacts.Device) item.fact());
            case "TRANSACTION" -> new EmtechTransactionMapper(json, "BANK").toJson((RegulatoryFacts.Transaction) item.fact());
            case "DEVICE_EVENT" -> new EmtechDeviceEventMapper(json).toJson((RegulatoryFacts.DeviceEvent) item.fact());
            case "COMPLAINT" -> new EmtechComplaintMapper(json).toJson((RegulatoryFacts.Complaint) item.fact());
            default -> throw new IllegalStateException(item.resource());
        };
    }
}
