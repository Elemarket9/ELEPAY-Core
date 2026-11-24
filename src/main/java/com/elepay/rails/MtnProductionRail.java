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
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Direct MTN Ghana production collection and disbursement adapter. No aggregator, sandbox
 * endpoints, synthetic results, or local approval shortcuts. Partner status is polled over TLS.
 * Reference: MTN production API portal, https://momoapi.mtn.com/API-collections.
 */
@Component
@ConditionalOnProperty(name = "elepay.rails.mtn.enabled", havingValue = "true")
public class MtnProductionRail implements PaymentRail {
    private static final Logger log = LoggerFactory.getLogger(MtnProductionRail.class);

    public record Credentials(String user, String key, String subscription) {
        public boolean isValid() {
            return user != null && !user.isBlank()
                    && key != null && !key.isBlank()
                    && subscription != null && !subscription.isBlank();
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
    private final Credentials collection;
    private final Credentials disbursement;
    private final ObjectMapper json;
    private final NetworkHealthDetector health;
    private final HttpClient client;
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public MtnProductionRail(ObjectMapper json, NetworkHealthDetector health,
            @Value("${elepay.rails.mtn.base-url:}") String baseUrl,
            @Value("${elepay.rails.mtn.collection-user:}") String collectionUser,
            @Value("${elepay.rails.mtn.collection-key:}") String collectionKey,
            @Value("${elepay.rails.mtn.collection-subscription:}") String collectionSubscription,
            @Value("${elepay.rails.mtn.disbursement-user:}") String disbursementUser,
            @Value("${elepay.rails.mtn.disbursement-key:}") String disbursementKey,
            @Value("${elepay.rails.mtn.disbursement-subscription:}") String disbursementSubscription) {
        this(json, health,
                (baseUrl == null || baseUrl.isBlank()) ? URI.create("https://proxy.momoapi.mtn.com") : productionBase(baseUrl),
                new Credentials(collectionUser, collectionKey, collectionSubscription),
                new Credentials(disbursementUser, disbursementKey, disbursementSubscription),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    public MtnProductionRail(ObjectMapper json, NetworkHealthDetector health,
            URI baseUri, Credentials collection, Credentials disbursement, HttpClient client) {
        this.base = baseUri;
        this.collection = collection;
        this.disbursement = disbursement;
        this.json = json;
        this.health = health;
        this.client = client != null ? client : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public static URI productionBase(String url) {
        if (url == null || url.isBlank()) {
            return URI.create("https://proxy.momoapi.mtn.com");
        }
        URI u = URI.create(url);
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null
                || !Set.of("proxy.momoapi.mtn.com", "momoapi.momo.africa").contains(u.getHost().toLowerCase(Locale.ROOT))
                || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null
                || (u.getPort() != -1 && u.getPort() != 443)
                || !(u.getPath().isEmpty() || u.getPath().equals("/"))) {
            throw new IllegalStateException("Use the MTN-confirmed production HTTPS API origin");
        }
        return u;
    }

    public static long calculateFee(long amount) {
        long fee = (amount * 100L) / 10000L; // 1.00%
        return Math.min(fee, 1000L); // Capped at 1000 pesewas (10 GHS)
    }

    /**
     * Sanitizes {@code payerMessage} / {@code payeeNote} for MTN MoMo's F5 ASM WAF,
     * which rejects requests containing {@code &} or HTML/script metacharacters.
     */
    public static String sanitizeNote(String note, String fallbackRef) {
        String raw = (note == null || note.isBlank())
                ? "ELEPAY " + (fallbackRef != null ? fallbackRef : "")
                : note;
        String cleaned = raw
                .replace("&", " and ")
                .replaceAll("[<>\"'\\\\;\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            cleaned = "ELEPAY";
        }
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(0, 100).trim();
        }
        return cleaned;
    }

    public boolean isConfigured(String direction) {
        Credentials c = credentials(direction);
        return c != null && c.isValid();
    }

    @Override public String name() { return "mtn"; }

    @Override public boolean supports(String direction, String channel, String network) {
        return Set.of("charge", "payout").contains(direction) && "momo".equalsIgnoreCase(channel)
                && "mtn".equalsIgnoreCase(network);
    }

    // Stable provider UUID for every ELEPAY reference: remains identical across crashes and retries.
    public static UUID providerId(String direction, String reference) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    ("elepay:mtn:ghana:v1:" + direction + ":" + reference).getBytes(StandardCharsets.UTF_8));
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            ByteBuffer b = ByteBuffer.wrap(bytes);
            return new UUID(b.getLong(), b.getLong());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Override public Result charge(Charge request) {
        if (!supports("charge", request.channel(), request.network()))
            return new Result(Status.FAILED, null, request.amount(), request.currency(), null, null, "Unsupported destination");
        if (!isConfigured("charge"))
            return Result.unavailable("MTN Mobile Money collection credentials not configured");
        return submit("charge", request.reference(), request.amount(), request.currency(), request.msisdn(), request.description());
    }

    @Override public Result payout(Payout request) {
        if (!supports("payout", request.type(), request.network()))
            return new Result(Status.FAILED, null, request.amount(), request.currency(), null, null, "Unsupported destination");
        if (!isConfigured("payout"))
            return Result.unavailable("MTN Mobile Money disbursement credentials not configured");
        return submit("payout", request.reference(), request.amount(), request.currency(), request.accountNumber(), request.description());
    }

    @Override public Result refund(Refund request) {
        if (request.amount() <= 0 || !"GHS".equals(request.currency())) {
            return new Result(Status.FAILED, null, request.amount(), request.currency(), null, null, "Invalid Ghana currency or refund amount");
        }
        if (request.originalRailReference() == null || request.originalRailReference().isBlank()) {
            return new Result(Status.FAILED, null, request.amount(), request.currency(), null, null, "Original MTN reference required for refund");
        }
        if (!isConfigured("payout")) {
            return Result.unavailable("MTN Mobile Money disbursement credentials not configured for refund");
        }
        long start = System.currentTimeMillis();
        try {
            Credentials c = disbursement;
            String token = getOrRefreshToken("disbursement", c, false);
            String id = providerId("refund", request.reference()).toString();
            String note = sanitizeNote("ELEPAY refund " + request.originalReference(), request.reference());
            Map<String, Object> body = Map.of(
                    "amount", BigDecimal.valueOf(request.amount(), 2).toPlainString(),
                    "currency", request.currency(),
                    "externalId", request.reference(),
                    "payerMessage", note,
                    "payeeNote", note,
                    "referenceIdToRefund", request.originalRailReference());

            HttpRequest req = buildRequest("/disbursement/v2_0/refund")
                    .header("Authorization", "Bearer " + token)
                    .header("Ocp-Apim-Subscription-Key", c.subscription())
                    .header("X-Reference-Id", id)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            Response response = send(req);
            if (response.status() == 401) {
                token = getOrRefreshToken("disbursement", c, true);
                req = buildRequest("/disbursement/v2_0/refund")
                        .header("Authorization", "Bearer " + token)
                        .header("Ocp-Apim-Subscription-Key", c.subscription())
                        .header("X-Reference-Id", id)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
                response = send(req);
            }
            long latency = System.currentTimeMillis() - start;
            boolean accepted = response.status() == 202 && !isNonJsonWafResponse(response);
            health.record(name(), accepted, false, latency);
            if (isNonJsonWafResponse(response)) {
                return Result.unknown("MTN refund rejected by upstream WAF (non-JSON HTML response)");
            }
            if (response.status() == 202) {
                return new Result(Status.PENDING, id, request.amount(), request.currency(), 0L, null,
                        "MTN refund accepted; awaiting verified status");
            }
            if (response.status() == 400 || response.status() == 422) {
                return new Result(Status.FAILED, null, request.amount(), request.currency(), 0L, null,
                        "MTN rejected refund: " + response.body());
            }
            return Result.unknown("MTN refund submission outcome requires status verification");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unknown("MTN refund interrupted");
        } catch (HttpTimeoutException e) {
            health.record(name(), false, true, System.currentTimeMillis() - start);
            return Result.unknown("MTN refund network timeout; verify status with query");
        } catch (Exception e) {
            health.record(name(), false, false, System.currentTimeMillis() - start);
            return Result.unknown("MTN refund communication unavailable: " + e.getMessage());
        }
    }

    private Result submit(String direction, String ref, long amount, String currency, String phone, String description) {
        if (amount <= 0 || !"GHS".equals(currency))
            return new Result(Status.FAILED, null, amount, currency, null, null, "Invalid Ghana currency or amount");
        long start = System.currentTimeMillis();
        long fee = calculateFee(amount);
        try {
            String msisdn = References.msisdn(phone);
            String product = product(direction);
            Credentials c = credentials(direction);
            String token = getOrRefreshToken(product, c, false);
            String id = providerId(direction, ref).toString();
            String note = sanitizeNote(description, ref);
            Map<String, Object> body = Map.of(
                    "amount", BigDecimal.valueOf(amount, 2).toPlainString(),
                    "currency", currency,
                    "externalId", ref,
                    direction.equals("charge") ? "payer" : "payee",
                    Map.of("partyIdType", "MSISDN", "partyId", msisdn),
                    "payerMessage", note,
                    "payeeNote", note);

            HttpRequest req = buildRequest(path(direction))
                    .header("Authorization", "Bearer " + token)
                    .header("Ocp-Apim-Subscription-Key", c.subscription())
                    .header("X-Reference-Id", id)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();

            Response response = send(req);

            // Handle token expiration/refresh (401 Unauthorized)
            if (response.status() == 401) {
                log.info("MTN token expired during submit, refreshing token for {}", product);
                token = getOrRefreshToken(product, c, true);
                req = buildRequest(path(direction))
                        .header("Authorization", "Bearer " + token)
                        .header("Ocp-Apim-Subscription-Key", c.subscription())
                        .header("X-Reference-Id", id)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
                response = send(req);
            }

            long latency = System.currentTimeMillis() - start;
            boolean accepted = response.status() == 202 && !isNonJsonWafResponse(response);
            health.record(name(), accepted, false, latency);

            if (isNonJsonWafResponse(response)) {
                return Result.unknown("MTN request rejected by upstream WAF (non-JSON HTML response)");
            }
            if (response.status() == 202) {
                return new Result(Status.PENDING, id, amount, currency, fee, null,
                        "MTN accepted the request; awaiting verified status");
            }
            if (response.status() == 400 || response.status() == 422) {
                return new Result(Status.FAILED, null, amount, currency, fee, null,
                        "MTN rejected submission: " + response.body());
            }

            // Duplicate, unexpected, or server response: preserve uncertainty and query the same reference.
            return Result.unknown("MTN submission outcome requires status verification");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unknown("MTN request interrupted");
        } catch (HttpTimeoutException e) {
            health.record(name(), false, true, System.currentTimeMillis() - start);
            return Result.unknown("MTN network timeout during submission; verify status with query");
        } catch (Exception e) {
            health.record(name(), false, false, System.currentTimeMillis() - start);
            return Result.unknown("MTN network or communication unavailable: " + e.getMessage());
        }
    }

    @Override public Result query(String direction, String reference, String railReference) {
        if (!Set.of("charge", "payout", "refund").contains(direction)) return Result.unknown("Unsupported MTN query");
        if (!isConfigured("refund".equals(direction) ? "payout" : direction))
            return Result.unavailable("MTN Mobile Money credentials not configured for " + direction);
        long start = System.currentTimeMillis();
        try {
            String product = product(direction);
            Credentials c = credentials(direction);
            String token = getOrRefreshToken(product, c, false);
            String id = providerId(direction, reference).toString();

            HttpRequest req = buildRequest(path(direction) + "/" + id)
                    .header("Authorization", "Bearer " + token)
                    .header("Ocp-Apim-Subscription-Key", c.subscription())
                    .GET().build();

            Response response = send(req);

            if (response.status() == 401) {
                log.info("MTN token expired during query, refreshing token for {}", product);
                token = getOrRefreshToken(product, c, true);
                req = buildRequest(path(direction) + "/" + id)
                        .header("Authorization", "Bearer " + token)
                        .header("Ocp-Apim-Subscription-Key", c.subscription())
                        .GET().build();
                response = send(req);
            }

            long latency = System.currentTimeMillis() - start;
            boolean ok = (response.status() == 200 && !isNonJsonWafResponse(response)) || response.status() == 404;
            health.record(name(), ok, false, latency);

            if (isNonJsonWafResponse(response)) {
                return Result.unknown("MTN status unavailable: upstream returned non-JSON WAF/HTML response");
            }
            if (response.status() == 404) return new Result(Status.NOT_FOUND, null, null, null, null, null, "MTN reference not found");
            if (response.status() != 200) return Result.unknown("MTN status unavailable: HTTP " + response.status());

            JsonNode statusNode;
            try {
                statusNode = json.readTree(response.body());
            } catch (RuntimeException parseError) {
                return Result.unknown("MTN status unavailable: invalid non-JSON response");
            }
            return parseStatus(statusNode, reference);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unknown("MTN status request interrupted");
        } catch (HttpTimeoutException e) {
            health.record(name(), false, true, System.currentTimeMillis() - start);
            return Result.unknown("MTN status request timed out");
        } catch (Exception e) {
            health.record(name(), false, false, System.currentTimeMillis() - start);
            return Result.unknown("MTN status unavailable: " + e.getMessage());
        }
    }

    public static Result parseStatus(JsonNode node, String reference) {
        if (!reference.equals(node.path("externalId").asString("")))
            return Result.unknown("MTN external reference mismatch");
        String status = node.path("status").asString("");
        if ("PENDING".equals(status)) return new Result(Status.PENDING, null, null, null, null, null, "MTN payment pending");
        if ("FAILED".equals(status) || "REJECTED".equals(status)) {
            String reason = node.path("reason").asString("MTN payment failed");
            return new Result(Status.FAILED, null, null, null, null, null, reason);
        }
        if (!"SUCCESSFUL".equals(status)) return Result.unknown("Unknown MTN status: " + status);
        try {
            long amount = new BigDecimal(node.path("amount").asString("")).movePointRight(2).longValueExact();
            String currency = node.path("currency").asString("");
            String financialId = node.path("financialTransactionId").asString("");
            if (amount <= 0 || !"GHS".equals(currency) || financialId.isBlank())
                return Result.unknown("MTN success lacks financial evidence");
            long fee = calculateFee(amount);
            return new Result(Status.SUCCEEDED, financialId, amount, currency, fee, null, "MTN authenticated status confirmed");
        } catch (RuntimeException e) { return Result.unknown("MTN amount evidence malformed"); }
    }

    private String product(String direction) { return direction.equals("charge") ? "collection" : "disbursement"; }
    private Credentials credentials(String direction) { return direction.equals("charge") ? collection : disbursement; }
    private String path(String direction) {
        return switch (direction) {
            case "charge" -> "/collection/v1_0/requesttopay";
            case "refund" -> "/disbursement/v2_0/refund";
            default -> "/disbursement/v1_0/transfer";
        };
    }

    private HttpRequest.Builder buildRequest(String path) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "ELEPAY-Core/1.0")
                .header("Accept", "application/json")
                .header("X-Target-Environment", "mtnghana");
    }

    private String getOrRefreshToken(String product, Credentials c, boolean forceRefresh) throws Exception {
        if (!forceRefresh) {
            CachedToken cached = tokenCache.get(product);
            if (cached != null && cached.isValid()) {
                return cached.token;
            }
        }

        String basic = Base64.getEncoder().encodeToString((c.user() + ":" + c.key()).getBytes(StandardCharsets.UTF_8));
        Response response = send(buildRequest("/" + product + "/token/")
                .header("Authorization", "Basic " + basic).header("Ocp-Apim-Subscription-Key", c.subscription())
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        if (isNonJsonWafResponse(response)) {
            throw new IllegalStateException("MTN authentication rejected by upstream WAF (non-JSON HTML response)");
        }
        if (response.status() != 200) throw new IllegalStateException("MTN authentication rejected with status " + response.status());
        JsonNode root;
        try {
            root = json.readTree(response.body());
        } catch (RuntimeException parseError) {
            throw new IllegalStateException("MTN authentication returned invalid non-JSON response", parseError);
        }
        String token = root.path("access_token").asString("");
        if (token.isBlank()) throw new IllegalStateException("MTN token missing");
        long expiresIn = root.path("expires_in").asLong(3600L);
        // Cache with safety margin of 60 seconds
        Instant expiresAt = Instant.now().plusSeconds(Math.max(60L, expiresIn - 60L));
        tokenCache.put(product, new CachedToken(token, expiresAt));
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
            if (body.length > 65536) throw new IllegalStateException("MTN response too large");
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            return new Response(response.statusCode(), new String(body, StandardCharsets.UTF_8), contentType);
        }
    }

    @Override public boolean callbacksAreAuthoritative() { return false; }

    @Override public Callback verifyCallback(Map<String, String> headers, String body) {
        // No callback authentication contract has been onboarded. Polling alone determines state.
        throw new InvalidCallbackException("MTN callback authentication not configured; use status polling");
    }
}
