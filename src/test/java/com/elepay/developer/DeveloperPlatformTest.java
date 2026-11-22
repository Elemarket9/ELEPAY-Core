package com.elepay.developer;

import static org.junit.jupiter.api.Assertions.*;

import com.elepay.common.HmacVerification;
import com.elepay.outbox.Webhooks;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DeveloperPlatformTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void testSnippetGenerationForMultipleLanguagesAndActions() {
        DeveloperService service = new DeveloperService(null, null, null, json, null, null);

        String apiKey = "ek_live_abcdef1234567890abcdef12";
        String baseUrl = "https://api.elepay.com";

        // 1. Node.js
        var nodeSnippet = service.generateSnippet("create_checkout", "nodejs", apiKey, baseUrl);
        assertEquals("nodejs", nodeSnippet.language());
        assertTrue(nodeSnippet.code().contains("ElepayClient"));
        assertTrue(nodeSnippet.code().contains(apiKey));
        assertTrue(nodeSnippet.code().contains(baseUrl));

        // 2. Python
        var pySnippet = service.generateSnippet("verify_payment", "python", apiKey, baseUrl);
        assertEquals("python", pySnippet.language());
        assertTrue(pySnippet.code().contains("verify_payment"));
        assertTrue(pySnippet.code().contains("requests.get"));
        assertTrue(pySnippet.code().contains(apiKey));

        // 3. PHP
        var phpSnippet = service.generateSnippet("create_checkout", "php", apiKey, baseUrl);
        assertEquals("php", phpSnippet.language());
        assertTrue(phpSnippet.code().contains("curl_init"));
        assertTrue(phpSnippet.code().contains("X-Elepay-Api-Key"));

        // 4. Java
        var javaSnippet = service.generateSnippet("create_checkout", "java", apiKey, baseUrl);
        assertEquals("java", javaSnippet.language());
        assertTrue(javaSnippet.code().contains("HttpClient"));
        assertTrue(javaSnippet.code().contains("HttpRequest"));

        // 5. cURL
        var curlSnippet = service.generateSnippet("create_refund", "curl", apiKey, baseUrl);
        assertEquals("curl", curlSnippet.language());
        assertTrue(curlSnippet.code().contains("curl -X POST"));
        assertTrue(curlSnippet.code().contains("X-Elepay-Api-Key"));
    }

    @Test
    void testWebhookSignatureAndVerification() {
        String secret = "whsec_0123456789abcdef0123456789abcdef";
        String payload = "{\"event\":\"checkout.paid\",\"reference\":\"chk_test123\",\"amount\":25000}";
        long timestamp = 1732250000L;

        String signature = Webhooks.sign(secret, timestamp + "." + payload);
        assertNotNull(signature);
        assertFalse(signature.isBlank());

        // Verify with HmacVerification
        boolean isValid = HmacVerification.verify(secret, timestamp + "." + payload, signature);
        assertTrue(isValid, "HMAC signature generated for webhook must match verification");

        // Verify tampering fails
        boolean tampered = HmacVerification.verify(secret, timestamp + "." + payload + "tampered", signature);
        assertFalse(tampered, "Tampered payload must fail HMAC verification");
    }

    @Test
    void testOpenApiSummary() {
        DeveloperService service = new DeveloperService(null, null, null, json, null, null);
        DeveloperController controller = new DeveloperController(service);

        Map<String, Object> summary = controller.openApiSummary();
        assertNotNull(summary);
        assertEquals("ELEPAY Developer Platform", summary.get("platform"));
        assertEquals("1.0.0", summary.get("version"));
        assertEquals("/v3/api-docs", summary.get("openApiDocsUrl"));

        @SuppressWarnings("unchecked")
        List<String> events = (List<String>) summary.get("supportedEvents");
        assertTrue(events.contains("checkout.paid"));
        assertTrue(events.contains("payment.failed"));
        assertTrue(events.contains("payout.completed"));
    }

    @Test
    void testApiKeyValidationConstraints() {
        assertTrue(DeveloperService.ALLOWED_SCOPES.containsAll(List.of("view", "collect", "refund")));
        assertTrue(DeveloperService.ALLOWED_SCOPES.contains("withdraw"));
        assertTrue(DeveloperService.ALLOWED_SCOPES.contains("payouts"));
        assertFalse(DeveloperService.ALLOWED_SCOPES.contains("admin_superuser"));
    }
}
