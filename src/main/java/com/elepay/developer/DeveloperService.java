package com.elepay.developer;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.Idempotency;
import com.elepay.merchant.MerchantAccess;
import com.elepay.outbox.Webhooks;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Developer platform service handling API credentials (Live and Test keys),
 * webhook endpoint configuration, real-time delivery logs, manual retry replay,
 * SDK snippet generation, and partner certification.
 */
@Service
public class DeveloperService {
    private static final Logger log = LoggerFactory.getLogger(DeveloperService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final Set<String> ALLOWED_SCOPES = Set.of("view", "collect", "refund", "withdraw", "payouts");
    public static final Set<String> DEFAULT_EVENT_TYPES = Set.of(
            "checkout.paid", "payment.failed", "payout.completed", "refund.created", "mandate.active"
    );

    private final JdbcClient db;
    private final MerchantAccess access;
    private final Audit audit;
    private final ObjectMapper json;
    private final com.elepay.identity.ApiKeys apiKeys;
    private final Webhooks webhooks;

    public DeveloperService(JdbcClient db, MerchantAccess access, Audit audit, ObjectMapper json,
                            com.elepay.identity.ApiKeys apiKeys, Webhooks webhooks) {
        this.db = db;
        this.access = access;
        this.audit = audit;
        this.json = json;
        this.apiKeys = apiKeys;
        this.webhooks = webhooks;

    }

    // --- Records / DTOs ---

    public record ApiKeyCreated(UUID id, String prefix, String apiKey, String environment, String label,
                                List<String> scopes, OffsetDateTime createdAt, String signingSecret) {}

    public record ApiKeyView(UUID id, String prefix, String environment, String label, List<String> scopes,
                             OffsetDateTime createdAt, OffsetDateTime revokedAt, OffsetDateTime lastUsedAt) {}

    public record WebhookEndpointCreated(UUID id, String url, String secret, String environment, String description,
                                         List<String> eventTypes, boolean active, OffsetDateTime createdAt) {}

    public record WebhookEndpointView(UUID id, String url, String secret, String environment, String description,
                                      List<String> eventTypes, boolean active, int failureCount,
                                      OffsetDateTime lastDeliveryAt, OffsetDateTime createdAt) {}

    public record WebhookDeliveryLogView(UUID id, UUID deliveryId, UUID endpointId, UUID eventId, UUID merchantId,
                                         String eventType, String requestUrl, String requestHeaders,
                                         String requestPayload, Integer responseCode, String responseHeaders,
                                         String responseBody, int durationMs, String status, int attemptNumber,
                                         boolean isManualRetry, OffsetDateTime createdAt) {}

    public record ReplayResult(UUID newLogId, boolean success, int statusCode, String responseBody, int durationMs) {}

    public record SdkSnippet(String language, String action, String code) {}

    public record PartnerCertificationView(UUID id, UUID merchantId, String partnerName, String partnerType,
                                           String certificationLevel, int testRunsPassed, String complianceChecklist,
                                           OffsetDateTime certifiedAt, OffsetDateTime expiresAt, String status,
                                           OffsetDateTime createdAt) {}

    // --- 1. API Keys (Live & Test) ---

    @Transactional
    public ApiKeyCreated createApiKey(UUID merchantId, String label, String environment, List<String> scopes, String createdBy) {
        return createApiKey(merchantId, label, environment, scopes, null, null, createdBy);
    }

    @Transactional
    public ApiKeyCreated createApiKey(UUID merchantId, String label, String environment, List<String> scopes,
                                      String keyType, List<String> allowedIps, String createdBy) {
        access.require(merchantId, MerchantAccess.Permission.MANAGE);
        String env = ("test".equalsIgnoreCase(environment)) ? "test" : "live";
        if (scopes != null && !scopes.isEmpty() && !ALLOWED_SCOPES.containsAll(scopes)) {
            throw ApiException.badRequest("invalid_scope", "Valid scopes: " + ALLOWED_SCOPES);
        }

        var key = apiKeys.createForEnvironment(merchantId, label, env, scopes, keyType, allowedIps, createdBy);

        audit.record(createdBy, "developer.api_key_created", "merchant:" + merchantId,
                Map.of("prefix", key.prefix(), "environment", env, "scopes", String.join(",", key.scopes())));

        return new ApiKeyCreated(key.id(), key.prefix(), key.secret(), env, label, key.scopes(), OffsetDateTime.now(), key.signingSecret());
    }

    public List<ApiKeyView> listApiKeys(UUID merchantId, String environment) {
        access.require(merchantId, MerchantAccess.Permission.MANAGE);
        return apiKeys.listWithEnvironment(merchantId, environment).stream().map(k -> new ApiKeyView(k.id(),
                k.prefix(), k.environment(), k.label(), k.scopes(), k.createdAt(), k.revokedAt(), k.lastUsedAt())).toList();
    }

    @Transactional
    public boolean revokeApiKey(UUID merchantId, UUID keyId, String revokedBy) {
        access.require(merchantId, MerchantAccess.Permission.MANAGE);
        if (apiKeys.revoke(merchantId, keyId)) {
            audit.record(revokedBy, "developer.api_key_revoked", "merchant:" + merchantId, Map.of("keyId", keyId.toString()));
            return true;
        }
        return false;
    }

    @Transactional
    public ApiKeyCreated rotateApiKey(UUID merchantId, UUID oldKeyId, String label, String rotatedBy) {
        access.require(merchantId, MerchantAccess.Permission.MANAGE);
        var old = apiKeys.activeKey(merchantId, oldKeyId)
                .orElseThrow(() -> ApiException.notFound("Active API key not found to rotate"));

        revokeApiKey(merchantId, oldKeyId, rotatedBy);
        String newLabel = (label != null && !label.isBlank()) ? label : "Rotated key";
        ApiKeyCreated created = createApiKey(merchantId, newLabel, old.getKey(), old.getValue(), rotatedBy);
        audit.record(rotatedBy, "developer.api_key_rotated", "merchant:" + merchantId,
                Map.of("oldKeyId", oldKeyId.toString(), "newKeyId", created.id().toString()));
        return created;
    }

    // --- 2. Webhook Endpoints ---

    @Transactional
    public WebhookEndpointCreated registerWebhook(UUID merchantId, String url, String environment,
                                                  List<String> eventTypes, String description, String createdBy) {
        access.require(merchantId, MerchantAccess.Permission.MANAGE);
        if (url == null || !url.matches("^https?://.+")) {
            throw ApiException.badRequest("invalid_url", "URL must start with http:// or https://");
        }
        String env = ("test".equalsIgnoreCase(environment)) ? "test" : "live";
        List<String> events = (eventTypes == null || eventTypes.isEmpty())
                ? List.copyOf(DEFAULT_EVENT_TYPES)
                : eventTypes.stream().distinct().toList();

        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String secret = "whsec_" + HexFormat.of().formatHex(bytes);
        UUID id = UUID.randomUUID();

        webhooks.registerEndpoint(id, merchantId, url, secret, env, description, events);

        audit.record(createdBy, "developer.webhook_endpoint_registered", "merchant:" + merchantId,
                Map.of("endpointId", id.toString(), "url", url, "environment", env));

        return new WebhookEndpointCreated(id, url, secret, env, description, events, true, OffsetDateTime.now());
    }

    public List<WebhookEndpointView> listWebhooks(UUID merchantId, String environment) {
        access.require(merchantId, MerchantAccess.Permission.MANAGE);
        return webhooks.endpoints(merchantId, environment).stream().map(e -> new WebhookEndpointView(e.id(), e.url(),
                e.secret(), e.environment(), e.description(), e.eventTypes(), e.active(), e.failureCount(),
                e.lastDeliveryAt(), e.createdAt())).toList();
    }

    @Transactional
    public boolean updateWebhook(UUID merchantId, UUID endpointId, String url, List<String> eventTypes,
                                 Boolean active, String description) {
        access.require(merchantId, MerchantAccess.Permission.MANAGE);
        if (webhooks.endpoint(merchantId, endpointId).isEmpty()) throw ApiException.notFound("Webhook endpoint not found");
        if (url != null && !url.matches("^https?://.+")) {
            throw ApiException.badRequest("invalid_url", "URL must start with http:// or https://");
        }
        return webhooks.updateEndpoint(merchantId, endpointId, url, eventTypes, active, description);
    }

    @Transactional
    public String rotateWebhookSecret(UUID merchantId, UUID endpointId, String updatedBy) {
        access.require(merchantId, MerchantAccess.Permission.MANAGE);
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String newSecret = "whsec_" + HexFormat.of().formatHex(bytes);
        if (!webhooks.setSecret(merchantId, endpointId, newSecret)) throw ApiException.notFound("Webhook endpoint not found");
        audit.record(updatedBy, "developer.webhook_secret_rotated", "merchant:" + merchantId, Map.of("endpointId", endpointId.toString()));
        return newSecret;
    }

    public ReplayResult pingWebhook(UUID merchantId, UUID endpointId) {
        access.require(merchantId, MerchantAccess.Permission.MANAGE);
        var e = webhooks.endpoint(merchantId, endpointId)
                .orElseThrow(() -> ApiException.notFound("Webhook endpoint not found"));
        var ep = Map.of("id", e.id().toString(), "url", e.url(), "secret", e.secret());

        String eventId = UUID.randomUUID().toString();
        String payload = """
                {
                  "id": "%s",
                  "event": "ping",
                  "timestamp": "%s",
                  "data": {
                    "merchantId": "%s",
                    "message": "ELEPAY Webhook connectivity test"
                  }
                }""".formatted(eventId, OffsetDateTime.now(), merchantId);

        return executeAndLogDelivery(merchantId, UUID.fromString(ep.get("id")), UUID.fromString(eventId),
                "ping", ep.get("url"), ep.get("secret"), payload, false);
    }

    // --- 3. Delivery Logs & Manual Retry Replay ---

    public List<WebhookDeliveryLogView> listDeliveryLogs(UUID merchantId, UUID endpointId, String status, int limit) {
        access.require(merchantId, MerchantAccess.Permission.MANAGE);
        int lim = Math.max(1, Math.min(limit, 200));
        StringBuilder sql = new StringBuilder("""
                SELECT id, delivery_id, endpoint_id, event_id, merchant_id, event_type, request_url,
                       request_headers::text, request_payload::text, response_code, response_headers::text,
                       response_body, duration_ms, status, attempt_number, is_manual_retry, created_at
                FROM webhook_delivery_log WHERE merchant_id = :m """);
        if (endpointId != null) sql.append(" AND endpoint_id = :ep ");
        if (status != null && !status.isBlank()) sql.append(" AND status = :st ");
        sql.append(" ORDER BY created_at DESC LIMIT :limit");

        var client = db.sql(sql.toString()).param("m", merchantId).param("limit", lim);
        if (endpointId != null) client = client.param("ep", endpointId);
        if (status != null && !status.isBlank()) client = client.param("st", status.toUpperCase());

        return client.query((rs, i) -> new WebhookDeliveryLogView(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class),
                rs.getObject(5, UUID.class),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getString(9),
                rs.getObject(10, Integer.class),
                rs.getString(11),
                rs.getString(12),
                rs.getInt(13),
                rs.getString(14),
                rs.getInt(15),
                rs.getBoolean(16),
                rs.getObject(17, OffsetDateTime.class)
        )).list();
    }

    public WebhookDeliveryLogView getDeliveryLog(UUID merchantId, UUID logId) {
        access.require(merchantId, MerchantAccess.Permission.MANAGE);
        return db.sql("""
                SELECT id, delivery_id, endpoint_id, event_id, merchant_id, event_type, request_url,
                       request_headers::text, request_payload::text, response_code, response_headers::text,
                       response_body, duration_ms, status, attempt_number, is_manual_retry, created_at
                FROM webhook_delivery_log WHERE id = :id AND merchant_id = :m""")
                .param("id", logId).param("m", merchantId)
                .query((rs, i) -> new WebhookDeliveryLogView(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getString(9),
                        rs.getObject(10, Integer.class),
                        rs.getString(11),
                        rs.getString(12),
                        rs.getInt(13),
                        rs.getString(14),
                        rs.getInt(15),
                        rs.getBoolean(16),
                        rs.getObject(17, OffsetDateTime.class)
                )).optional().orElseThrow(() -> ApiException.notFound("Delivery log entry not found"));
    }

    @Transactional
    public ReplayResult replayDelivery(UUID merchantId, UUID logId, String replayedBy) {
        access.require(merchantId, MerchantAccess.Permission.MANAGE);
        var old = getDeliveryLog(merchantId, logId);
        var ep = webhooks.endpoint(merchantId, old.endpointId()).map(Webhooks.EndpointView::secret)
                .orElseThrow(() -> ApiException.badRequest("endpoint_missing", "Associated webhook endpoint no longer exists"));

        audit.record(replayedBy, "developer.webhook_delivery_replayed", "merchant:" + merchantId,
                Map.of("originalLogId", logId.toString(), "endpointId", old.endpointId().toString()));

        return executeAndLogDelivery(merchantId, old.endpointId(), old.eventId(), old.eventType(),
                old.requestUrl(), ep, old.requestPayload(), true);
    }

    private ReplayResult executeAndLogDelivery(UUID merchantId, UUID endpointId, UUID eventId,
                                               String eventType, String url, String secret,
                                               String payload, boolean isManualRetry) {
        long t = System.currentTimeMillis() / 1000;
        String signature = "t=" + t + ",v1=" + Webhooks.sign(secret, t + "." + payload);
        UUID logId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        String reqHeadersJson = """
                {
                  "Content-Type": "application/json",
                  "Elepay-Event-Id": "%s",
                  "Elepay-Signature": "%s"
                }""".formatted(eventId, signature);

        long start = System.currentTimeMillis();
        Integer statusCode = null;
        String responseBody = null;
        String status = "FAILED";
        String unsafe = webhooks.destinationProblem(url);
        if (unsafe != null) {
            responseBody = "Blocked destination: " + unsafe;
            statusCode = 400;
        } else try {
            statusCode = webhooks.sendSigned(url, eventId, signature, payload);
            responseBody = "Receiver returned HTTP " + statusCode;
            if (statusCode >= 200 && statusCode < 300) {
                status = "DELIVERED";
            }
        } catch (Exception e) {
            log.warn("Webhook delivery failed for endpoint {}", endpointId);
            responseBody = "Webhook transport failed";
            statusCode = (statusCode != null) ? statusCode : 500;
        }
        int durationMs = (int) (System.currentTimeMillis() - start);

        db.sql("""
                INSERT INTO webhook_delivery_log (id, delivery_id, endpoint_id, event_id, merchant_id,
                       event_type, request_url, request_headers, request_payload, response_code,
                       response_body, duration_ms, status, is_manual_retry)
                VALUES (:id, :did, :epid, :evid, :m, :etype, :url, :rheaders::jsonb, :rpayload::jsonb,
                        :code, :rbody, :dur, :status, :retry)""")
                .param("id", logId).param("did", deliveryId).param("epid", endpointId).param("evid", eventId)
                .param("m", merchantId).param("etype", eventType).param("url", url)
                .param("rheaders", reqHeadersJson).param("rpayload", payload)
                .param("code", statusCode).param("rbody", responseBody)
                .param("dur", durationMs).param("status", status).param("retry", isManualRetry).update();

        // Update endpoint stats
        webhooks.recordDeliveryOutcome(endpointId, "DELIVERED".equals(status));

        return new ReplayResult(logId, "DELIVERED".equals(status), statusCode, responseBody, durationMs);
    }

    // --- 4. Client SDK Code Snippet Generators ---

    public SdkSnippet generateSnippet(String action, String language, String apiKey, String baseUrl) {
        // Never copy a submitted credential into generated examples, URLs, logs or JavaScript bundles.
        String key = "ELEPAY_API_KEY";
        String host = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : "https://api.elepay.com";
        String lang = (language != null) ? language.toLowerCase() : "curl";
        String act = (action != null) ? action.toLowerCase() : "create_checkout";

        String code = switch (lang) {
            case "nodejs", "javascript" -> generateNodeSnippet(act, key, host);
            case "python" -> generatePythonSnippet(act, key, host);
            case "php" -> generatePhpSnippet(act, key, host);
            case "java" -> generateJavaSnippet(act, key, host);
            default -> generateCurlSnippet(act, key, host);
        };

        return new SdkSnippet(lang, act, code);
    }

    private String generateCurlSnippet(String action, String key, String host) {
        return switch (action) {
            case "verify_payment" -> """
                    curl -X GET "%s/v1/public/checkouts/${ELEPAY_CHECKOUT_REFERENCE}/status" \\
                      -H "X-Elepay-Api-Key: ${%s}" \\
                      -H "Accept: application/json"
                    """.formatted(host, key);
            case "create_refund" -> """
                    curl -X POST "%s/v1/merchants/YOUR_MERCHANT_ID/checkouts/chk_123/allocations/1/refunds" \\
                      -H "X-Elepay-Api-Key: ${%s}" \\
                      -H "Content-Type: application/json" \\
                      -H "Idempotency-Key: $(uuidgen)" \\
                      -d '{"amount": 5000, "reason": "Customer cancellation"}'
                    """.formatted(host, key);
            case "register_webhook" -> """
                    curl -X POST "%s/v1/developer/webhooks" \\
                      -H "X-Elepay-Api-Key: ${%s}" \\
                      -H "Content-Type: application/json" \\
                      -d '{"url": "https://yourapp.com/webhooks", "environment": "live", "eventTypes": ["checkout.paid"]}'
                    """.formatted(host, key);
            default -> """
                    curl -X POST "%s/v1/checkouts" \\
                      -H "X-Elepay-Api-Key: ${%s}" \\
                      -H "Content-Type: application/json" \\
                      -H "Idempotency-Key: $(uuidgen)" \\
                      -d '{
                        "currency": "GHS",
                        "lines": [
                          {"kind": "sale", "description": "Order #1001", "amount": 15000}
                        ]
                      }'
                    """.formatted(host, key);
        };
    }

    private String generateNodeSnippet(String action, String key, String host) {
        return switch (action) {
            case "verify_payment" -> """
                    import { ElepayClient } from "@elepay/sdk";

                    const elepay = new ElepayClient({
                      apiKey: process.env.%s,
                      baseUrl: "%s"
                    });

                    async function verifyPayment(reference) {
                      const status = await elepay.checkouts.getStatus(reference);
                      console.log("Payment status:", status.status, "Paid at:", status.paidAt);
                      return status;
                    }
                    verifyPayment(process.env.ELEPAY_CHECKOUT_REFERENCE);
                    """.formatted(key, host);
            case "create_refund" -> """
                    import { ElepayClient } from "@elepay/sdk";
                    import { randomUUID } from "node:crypto";

                    const elepay = new ElepayClient({ apiKey: process.env.%s, baseUrl: "%s" });

                    async function refund(merchantId, checkoutRef, lineIndex, amountPesewas) {
                      const refund = await elepay.merchants(merchantId).refunds.create({
                        checkoutReference: checkoutRef,
                        line: lineIndex,
                        amount: amountPesewas,
                        reason: "Customer returned goods",
                        idempotencyKey: randomUUID()
                      });
                      console.log("Refund recorded:", refund);
                    }
                    """.formatted(key, host);
            default -> """
                    import { ElepayClient } from "@elepay/sdk";
                    import { randomUUID } from "node:crypto";

                    const elepay = new ElepayClient({
                      apiKey: process.env.%s,
                      baseUrl: "%s"
                    });

                    async function createCheckout() {
                      const session = await elepay.checkouts.create({
                        currency: "GHS",
                        lines: [
                          { kind: "sale", description: "Online Store Order #1001", amount: 15000 }
                        ],
                        returnUrl: "https://myshop.com/orders/success",
                        cancelUrl: "https://myshop.com/orders/cancel",
                        idempotencyKey: randomUUID()
                      });
                      console.log("Payment URL:", session.payUrl);
                      return session.payUrl;
                    }
                    createCheckout();
                    """.formatted(key, host);
        };
    }

    private String generatePythonSnippet(String action, String key, String host) {
        return switch (action) {
            case "verify_payment" -> """
                    import os
                    import requests

                    ELEPAY_API_KEY = os.environ["%s"]
                    BASE_URL = "%s"

                    def verify_payment(reference: str):
                        url = f"{BASE_URL}/v1/public/checkouts/{reference}/status"
                        headers = {
                            "X-Elepay-Api-Key": ELEPAY_API_KEY,
                            "Accept": "application/json"
                        }
                        response = requests.get(url, headers=headers, timeout=10)
                        response.raise_for_status()
                        return response.json()

                    status = verify_payment(os.environ["ELEPAY_CHECKOUT_REFERENCE"])
                    print("Status:", status.get("status"))
                    """.formatted(key, host);
            case "create_refund" -> """
                    import uuid
                    import os
                    import requests

                    ELEPAY_API_KEY = os.environ["%s"]
                    BASE_URL = "%s"

                    def process_refund(merchant_id: str, checkout_ref: str, line_no: int, amount_pesewas: int):
                        url = f"{BASE_URL}/v1/merchants/{merchant_id}/checkouts/{checkout_ref}/allocations/{line_no}/refunds"
                        headers = {
                            "X-Elepay-Api-Key": ELEPAY_API_KEY,
                            "Content-Type": "application/json",
                            "Idempotency-Key": str(uuid.uuid4())
                        }
                        payload = {"amount": amount_pesewas, "reason": "Customer cancellation"}
                        res = requests.post(url, json=payload, headers=headers, timeout=10)
                        res.raise_for_status()
                        return res.json()
                    """.formatted(key, host);
            default -> """
                    import uuid
                    import os
                    import requests

                    ELEPAY_API_KEY = os.environ["%s"]
                    BASE_URL = "%s"

                    def create_checkout_session(amount_pesewas: int, description: str):
                        url = f"{BASE_URL}/v1/checkouts"
                        headers = {
                            "X-Elepay-Api-Key": ELEPAY_API_KEY,
                            "Content-Type": "application/json",
                            "Idempotency-Key": str(uuid.uuid4())
                        }
                        payload = {
                            "currency": "GHS",
                            "lines": [
                                {"kind": "sale", "description": description, "amount": amount_pesewas}
                            ]
                        }
                        res = requests.post(url, json=payload, headers=headers, timeout=10)
                        res.raise_for_status()
                        return res.json()

                    session = create_checkout_session(15000, "Online Order #1001")
                    print("Pay URL:", session["payUrl"])
                    """.formatted(key, host);
        };
    }

    private String generatePhpSnippet(String action, String key, String host) {
        return switch (action) {
            case "verify_payment" -> """
                    <?php
                    $apiKey = getenv('%s');
                    $baseUrl = '%s';
                    $reference = getenv('ELEPAY_CHECKOUT_REFERENCE');

                    $ch = curl_init("$baseUrl/v1/public/checkouts/$reference/status");
                    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
                    curl_setopt($ch, CURLOPT_HTTPHEADER, [
                        'X-Elepay-Api-Key: ' . $apiKey,
                        'Accept: application/json'
                    ]);
                    $response = curl_exec($ch);
                    curl_close($ch);

                    $data = json_decode($response, true);
                    echo "Status: " . $data['status'];
                    """.formatted(key, host);
            default -> """
                    <?php
                    $apiKey = getenv('%s');
                    $baseUrl = '%s';

                    $payload = json_encode([
                        'currency' => 'GHS',
                        'lines' => [
                            ['kind' => 'sale', 'description' => 'WooCommerce Order #1001', 'amount' => 15000]
                        ]
                    ]);

                    $ch = curl_init("$baseUrl/v1/checkouts");
                    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
                    curl_setopt($ch, CURLOPT_POST, true);
                    curl_setopt($ch, CURLOPT_POSTFIELDS, $payload);
                    curl_setopt($ch, CURLOPT_HTTPHEADER, [
                        'X-Elepay-Api-Key: ' . $apiKey,
                        'Content-Type: application/json',
                        'Idempotency-Key: ' . bin2hex(random_bytes(16))
                    ]);

                    $res = curl_exec($ch);
                    curl_close($ch);
                    $session = json_decode($res, true);
                    header('Location: ' . $session['payUrl']);
                    exit;
                    """.formatted(key, host);
        };
    }

    private String generateJavaSnippet(String action, String key, String host) {
        return """
                package com.example;

                import java.net.URI;
                import java.net.http.HttpClient;
                import java.net.http.HttpRequest;
                import java.net.http.HttpResponse;
                import java.util.UUID;

                public class ElepayClient {
                    private static final String API_KEY = System.getenv("%s");
                    private static final String BASE_URL = "%s";

                    public static void main(String[] args) throws Exception {
                        HttpClient client = HttpClient.newHttpClient();
                        String payload = \"\"\"
                            {
                              "currency": "GHS",
                              "lines": [
                                {"kind": "sale", "description": "Order #1001", "amount": 15000}
                              ]
                            }
                            \"\"\";

                        HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(BASE_URL + "/v1/checkouts"))
                            .header("X-Elepay-Api-Key", API_KEY)
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build();

                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        System.out.println("Checkout Response: " + response.body());
                    }
                }
                """.formatted(key, host);
    }

    // --- 5. Partner Certification ---

    @Transactional
    public PartnerCertificationView submitCertification(UUID merchantId, String partnerName, String partnerType,
                                                        Map<String, Object> checklist, String submittedBy) {
        access.require(merchantId, MerchantAccess.Permission.MANAGE);
        UUID id = UUID.randomUUID();
        String checklistJson = "{}";
        try {
            if (checklist != null) checklistJson = json.writeValueAsString(checklist);
        } catch (Exception ignored) {}

        db.sql("""
                INSERT INTO partner_certification (id, merchant_id, partner_name, partner_type, certification_level,
                       test_runs_passed, compliance_checklist, status)
                VALUES (:id, :m, :name, :type, 'PENDING_REVIEW', 0, :checklist::jsonb, 'IN_PROGRESS')""")
                .param("id", id).param("m", merchantId).param("name", partnerName)
                .param("type", partnerType.toUpperCase()).param("checklist", checklistJson).update();

        audit.record(submittedBy, "developer.partner_certification_submitted", "merchant:" + merchantId,
                Map.of("certId", id.toString(), "partner", partnerName, "type", partnerType));

        return getCertification(merchantId, id);
    }

    public PartnerCertificationView getCertification(UUID merchantId, UUID certId) {
        access.require(merchantId, MerchantAccess.Permission.MANAGE);
        return db.sql("""
                SELECT id, merchant_id, partner_name, partner_type, certification_level, test_runs_passed,
                       compliance_checklist::text, certified_at, expires_at, status, created_at
                FROM partner_certification WHERE id = :id AND merchant_id = :m""")
                .param("id", certId).param("m", merchantId)
                .query((rs, i) -> new PartnerCertificationView(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getInt(6),
                        rs.getString(7),
                        rs.getObject(8, OffsetDateTime.class),
                        rs.getObject(9, OffsetDateTime.class),
                        rs.getString(10),
                        rs.getObject(11, OffsetDateTime.class)
                )).optional().orElseThrow(() -> ApiException.notFound("Certification not found"));
    }

    public List<PartnerCertificationView> listCertifications(UUID merchantId) {
        access.require(merchantId, MerchantAccess.Permission.MANAGE);
        return db.sql("""
                SELECT id, merchant_id, partner_name, partner_type, certification_level, test_runs_passed,
                       compliance_checklist::text, certified_at, expires_at, status, created_at
                FROM partner_certification WHERE merchant_id = :m ORDER BY created_at DESC""")
                .param("m", merchantId)
                .query((rs, i) -> new PartnerCertificationView(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getInt(6),
                        rs.getString(7),
                        rs.getObject(8, OffsetDateTime.class),
                        rs.getObject(9, OffsetDateTime.class),
                        rs.getString(10),
                        rs.getObject(11, OffsetDateTime.class)
                )).list();
    }

    @Transactional
    public PartnerCertificationView runCertificationTests(UUID merchantId, UUID certId, String actorSubject) {
        access.require(merchantId, MerchantAccess.Permission.MANAGE);
        getCertification(merchantId, certId); // Verify ownership before returning any status.
        throw ApiException.unprocessable("certification_evidence_required",
                "Production certification requires verified partner test evidence and an authorized reviewer");
    }
}
