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
import tools.jackson.databind.json.JsonMapper;

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
        @Override public String toString() { return "Credentials[redacted]"; }
        public boolean isValid() {
            return user != null && !user.isBlank()
                    && key != null && !key.isBlank()
                    && subscription != null && !subscription.isBlank();
        }
    }

    record Response(int status, String body, String contentType) {}

    /** Fixed diagnostic for upstream HTML authentication blocks, never raw response content. */
    private static final class AuthenticationWafException extends IllegalStateException {
        AuthenticationWafException() { super("MTN authentication rejected by upstream WAF"); }
    }

    /** MTN refused to issue an access token (bad credentials, disabled user, or an error status). */
    private static final class TokenRejectedException extends IllegalStateException {
        TokenRejectedException(String message) { super(message); }
    }

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
        long fee = amount / 100L; // 1.00%, without overflowing minor units
        return Math.min(fee, 1000L); // Capped at 1000 pesewas (10 GHS)
    }

    /**
     * Sanitizes {@code payerMessage} / {@code payeeNote} for MTN MoMo's F5 ASM WAF. The WAF blocks the
     * whole request (HTML "Request Rejected" page) when a note contains any of
     * {@code ! # $ % & ' ( ) * ; ? [ ] ^ ` { | } ~ < > " \}, so ordinary text such as "Order #12" or
     * "Refund (item 2)" would otherwise fail. Verified against the MTN sandbox WAF: letters (including
     * accented and Ghanaian letters), digits, space and {@code + , - . / : @ _} pass. Everything else
     * is replaced with a space (an allow-list, so new WAF-sensitive characters cannot slip through).
     */
    public static String sanitizeNote(String note, String fallbackRef) {
        String raw = (note == null || note.isBlank())
                ? "ELEPAY " + (fallbackRef != null ? fallbackRef : "")
                : note;
        String cleaned = raw
                .replace("&", " and ")
                .replace("%", " percent ")
                .replace("#", " no ")
                .replaceAll("[^\\p{L}\\p{N} +,\\-./:@_]", " ")
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
        if ("refund".equals(direction)) {
            // Refunds go back through the rail that took the payment; callers (e.g. checkout refunds)
            // do not repeat the network, so accept a null network for MoMo refunds.
            return "momo".equalsIgnoreCase(channel) && (network == null || "mtn".equalsIgnoreCase(network));
        }
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
        Credentials c = disbursement;
        String token;
        try {
            token = getOrRefreshToken("disbursement", c, false);
        } catch (Exception e) {
            // Nothing has been sent to MTN yet: report UNAVAILABLE so the refund fails cleanly instead of
            // sitting in UNKNOWN recovery for hours.
            return preSubmissionFailure(e, start, "refund");
        }
        try {
            String id = providerId("refund", request.reference()).toString();
            String note = sanitizeNote("ELEPAY refund " + request.originalReference(), request.reference());
            Map<String, Object> body = Map.of(
                    "amount", BigDecimal.valueOf(request.amount(), 2).toPlainString(),
                    "currency", request.currency(),
                    "externalId", request.reference(),
                    "payerMessage", note,
                    "payeeNote", note,
                    "referenceIdToRefund", referenceIdToRefund(request));

            HttpRequest req = buildRequest("/disbursement/v2_0/refund")
                    .header("Authorization", "Bearer " + token)
                    .header("Ocp-Apim-Subscription-Key", c.subscription())
                    .header("X-Reference-Id", id)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            Response response = send(req);
            if (response.status() == 401) {
                try {
                    token = getOrRefreshToken("disbursement", c, true);
                } catch (Exception e) {
                    return preSubmissionFailure(e, start, "refund"); // the 401 means MTN did not accept it
                }
                req = buildRequest("/disbursement/v2_0/refund")
                        .header("Authorization", "Bearer " + token)
                        .header("Ocp-Apim-Subscription-Key", c.subscription())
                        .header("X-Reference-Id", id)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
                response = send(req);
            }
            long latency = System.currentTimeMillis() - start;
            health.record(name(), mtnAnswered(response), false, latency);
            if (isNonJsonWafResponse(response)) {
                return Result.unknown("MTN refund rejected by upstream WAF (non-JSON HTML response)");
            }
            if (response.status() == 202) {
                return new Result(Status.PENDING, id, request.amount(), request.currency(), 0L, null,
                        "MTN refund accepted; awaiting verified status");
            }
            if (response.status() == 400 || response.status() == 422) {
                return new Result(Status.FAILED, null, request.amount(), request.currency(), 0L, null,
                        "MTN rejected refund: HTTP " + response.status());
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
            return Result.unknown(e instanceof AuthenticationWafException
                    ? "MTN refund authentication rejected by upstream WAF" : "MTN refund communication unavailable");
        }
    }

    /**
     * MTN's {@code referenceIdToRefund} is the X-Reference-Id UUID of the original request-to-pay. ELEPAY
     * stores MTN's {@code financialTransactionId} as the rail reference once a charge succeeds, and MTN
     * rejects that value with HTTP 400. The original X-Reference-Id is derived deterministically from the
     * ELEPAY reference, so recompute it; fall back to the stored rail reference only if it already is a UUID.
     */
    static String referenceIdToRefund(Refund request) {
        if (request.originalReference() != null && !request.originalReference().isBlank()) {
            return providerId("charge", request.originalReference()).toString();
        }
        return request.originalRailReference();
    }

    /** Failure before any payment request reached MTN (token/authentication stage). Never ambiguous. */
    private Result preSubmissionFailure(Exception e, long start, String what) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        health.record(name(), false, e instanceof HttpTimeoutException, System.currentTimeMillis() - start);
        String reason = e instanceof AuthenticationWafException
                ? "MTN authentication rejected by upstream WAF"
                : e instanceof HttpTimeoutException ? "MTN authentication timed out"
                : e instanceof TokenRejectedException ? e.getMessage()
                : "MTN authentication unavailable";
        log.warn("MTN {} not sent: {}", what, reason);
        return Result.unavailable(reason + "; " + what + " was not sent to MTN");
    }

    private Result submit(String direction, String ref, long amount, String currency, String phone, String description) {
        if (amount <= 0 || !"GHS".equals(currency))
            return new Result(Status.FAILED, null, amount, currency, null, null, "Invalid Ghana currency or amount");
        long start = System.currentTimeMillis();
        long fee = calculateFee(amount);
        String msisdn;
        try {
            msisdn = References.msisdn(phone);
        } catch (RuntimeException invalidPhone) {
            // Definitively rejected before anything is sent: never leave it UNKNOWN for recovery.
            return new Result(Status.FAILED, null, amount, currency, null, null, "Invalid Ghana MTN phone number");
        }
        String product = product(direction);
        Credentials c = credentials(direction);
        String token;
        try {
            token = getOrRefreshToken(product, c, false);
        } catch (Exception e) {
            return preSubmissionFailure(e, start, direction);
        }
        try {
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
                try {
                    token = getOrRefreshToken(product, c, true);
                } catch (Exception e) {
                    return preSubmissionFailure(e, start, direction); // the 401 means MTN did not accept it
                }
                req = buildRequest(path(direction))
                        .header("Authorization", "Bearer " + token)
                        .header("Ocp-Apim-Subscription-Key", c.subscription())
                        .header("X-Reference-Id", id)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
                response = send(req);
            }

            long latency = System.currentTimeMillis() - start;
            health.record(name(), mtnAnswered(response), false, latency);

            if (isNonJsonWafResponse(response)) {
                return Result.unknown("MTN request rejected by upstream WAF (non-JSON HTML response)");
            }
            if (response.status() == 202) {
                return new Result(Status.PENDING, id, amount, currency, fee, null,
                        "MTN accepted the request; awaiting verified status");
            }
            if (response.status() == 400 || response.status() == 422) {
                return new Result(Status.FAILED, null, amount, currency, fee, null,
                        "MTN rejected submission: HTTP " + response.status());
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
            return Result.unknown(e instanceof AuthenticationWafException
                    ? "MTN authentication rejected by upstream WAF" : "MTN network or communication unavailable");
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

            HttpRequest req = buildRequest(statusPath(direction) + "/" + id)
                    .header("Authorization", "Bearer " + token)
                    .header("Ocp-Apim-Subscription-Key", c.subscription())
                    .GET().build();

            Response response = send(req);

            if (response.status() == 401) {
                log.info("MTN token expired during query, refreshing token for {}", product);
                token = getOrRefreshToken(product, c, true);
                req = buildRequest(statusPath(direction) + "/" + id)
                        .header("Authorization", "Bearer " + token)
                        .header("Ocp-Apim-Subscription-Key", c.subscription())
                        .GET().build();
                response = send(req);
            }

            long latency = System.currentTimeMillis() - start;
            health.record(name(), mtnAnswered(response), false, latency);

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
            return Result.unknown(e instanceof AuthenticationWafException
                    ? "MTN status authentication rejected by upstream WAF" : "MTN status unavailable");
        }
    }

    public static Result parseStatus(JsonNode node, String reference) {
        if (!reference.equals(node.path("externalId").asString("")))
            return Result.unknown("MTN external reference mismatch");
        String status = node.path("status").asString("");
        // CREATED: MTN registered the request but the customer prompt is not yet live. Not terminal.
        if ("PENDING".equals(status) || "CREATED".equals(status)) {
            return new Result(Status.PENDING, null, null, null, null, null, "MTN payment pending");
        }
        if ("FAILED".equals(status) || "REJECTED".equals(status)) {
            return new Result(Status.FAILED, null, null, null, null, null, failureMessage(node.path("reason")));
        }
        if (!"SUCCESSFUL".equals(status)) return Result.unknown("Unknown MTN status");
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

    /**
     * Keeps MTN's failure reason so customers and operators can tell a decline from an expired prompt
     * or a float shortfall. Only known codes are echoed; anything else is reported generically.
     */
    static String failureMessage(JsonNode reasonNode) {
        String code = reasonNode.isObject() ? reasonNode.path("code").asString("") : reasonNode.asString("");
        code = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        String text = switch (code) {
            case "APPROVAL_REJECTED" -> "customer declined the MoMo prompt";
            case "EXPIRED" -> "customer did not approve the MoMo prompt in time (expired)";
            case "PAYER_NOT_FOUND" -> "MoMo wallet not found for this number";
            case "PAYEE_NOT_FOUND" -> "recipient MoMo wallet not found for this number";
            case "NOT_ENOUGH_FUNDS" -> "not enough funds in the paying wallet";
            case "PAYER_LIMIT_REACHED" -> "paying wallet limit reached";
            case "PAYEE_NOT_ALLOWED_TO_RECEIVE" -> "recipient wallet is not allowed to receive";
            case "NOT_ALLOWED" -> "MTN did not allow this transaction";
            case "INTERNAL_PROCESSING_ERROR" -> "MTN internal processing error";
            case "SERVICE_UNAVAILABLE" -> "MTN service unavailable";
            case "COULD_NOT_PERFORM_TRANSACTION" -> "MTN could not perform the transaction";
            case "TRANSACTION_CANCELED" -> "transaction cancelled";
            default -> null;
        };
        return text == null ? "MTN payment failed" : "MTN payment failed: " + text + " (" + code + ")";
    }

    private String statusPath(String direction) {
        // MTN serves refund status only on v1_0; GET /disbursement/v2_0/refund/{id} is a 404.
        return "refund".equals(direction) ? "/disbursement/v1_0/refund" : path(direction);
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
            throw new AuthenticationWafException();
        }
        if (response.status() != 200) {
            // MTN answers wrong credentials with HTTP 500 {"error":"login_failed"}, not 401.
            String error = "";
            try { error = json.readTree(response.body()).path("error").asString(""); } catch (RuntimeException ignored) {}
            throw new TokenRejectedException("login_failed".equals(error)
                    ? "MTN rejected the API user or API key (login_failed)"
                    : "MTN authentication rejected with status " + response.status());
        }
        JsonNode root;
        try {
            root = json.readTree(response.body());
        } catch (RuntimeException parseError) {
            throw new IllegalStateException("MTN authentication returned invalid non-JSON response", parseError);
        }
        String token = root.path("access_token").asString("");
        if (token.isBlank()) throw new IllegalStateException("MTN token missing");
        long expiresIn = root.path("expires_in").asLong(0L);
        if (expiresIn <= 0) throw new IllegalStateException("MTN token expiry missing or invalid");
        // Never extend a provider token's lifetime; short-lived tokens are not cached.
        Instant expiresAt = Instant.now().plusSeconds(Math.max(0L, Math.min(expiresIn, 86400L) - 60L));
        tokenCache.put(product, new CachedToken(token, expiresAt));
        return token;
    }

    /**
     * Whether MTN itself answered, for the rail's circuit breaker. The breaker must measure whether MTN is
     * reachable, not whether it said yes: a refusal such as 400 bad request, 404 unknown reference,
     * 409 "already have this X-Reference-Id" (expected when a lost request is safely resent) or a 500 with an
     * MTN JSON error code such as NOT_ALLOWED is a working MTN. Counting those as failures let two refused
     * ops balance lookups, or a few idempotent resends during recovery, open the circuit and stop all MTN
     * payments. Only real outages count: gateway errors (502/503/504), throttling (429), a WAF/HTML page,
     * or a 5xx without an MTN JSON body. Timeouts and connection errors are recorded at their catch sites.
     */
    static boolean mtnAnswered(Response response) {
        if (response == null || isNonJsonWafResponse(response)) return false;
        int status = response.status();
        if (status == 429 || status == 502 || status == 503 || status == 504) return false;
        if (status < 500) return true;
        String body = response.body() == null ? "" : response.body().trim();
        if (!body.startsWith("{")) return false;
        try {
            JsonNode node = JsonMapper.shared().readTree(body);
            return node.hasNonNull("code") || node.hasNonNull("error");
        } catch (RuntimeException notJson) {
            return false;
        }
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

    /** MTN MoMo OpenAPI Account Balance inquiry result (UAT Sheet 5: Balance). */
    public record AccountBalance(boolean available, long availableBalancePesewas, String currency, String message) {}

    /** MTN MoMo OpenAPI Account Holder active check result (UAT Sheet 4: ValidateAccount). */
    public record AccountValidation(boolean checked, boolean active, String msisdn, String message) {}

    /**
     * Queries the live MTN Collection or Disbursement account balance (GET /{product}/v1_0/account/balance).
     * Required by MTN MoMo OpenAPI UAT Sheet 5 and used for pre-disbursement float monitoring.
     */
    public AccountBalance getAccountBalance(String direction) {
        if (!Set.of("charge", "payout").contains(direction)) {
            return new AccountBalance(false, 0L, "GHS", "Direction must be 'charge' or 'payout'");
        }
        if (!isConfigured(direction)) {
            return new AccountBalance(false, 0L, "GHS", "MTN credentials not configured for " + direction);
        }
        long start = System.currentTimeMillis();
        try {
            String product = product(direction);
            Credentials c = credentials(direction);
            String token = getOrRefreshToken(product, c, false);
            String balancePath = "/" + product + "/v1_0/account/balance";

            HttpRequest req = buildRequest(balancePath)
                    .header("Authorization", "Bearer " + token)
                    .header("Ocp-Apim-Subscription-Key", c.subscription())
                    .GET().build();
            Response response = send(req);
            if (response.status() == 401) {
                token = getOrRefreshToken(product, c, true);
                req = buildRequest(balancePath)
                        .header("Authorization", "Bearer " + token)
                        .header("Ocp-Apim-Subscription-Key", c.subscription())
                        .GET().build();
                response = send(req);
            }
            long latency = System.currentTimeMillis() - start;
            health.record(name(), mtnAnswered(response), false, latency);
            if (isNonJsonWafResponse(response)) {
                return new AccountBalance(false, 0L, "GHS", "MTN balance unavailable: upstream returned WAF/HTML response");
            }
            if (response.status() != 200) {
                return new AccountBalance(false, 0L, "GHS", "MTN balance failed: HTTP " + response.status());
            }
            JsonNode node = json.readTree(response.body());
            JsonNode balNode = node.path("availableBalance");
            BigDecimal amountMajor;
            if (balNode.isNumber()) {
                amountMajor = balNode.decimalValue();
            } else {
                String rawAmount = balNode.asString("").trim();
                if (rawAmount.isEmpty()) {
                    return new AccountBalance(false, 0L, "GHS", "MTN balance missing availableBalance field");
                }
                amountMajor = new BigDecimal(rawAmount);
            }
            String currency = node.path("currency").asString("GHS");
            long pesewas = amountMajor.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
            return new AccountBalance(true, pesewas, currency, "MTN " + product + " balance retrieved");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AccountBalance(false, 0L, "GHS", "MTN balance request interrupted");
        } catch (HttpTimeoutException e) {
            health.record(name(), false, true, System.currentTimeMillis() - start);
            return new AccountBalance(false, 0L, "GHS", "MTN balance request timed out");
        } catch (Exception e) {
            health.record(name(), false, false, System.currentTimeMillis() - start);
            return new AccountBalance(false, 0L, "GHS", e instanceof AuthenticationWafException
                    ? "MTN balance authentication rejected by upstream WAF" : "MTN balance unavailable");
        }
    }

    /**
     * Validates whether an MTN Ghana MSISDN is an active MoMo account holder
     * (GET /{product}/v1_0/accountholder/msisdn/{msisdn}/active).
     * Required by MTN MoMo OpenAPI UAT Sheet 4 (ValidateAccount).
     */
    public AccountValidation validateAccountHolder(String direction, String rawMsisdn) {
        if (!Set.of("charge", "payout").contains(direction)) {
            return new AccountValidation(false, false, rawMsisdn, "Direction must be 'charge' or 'payout'");
        }
        String normalized = rawMsisdn == null ? "" : rawMsisdn.replaceAll("\\D", "");
        if (normalized.startsWith("0") && normalized.length() == 10) {
            normalized = "233" + normalized.substring(1);
        }
        if (!normalized.matches("233\\d{9}")) {
            return new AccountValidation(false, false, normalized, "Invalid Ghana MSISDN (expected 233XXXXXXXXX)");
        }
        if (!isConfigured(direction)) {
            return new AccountValidation(false, false, normalized, "MTN credentials not configured for " + direction);
        }
        long start = System.currentTimeMillis();
        try {
            String product = product(direction);
            Credentials c = credentials(direction);
            String token = getOrRefreshToken(product, c, false);
            String activePath = "/" + product + "/v1_0/accountholder/msisdn/" + normalized + "/active";

            HttpRequest req = buildRequest(activePath)
                    .header("Authorization", "Bearer " + token)
                    .header("Ocp-Apim-Subscription-Key", c.subscription())
                    .GET().build();
            Response response = send(req);
            if (response.status() == 401) {
                token = getOrRefreshToken(product, c, true);
                req = buildRequest(activePath)
                        .header("Authorization", "Bearer " + token)
                        .header("Ocp-Apim-Subscription-Key", c.subscription())
                        .GET().build();
                response = send(req);
            }
            long latency = System.currentTimeMillis() - start;
            health.record(name(), mtnAnswered(response), false, latency);
            if (isNonJsonWafResponse(response)) {
                return new AccountValidation(false, false, normalized, "MTN validation unavailable: WAF/HTML response");
            }
            if (response.status() != 200) {
                return new AccountValidation(false, false, normalized, "MTN validation failed: HTTP " + response.status());
            }
            JsonNode node = json.readTree(response.body());
            boolean active = node.path("result").asBoolean(false);
            return new AccountValidation(true, active, normalized, active ? "Active MTN MoMo account" : "Account not active on MTN MoMo");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AccountValidation(false, false, normalized, "MTN validation interrupted");
        } catch (HttpTimeoutException e) {
            health.record(name(), false, true, System.currentTimeMillis() - start);
            return new AccountValidation(false, false, normalized, "MTN validation timed out");
        } catch (Exception e) {
            health.record(name(), false, false, System.currentTimeMillis() - start);
            return new AccountValidation(false, false, normalized, e instanceof AuthenticationWafException
                    ? "MTN validation authentication rejected by upstream WAF" : "MTN validation unavailable");
        }
    }
}
