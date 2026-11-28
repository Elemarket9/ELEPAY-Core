package com.elepay.rails;

import com.elepay.cards.NetworkTokenService;
import com.elepay.common.CallbackAuthenticity;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Production Card Acquiring Rail ({@code name() = "card"}).
 *
 * <p>Integrates with {@link CardTokenVault} (PCI DSS tokenization boundary),
 * {@link NetworkTokenService} (EMVCo network token &amp; ATC boundary), and
 * {@link CustodianBankBackstopService} (custodian trust outflow backstop on refunds).
 *
 * <p>Strictly separates {@code LIVE}, {@code PARTNER_TEST}, and {@code SANDBOX} execution modes,
 * failing closed when live acquirer credentials or endpoints are absent and never fabricating
 * live settlement success.
 */
@Component
@ConditionalOnProperty(name = "elepay.rails.card.enabled", havingValue = "true")
public class CardAcquiringRail implements PaymentRail {
    private static final Logger log = LoggerFactory.getLogger(CardAcquiringRail.class);
    private static final Set<String> SUPPORTED_CHANNELS = Set.of("card", "nfc");

    public enum ExecutionMode {
        LIVE,
        PARTNER_TEST,
        SANDBOX;

        public static ExecutionMode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return LIVE;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "live" -> LIVE;
                case "partner-test", "partner_test" -> PARTNER_TEST;
                case "sandbox" -> SANDBOX;
                default -> throw new IllegalStateException("Unsupported card rail mode: " + raw);
            };
        }
    }

    private final ExecutionMode mode;
    private final URI acquirerBaseUri;
    private final String acquirerId;
    private final String acquirerSecret;
    private final String webhookSecret;
    private final CardTokenVault cardTokenVault;
    private final NetworkTokenService networkTokenService;
    private final CustodianBankBackstopService custodianBackstop;
    private final NetworkHealthDetector health;
    private final ObjectMapper json;
    private final HttpClient client;

    @Autowired
    public CardAcquiringRail(
            ObjectMapper json,
            NetworkHealthDetector health,
            CardTokenVault cardTokenVault,
            @Autowired(required = false) NetworkTokenService networkTokenService,
            @Autowired(required = false) CustodianBankBackstopService custodianBackstop,
            @Value("${elepay.rails.card.mode:live}") String mode,
            @Value("${elepay.rails.card.acquirer-url:}") String acquirerUrl,
            @Value("${elepay.rails.card.acquirer-id:}") String acquirerId,
            @Value("${elepay.rails.card.acquirer-secret:}") String acquirerSecret,
            @Value("${elepay.rails.card.webhook-secret:}") String webhookSecret
    ) {
        this(
                ExecutionMode.parse(mode),
                parseAcquirerUri(acquirerUrl, ExecutionMode.parse(mode)),
                acquirerId,
                acquirerSecret,
                webhookSecret,
                cardTokenVault,
                networkTokenService,
                custodianBackstop,
                health,
                json,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()
        );
    }

    public CardAcquiringRail(
            ExecutionMode mode,
            URI acquirerBaseUri,
            String acquirerId,
            String acquirerSecret,
            String webhookSecret,
            CardTokenVault cardTokenVault,
            NetworkTokenService networkTokenService,
            CustodianBankBackstopService custodianBackstop,
            NetworkHealthDetector health,
            ObjectMapper json,
            HttpClient client
    ) {
        this.mode = mode != null ? mode : ExecutionMode.LIVE;
        this.acquirerBaseUri = acquirerBaseUri;
        this.acquirerId = acquirerId;
        this.acquirerSecret = acquirerSecret;
        this.webhookSecret = webhookSecret;
        this.cardTokenVault = cardTokenVault;
        this.networkTokenService = networkTokenService;
        this.custodianBackstop = custodianBackstop;
        this.health = health;
        this.json = json;
        this.client = client != null ? client : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static URI parseAcquirerUri(String rawUrl, ExecutionMode mode) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        URI uri = URI.create(rawUrl.trim());
        if (mode == ExecutionMode.LIVE) {
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalStateException("Live card acquirer URL must be a valid HTTPS origin without userinfo");
            }
        }
        return uri;
    }

    @Override
    public String name() {
        return "card";
    }

    public ExecutionMode mode() {
        return mode;
    }

    @Override
    public boolean supports(String direction, String channel, String network) {
        if (!"charge".equalsIgnoreCase(direction) && !"refund".equalsIgnoreCase(direction)) {
            return false;
        }
        if (channel == null || channel.isBlank()) {
            return false;
        }
        return SUPPORTED_CHANNELS.contains(channel.toLowerCase(Locale.ROOT));
    }

    public boolean isConfigured() {
        return acquirerBaseUri != null
                && acquirerId != null && !acquirerId.isBlank()
                && acquirerSecret != null && !acquirerSecret.isBlank();
    }

    @Override
    public Result charge(Charge charge) {
        if (charge == null || charge.amount() <= 0 || charge.reference() == null || charge.reference().isBlank()) {
            return new Result(Status.FAILED, null, null, null, null, null, "Invalid card charge request");
        }
        if (!isConfigured()) {
            return Result.unavailable("Card acquirer is not configured for " + mode.name().toLowerCase(Locale.ROOT) + " mode");
        }

        // Validate tokenized card within the vault boundary (never decrypt PAN unless transmitted)
        String tokenRef = charge.network();
        String maskedCardRef = null;
        if (tokenRef != null && tokenRef.startsWith("tok_card_") && cardTokenVault != null) {
            var cardOpt = cardTokenVault.findByToken(tokenRef);
            if (cardOpt.isEmpty() || !"active".equalsIgnoreCase(cardOpt.get().status())) {
                return new Result(Status.FAILED, null, null, null, null, null, "Card token is missing or inactive");
            }
            var card = cardOpt.get();
            if (isCardExpiredOrInvalid(card.expiryYear(), card.expiryMonth())) {
                return new Result(Status.FAILED, null, null, null, null, null, "Card token is expired or has invalid expiry");
            }
            maskedCardRef = card.maskedPan();
        } else if ("nfc".equalsIgnoreCase(charge.channel()) && networkTokenService != null && tokenRef != null && !tokenRef.isBlank()) {
            var netOpt = networkTokenService.findByTokenPan(tokenRef);
            if (netOpt.isEmpty()) {
                return new Result(Status.FAILED, null, null, null, null, null, "Network token not found");
            }
            var netToken = netOpt.get();
            if (netToken.status() != com.elepay.cards.TokenStatus.ACTIVE) {
                return new Result(Status.FAILED, null, null, null, null, null, "Network token is " + netToken.status());
            }
            if (isCardExpiredOrInvalid(netToken.expiryYear(), netToken.expiryMonth())) {
                return new Result(Status.FAILED, null, null, null, null, null, "Network token is expired or has invalid expiry");
            }
            maskedCardRef = "net:" + netToken.id();
        }

        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> payload = Map.of(
                    "reference", charge.reference(),
                    "amount", charge.amount(),
                    "currency", charge.currency() != null ? charge.currency() : "GHS",
                    "channel", charge.channel() != null ? charge.channel() : "card",
                    "mode", mode.name(),
                    "tokenReference", tokenRef != null ? tokenRef : "",
                    "maskedPan", maskedCardRef != null ? maskedCardRef : "",
                    "returnUrl", charge.returnUrl() != null ? charge.returnUrl() : ""
            );
            HttpRequest req = HttpRequest.newBuilder(acquirerBaseUri.resolve("/v1/acquiring/charges"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-Acquirer-Id", acquirerId)
                    .header("Authorization", "Bearer " + acquirerSecret)
                    .header("Idempotency-Key", charge.reference())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            String body = readBoundedBody(resp);
            Result result = interpretAcquirerResponse(resp.statusCode(), body, charge.amount(), charge.currency());
            if (health != null) {
                health.record("card", result.status() != Status.UNAVAILABLE, false, System.currentTimeMillis() - startMs);
            }
            return result;
        } catch (HttpTimeoutException e) {
            if (health != null) health.record("card", false, true, System.currentTimeMillis() - startMs);
            return Result.unknown("Card acquirer timeout; outcome unknown");
        } catch (Exception e) {
            log.warn("Card acquirer charge error for {}: {}", charge.reference(), e.getMessage());
            if (health != null) health.record("card", false, false, System.currentTimeMillis() - startMs);
            return Result.unknown("Card acquirer communication error");
        }
    }

    @Override
    public Result payout(Payout payout) {
        return new Result(Status.FAILED, null, null, null, null, null,
                "Card acquiring rail does not support outbound payouts");
    }

    @Override
    public Result refund(Refund refund) {
        if (refund == null || refund.amount() <= 0 || refund.reference() == null || refund.reference().isBlank()) {
            return new Result(Status.FAILED, null, null, null, null, null, "Invalid card refund request");
        }
        // Custodian Bank outflow authorization is enforced once at the RailTransactions.send() boundary
        // for all outbound disbursements (payouts and refunds), avoiding double-reservation leaks.
        if (!isConfigured()) {
            return Result.unavailable("Card acquirer is not configured for refund in " + mode.name().toLowerCase(Locale.ROOT) + " mode");
        }

        try {
            Map<String, Object> payload = Map.of(
                    "reference", refund.reference(),
                    "originalReference", refund.originalReference() != null ? refund.originalReference() : "",
                    "originalRailReference", refund.originalRailReference() != null ? refund.originalRailReference() : "",
                    "amount", refund.amount(),
                    "currency", refund.currency() != null ? refund.currency() : "GHS",
                    "mode", mode.name()
            );
            HttpRequest req = HttpRequest.newBuilder(acquirerBaseUri.resolve("/v1/acquiring/refunds"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-Acquirer-Id", acquirerId)
                    .header("Authorization", "Bearer " + acquirerSecret)
                    .header("Idempotency-Key", refund.reference())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            String body = readBoundedBody(resp);
            return interpretAcquirerResponse(resp.statusCode(), body, refund.amount(), refund.currency());
        } catch (HttpTimeoutException e) {
            return Result.unknown("Card acquirer refund timeout; outcome unknown");
        } catch (Exception e) {
            log.warn("Card acquirer refund error for {}: {}", refund.reference(), e.getMessage());
            return Result.unknown("Card acquirer refund communication error");
        }
    }

    @Override
    public Result query(String direction, String reference, String railReference) {
        if (!isConfigured()) {
            return Result.unavailable("Card acquirer is not configured for status query");
        }
        try {
            String encodedRef = java.net.URLEncoder.encode(reference, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(acquirerBaseUri.resolve("/v1/acquiring/transactions/" + encodedRef))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("X-Acquirer-Id", acquirerId)
                    .header("Authorization", "Bearer " + acquirerSecret)
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            String body = readBoundedBody(resp);
            if (resp.statusCode() == 404) {
                return new Result(Status.NOT_FOUND, null, null, null, null, null, "Transaction not found at acquirer");
            }
            return interpretAcquirerResponse(resp.statusCode(), body, null, null);
        } catch (Exception e) {
            return Result.unknown("Card acquirer query failed: " + e.getMessage());
        }
    }

    @Override
    public Callback verifyCallback(Map<String, String> headers, String body) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new InvalidCallbackException("Card acquirer webhook secret is not configured");
        }
        try {
            CallbackAuthenticity.verify(webhookSecret, headers, body, "x-card-signature", "x-callback-signature");
        } catch (IllegalArgumentException e) {
            throw new InvalidCallbackException(e.getMessage());
        }
        try {
            JsonNode root = json.readTree(body);
            String reference = root.path("reference").asString("");
            String railReference = root.path("railReference").asString("");
            String eventType = root.path("eventType").asString("card.callback");
            String direction = root.path("direction").asString("charge");
            String dedupeKey = root.path("eventId").asString(reference + ":" + eventType);
            Result res = interpretAcquirerNode(root, null, null);
            return new Callback(dedupeKey, eventType, direction, reference, railReference, res);
        } catch (Exception e) {
            throw new InvalidCallbackException("Malformed card acquirer callback payload");
        }
    }

    @Override
    public boolean callbacksAreAuthoritative() {
        return mode == ExecutionMode.LIVE && webhookSecret != null && !webhookSecret.isBlank();
    }

    private Result interpretAcquirerResponse(int statusCode, String body, Long expectedAmount, String expectedCurrency) {
        if (statusCode >= 500 || statusCode == 408 || statusCode == 409 || statusCode == 429) {
            return Result.unknown("Card acquirer returned ambiguous HTTP " + statusCode);
        }
        if (statusCode == 400 || statusCode == 422) {
            return new Result(Status.FAILED, null, null, null, null, null, "Card acquirer rejected request (" + statusCode + ")");
        }
        if (statusCode < 200 || statusCode >= 300) {
            return Result.unknown("Unexpected card acquirer status " + statusCode);
        }
        try {
            JsonNode root = json.readTree(body);
            return interpretAcquirerNode(root, expectedAmount, expectedCurrency);
        } catch (Exception e) {
            return Result.unknown("Malformed card acquirer response");
        }
    }

    private Result interpretAcquirerNode(JsonNode root, Long expectedAmount, String expectedCurrency) {
        String statusStr = root.path("status").asString("").toUpperCase(Locale.ROOT);
        String railRef = root.path("railReference").asString("");
        String redirectUrl = root.path("redirectUrl").asString(null);
        String message = root.path("message").asString("Card acquirer status: " + statusStr);

        return switch (statusStr) {
            // AUTHORIZED is an uncaptured hold — never book as settled SUCCEEDED until CAPTURED/SUCCEEDED.
            case "PENDING", "3DS_REQUIRED", "CHALLENGE", "AUTHORIZED" ->
                    new Result(Status.PENDING, railRef.isBlank() ? null : railRef, null, null, null, redirectUrl, message);
            case "FAILED", "DECLINED", "REJECTED" ->
                    new Result(Status.FAILED, railRef.isBlank() ? null : railRef, null, null, null, null, message);
            case "SUCCEEDED", "CAPTURED" -> {
                if (mode != ExecutionMode.LIVE) {
                    // Never present non-live responses as live settlement success
                    yield new Result(Status.PENDING, railRef.isBlank() ? null : railRef, null, null, null, redirectUrl,
                            "Non-live (" + mode.name() + ") acquirer response; not settled live");
                }
                long amt = root.path("amount").asLong(-1L);
                String cur = root.path("currency").asString(expectedCurrency != null ? expectedCurrency : "");
                Long fee = root.has("fee") && !root.path("fee").isNull() ? root.path("fee").asLong(0L) : 0L;
                if (amt <= 0 || cur.isBlank() || railRef.isBlank()
                        || (expectedAmount != null && amt != expectedAmount)) {
                    yield Result.unknown("Card acquirer success lacks authoritative amount/currency/reference evidence");
                }
                yield new Result(Status.SUCCEEDED, railRef, amt, cur, fee, null, message);
            }
            default -> Result.unknown("Unrecognized card acquirer status: " + statusStr);
        };
    }

    private static boolean isCardExpiredOrInvalid(int year, int month) {
        if (month < 1 || month > 12 || year < 2000 || year > 2100) {
            return true;
        }
        try {
            return java.time.YearMonth.of(year, month).isBefore(java.time.YearMonth.now());
        } catch (java.time.DateTimeException e) {
            return true;
        }
    }

    private static String readBoundedBody(HttpResponse<InputStream> response) throws Exception {
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes(65537);
            if (bytes.length > 65536) {
                throw new IllegalStateException("Card acquirer response exceeded 64KB");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
