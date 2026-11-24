package com.elepay.rails;

import com.elepay.common.References;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Direct AirtelTigo Money (AT Money Ghana) production payment adapter.
 * Connects directly to AT Money core gateway without third-party aggregators.
 *
 * <p><strong>Fee Structure:</strong> 0.50% capped at 500 pesewas (5.00 GHS).
 * <p><strong>Authentication:</strong> OAuth2 Client Credentials with token refresh and HMAC-SHA256 request signing.
 * <p><strong>Callbacks:</strong> Authoritative HMAC-SHA256 verification in {@code x-airteltigo-signature}.
 */
@Component
@ConditionalOnProperty(name = "elepay.rails.airteltigo.enabled", havingValue = "true")
public class AirtelTigoProductionRail implements PaymentRail {
    private static final Logger log = LoggerFactory.getLogger(AirtelTigoProductionRail.class);

    public record Credentials(String clientId, String clientSecret, String merchantId, String webhookSecret) {
        public boolean isValid() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank()
                    && merchantId != null && !merchantId.isBlank();
        }
    }

    private record Response(int status, String body, String contentType) {}

    private static class CachedToken {
        final String token;
        final Instant expiresAt;

        CachedToken(String token, Instant expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }

        boolean isValid() {
            return token != null && !token.isBlank() && Instant.now().isBefore(expiresAt);
        }
    }

    private final URI base;
    private final Credentials credentials;
    private final ObjectMapper json;
    private final NetworkHealthDetector health;
    private final HttpClient client;
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public AirtelTigoProductionRail(
            ObjectMapper json,
            NetworkHealthDetector health,
            @Value("${elepay.rails.airteltigo.base-url:}") String baseUrl,
            @Value("${elepay.rails.airteltigo.client-id:}") String clientId,
            @Value("${elepay.rails.airteltigo.client-secret:}") String clientSecret,
            @Value("${elepay.rails.airteltigo.merchant-id:}") String merchantId,
            @Value("${elepay.rails.airteltigo.webhook-secret:}") String webhookSecret
    ) {
        this(
                json,
                health,
                (baseUrl == null || baseUrl.isBlank()) ? URI.create("https://api.at.com.gh") : productionBase(baseUrl),
                new Credentials(clientId, clientSecret, merchantId, webhookSecret),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER).build()
        );
    }

    public AirtelTigoProductionRail(
            ObjectMapper json,
            NetworkHealthDetector health,
            URI baseUri,
            Credentials credentials,
            HttpClient client
    ) {
        this.base = baseUri;
        this.credentials = credentials;
        this.json = json;
        this.health = health;
        this.client = client != null ? client : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public static URI productionBase(String url) {
        if (url == null || url.isBlank()) {
            return URI.create("https://api.at.com.gh");
        }
        URI u = URI.create(url);
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null
                || !Set.of("api.at.com.gh", "money.airteltigo.com.gh", "payments.at.com.gh")
                        .contains(u.getHost().toLowerCase(Locale.ROOT))
                || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null
                || (u.getPort() != -1 && u.getPort() != 443)
                || !(u.getPath().isEmpty() || u.getPath().equals("/"))) {
            throw new IllegalStateException("Use an approved AirtelTigo AT Money production HTTPS API origin");
        }
        return u;
    }

    public static long calculateFee(long amount) {
        long fee = (amount * 50L) / 10000L; // 0.50%
        return Math.min(fee, 500L); // Capped at 500 pesewas
    }

    public boolean isConfigured() {
        return credentials != null && credentials.isValid();
    }

    @Override
    public String name() {
        return "airteltigo";
    }

    @Override
    public boolean supports(String direction, String channel, String network) {
        return Set.of("charge", "payout").contains(direction)
                && "momo".equalsIgnoreCase(channel)
                && (network == null || "airteltigo".equalsIgnoreCase(network) || "at".equalsIgnoreCase(network));
    }

    public static UUID providerId(String direction, String reference) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    ("elepay:airteltigo:ghana:v1:" + direction + ":" + reference).getBytes(StandardCharsets.UTF_8));
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            ByteBuffer b = ByteBuffer.wrap(bytes);
            return new UUID(b.getLong(), b.getLong());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String computeHmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    @Override
    public Result charge(Charge request) {
        if (!supports("charge", request.channel(), request.network())) {
            return new Result(Status.FAILED, null, request.amount(), request.currency(), null, null, "Unsupported AirtelTigo destination");
        }
        if (!isConfigured()) {
            return Result.unavailable("AirtelTigo AT Money production credentials not configured for charge");
        }
        return submit("charge", request.reference(), request.amount(), request.currency(), request.msisdn(), request.description());
    }

    @Override
    public Result payout(Payout request) {
        if (!supports("payout", request.type(), request.network())) {
            return new Result(Status.FAILED, null, request.amount(), request.currency(), null, null, "Unsupported AirtelTigo destination");
        }
        if (!isConfigured()) {
            return Result.unavailable("AirtelTigo AT Money production credentials not configured for payout");
        }
        return submit("payout", request.reference(), request.amount(), request.currency(), request.accountNumber(), request.description());
    }

    @Override
    public Result refund(Refund request) {
        return Result.unknown("AirtelTigo direct refund operation requires specific clearing contract");
    }

    private Result submit(String direction, String ref, long amount, String currency, String phone, String description) {
        if (amount <= 0 || !"GHS".equals(currency)) {
            return new Result(Status.FAILED, null, amount, currency, null, null, "Invalid Ghana currency or amount");
        }
        long start = System.currentTimeMillis();
        long fee = calculateFee(amount);
        try {
            String msisdn = References.msisdn(phone);
            String token = getOrRefreshToken(false);
            String railRef = "AT-" + ref;
            String endpoint = direction.equals("charge") ? "/v1/payments/collections" : "/v1/payments/disbursements";

            Map<String, Object> bodyMap = Map.of(
                    "merchantId", credentials.merchantId(),
                    "reference", ref,
                    "railReference", railRef,
                    "amount", BigDecimal.valueOf(amount, 2).toPlainString(),
                    "currency", currency,
                    direction.equals("charge") ? "msisdn" : "recipientMsisdn", msisdn,
                    "description", MtnProductionRail.sanitizeNote(description, ref)
            );
            String payload = json.writeValueAsString(bodyMap);
            String signature = computeHmac(credentials.clientSecret(), payload);

            HttpRequest req = buildRequest(endpoint)
                    .header("Authorization", "Bearer " + token)
                    .header("X-AT-Signature", signature)
                    .header("X-Reference-Id", ref)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();

            Response response = send(req);

            if (response.status() == 401) {
                log.info("AirtelTigo token expired during submit, refreshing token");
                token = getOrRefreshToken(true);
                req = buildRequest(endpoint)
                        .header("Authorization", "Bearer " + token)
                        .header("X-AT-Signature", signature)
                        .header("X-Reference-Id", ref)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                response = send(req);
            }

            long latency = System.currentTimeMillis() - start;
            boolean accepted = (response.status() == 200 || response.status() == 202) && !isNonJsonWafResponse(response);
            health.record(name(), accepted, false, latency);

            if (isNonJsonWafResponse(response)) {
                return Result.unknown("AirtelTigo request rejected by upstream WAF (non-JSON HTML response)");
            }
            if (response.status() == 200 || response.status() == 202) {
                return new Result(Status.PENDING, railRef, amount, currency, fee, null,
                        "AirtelTigo payment initiated; awaiting subscriber confirmation");
            }
            if (response.status() == 400 || response.status() == 422) {
                return new Result(Status.FAILED, null, amount, currency, fee, null,
                        "AirtelTigo rejected request: " + response.body());
            }

            return Result.unknown("AirtelTigo submission outcome requires status verification");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unknown("AirtelTigo request interrupted");
        } catch (HttpTimeoutException e) {
            health.record(name(), false, true, System.currentTimeMillis() - start);
            return Result.unknown("AirtelTigo network timeout during submission; query status");
        } catch (Exception e) {
            health.record(name(), false, false, System.currentTimeMillis() - start);
            return Result.unknown("AirtelTigo network or communication unavailable: " + e.getMessage());
        }
    }

    @Override
    public Result query(String direction, String reference, String railReference) {
        if (!Set.of("charge", "payout").contains(direction)) {
            return Result.unknown("Unsupported AirtelTigo query direction: " + direction);
        }
        if (!isConfigured()) {
            return Result.unavailable("AirtelTigo AT Money production credentials not configured for query");
        }
        long start = System.currentTimeMillis();
        try {
            String token = getOrRefreshToken(false);
            HttpRequest req = buildRequest("/v1/payments/status/" + reference)
                    .header("Authorization", "Bearer " + token)
                    .GET().build();

            Response response = send(req);

            if (response.status() == 401) {
                log.info("AirtelTigo token expired during query, refreshing token");
                token = getOrRefreshToken(true);
                req = buildRequest("/v1/payments/status/" + reference)
                        .header("Authorization", "Bearer " + token)
                        .GET().build();
                response = send(req);
            }

            long latency = System.currentTimeMillis() - start;
            boolean ok = (response.status() == 200 && !isNonJsonWafResponse(response)) || response.status() == 404;
            health.record(name(), ok, false, latency);

            if (isNonJsonWafResponse(response)) {
                return Result.unknown("AirtelTigo status unavailable: upstream returned non-JSON WAF/HTML response");
            }
            if (response.status() == 404) {
                return new Result(Status.NOT_FOUND, null, null, null, null, null, "AirtelTigo transaction not found");
            }
            if (response.status() != 200) {
                return Result.unknown("AirtelTigo status unavailable: HTTP " + response.status());
            }

            JsonNode statusNode;
            try {
                statusNode = json.readTree(response.body());
            } catch (RuntimeException parseError) {
                return Result.unknown("AirtelTigo status unavailable: invalid non-JSON response");
            }
            return parseStatus(statusNode, reference);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unknown("AirtelTigo status request interrupted");
        } catch (HttpTimeoutException e) {
            health.record(name(), false, true, System.currentTimeMillis() - start);
            return Result.unknown("AirtelTigo status request timed out");
        } catch (Exception e) {
            health.record(name(), false, false, System.currentTimeMillis() - start);
            return Result.unknown("AirtelTigo status unavailable: " + e.getMessage());
        }
    }

    public static Result parseStatus(JsonNode node, String reference) {
        String ref = node.path("reference").asString("");
        if (ref.isBlank()) ref = node.path("externalId").asString("");
        if (!reference.equals(ref)) {
            return Result.unknown("AirtelTigo external reference mismatch");
        }

        String status = node.path("status").asString("").toUpperCase(Locale.ROOT);
        if ("PENDING".equals(status) || "PROCESSING".equals(status)) {
            return new Result(Status.PENDING, null, null, null, null, null, "AirtelTigo transaction pending");
        }
        if ("FAILED".equals(status) || "DECLINED".equals(status) || "REJECTED".equals(status)) {
            String reason = node.path("reason").asString("AirtelTigo transaction failed");
            return new Result(Status.FAILED, null, null, null, null, null, reason);
        }
        if (!"SUCCESSFUL".equals(status) && !"COMPLETED".equals(status)) {
            return Result.unknown("Unknown AirtelTigo status: " + status);
        }

        try {
            long amount = new BigDecimal(node.path("amount").asString("")).movePointRight(2).longValueExact();
            String currency = node.path("currency").asString("");
            String financialId = node.path("financialTransactionId").asString("");
            if (financialId.isBlank()) financialId = node.path("transactionId").asString("");
            if (amount <= 0 || !"GHS".equals(currency) || financialId.isBlank()) {
                return Result.unknown("AirtelTigo success lacks financial evidence");
            }
            long fee = calculateFee(amount);
            return new Result(Status.SUCCEEDED, financialId, amount, currency, fee, null, "AirtelTigo confirmed");
        } catch (RuntimeException e) {
            return Result.unknown("AirtelTigo amount evidence malformed");
        }
    }

    private HttpRequest.Builder buildRequest(String path) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "ELEPAY-Core/1.0")
                .header("Accept", "application/json");
    }

    private String getOrRefreshToken(boolean forceRefresh) throws Exception {
        if (!forceRefresh) {
            CachedToken cached = tokenCache.get("token");
            if (cached != null && cached.isValid()) {
                return cached.token;
            }
        }

        Map<String, String> tokenReq = Map.of(
                "grant_type", "client_credentials",
                "client_id", credentials.clientId(),
                "client_secret", credentials.clientSecret()
        );
        Response response = send(buildRequest("/v1/oauth2/token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(tokenReq))).build());

        if (isNonJsonWafResponse(response)) {
            throw new IllegalStateException("AirtelTigo authentication rejected by upstream WAF (non-JSON HTML response)");
        }
        if (response.status() != 200) {
            throw new IllegalStateException("AirtelTigo authentication rejected with HTTP " + response.status());
        }
        JsonNode root;
        try {
            root = json.readTree(response.body());
        } catch (RuntimeException parseError) {
            throw new IllegalStateException("AirtelTigo authentication returned invalid non-JSON response", parseError);
        }
        String token = root.path("access_token").asString("");
        if (token.isBlank()) throw new IllegalStateException("AirtelTigo access token missing");
        long expiresIn = root.path("expires_in").asLong(3600L);
        tokenCache.put("token", new CachedToken(token, Instant.now().plusSeconds(Math.max(60L, expiresIn - 60L))));
        return token;
    }

    private static boolean isNonJsonWafResponse(Response response) {
        if (response == null) return false;
        if (response.contentType() != null && response.contentType().toLowerCase(Locale.ROOT).contains("text/html")) {
            return true;
        }
        String trimmed = response.body() == null ? "" : response.body().trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.startsWith("<") || lower.contains("<html") || lower.contains("request rejected");
    }

    private Response send(HttpRequest request) throws Exception {
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            byte[] body = in.readNBytes(65537);
            if (body.length > 65536) throw new IllegalStateException("AirtelTigo response too large");
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            return new Response(response.statusCode(), new String(body, StandardCharsets.UTF_8), contentType);
        }
    }

    @Override
    public boolean callbacksAreAuthoritative() {
        return true;
    }

    @Override
    public Callback verifyCallback(Map<String, String> headers, String body) {
        String secret = credentials != null && credentials.webhookSecret() != null ? credentials.webhookSecret() : "";
        if (secret.isBlank()) {
            throw new InvalidCallbackException("AirtelTigo webhook secret is not configured");
        }

        try {
            com.elepay.common.CallbackAuthenticity.verify(secret, headers, body, "x-airteltigo-signature");
        } catch (IllegalArgumentException e) {
            throw new InvalidCallbackException(e.getMessage());
        }

        try {
            JsonNode root = json.readTree(body);
            String event = root.path("event").asString("charge.success");
            JsonNode data = root.path("data").isMissingNode() ? root : root.path("data");
            String reference = data.path("reference").asString();
            String status = data.path("status").asString();
            String rawAmount = data.path("amount").asString("").trim();
            if (rawAmount.isEmpty()) {
                throw new InvalidCallbackException("AirtelTigo callback amount is missing");
            }
            long amount = new BigDecimal(rawAmount).movePointRight(2).longValueExact();
            if (amount <= 0) {
                throw new InvalidCallbackException("AirtelTigo callback amount must be positive");
            }
            String currency = data.path("currency").asString("GHS");
            String railRef = data.path("railReference").asString("AT-" + reference);

            Status outcome = switch (status.toUpperCase(Locale.ROOT)) {
                case "SUCCESSFUL", "COMPLETED" -> Status.SUCCEEDED;
                case "FAILED", "DECLINED" -> Status.FAILED;
                default -> Status.PENDING;
            };

            Result result = new Result(outcome, railRef, amount, currency, calculateFee(amount), null, "AirtelTigo callback processed");
            return new Callback("airteltigo:" + reference + ":" + status, event, "charge", reference, railRef, result);
        } catch (InvalidCallbackException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidCallbackException("Failed to parse AirtelTigo callback payload: " + e.getMessage());
        }
    }
}
