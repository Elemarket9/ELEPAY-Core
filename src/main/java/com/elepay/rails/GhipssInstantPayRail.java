package com.elepay.rails;

import com.elepay.common.qr.EmvcoQr;
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
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * Direct GhIPSS (Ghana Interbank Payment and Settlement Systems) Instant Pay (GIP)
 * and GhQR payment adapter.
 * Connects directly to the national clearing and settlement switch for instant
 * interbank credit transfers and EMVCo-compliant GhQR payments.
 *
 * <p><strong>Fee Structure:</strong> Flat fee of 50 pesewas (0.50 GHS) per interbank transfer.
 * <p><strong>Authentication:</strong> API Key and HMAC-SHA256 payload signing ({@code X-GhIPSS-Signature}).
 * <p><strong>Callbacks:</strong> Authoritative HMAC-SHA256 verification in {@code x-ghipss-signature}.
 */
@Component
@ConditionalOnProperty(name = "elepay.rails.ghipss.enabled", havingValue = "true")
public class GhipssInstantPayRail implements PaymentRail {
    private static final Logger log = LoggerFactory.getLogger(GhipssInstantPayRail.class);

    public static final long FLAT_FEE_PESEWAS = 50L;

    public record Credentials(String institutionCode, String apiKey, String signingSecret, String webhookSecret) {
        public boolean isValid() {
            return institutionCode != null && !institutionCode.isBlank()
                    && apiKey != null && !apiKey.isBlank()
                    && signingSecret != null && !signingSecret.isBlank();
        }
    }

    public record GhQrVerification(
            boolean valid,
            String reference,
            Long amountPesewas,
            String currency,
            String merchantName,
            String terminalId,
            String errorMessage
    ) {
        public static GhQrVerification invalid(String message) {
            return new GhQrVerification(false, null, null, null, null, null, message);
        }
    }

    private record Response(int status, String body, String contentType) {}

    private final URI base;
    private final Credentials credentials;
    private final ObjectMapper json;
    private final NetworkHealthDetector health;
    private final HttpClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public GhipssInstantPayRail(
            ObjectMapper json,
            NetworkHealthDetector health,
            @Value("${elepay.rails.ghipss.base-url:}") String baseUrl,
            @Value("${elepay.rails.ghipss.institution-code:}") String institutionCode,
            @Value("${elepay.rails.ghipss.api-key:}") String apiKey,
            @Value("${elepay.rails.ghipss.signing-secret:}") String signingSecret,
            @Value("${elepay.rails.ghipss.webhook-secret:}") String webhookSecret
    ) {
        this(
                json,
                health,
                (baseUrl == null || baseUrl.isBlank()) ? URI.create("https://gip.ghipss.com") : productionBase(baseUrl),
                new Credentials(institutionCode, apiKey, signingSecret, webhookSecret),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER).build()
        );
    }

    public GhipssInstantPayRail(
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
            return URI.create("https://gip.ghipss.com");
        }
        URI u = URI.create(url);
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null
                || !Set.of("gip.ghipss.com", "api.ghipss.com", "switch.ghipss.com")
                        .contains(u.getHost().toLowerCase(Locale.ROOT))
                || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null
                || (u.getPort() != -1 && u.getPort() != 443)
                || !(u.getPath().isEmpty() || u.getPath().equals("/"))) {
            throw new IllegalStateException("Use an approved GhIPSS production HTTPS API origin");
        }
        return u;
    }

    public boolean isConfigured() {
        return credentials != null && credentials.isValid();
    }

    @Override
    public String name() {
        return "ghipss";
    }

    @Override
    public boolean supports(String direction, String channel, String network) {
        return Set.of("charge", "payout").contains(direction)
                && ("bank".equalsIgnoreCase(channel) || "ghqr".equalsIgnoreCase(channel))
                && (network == null || "ghipss".equalsIgnoreCase(network) || "bank".equalsIgnoreCase(network));
    }

    public static UUID sessionId(String direction, String reference) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    ("elepay:ghipss:gip:v1:" + direction + ":" + reference).getBytes(StandardCharsets.UTF_8));
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

    // ==================== GhQR Implementation ====================

    /**
     * Generates a standard EMVCo Merchant-Presented Mode (MPM) GhQR payload.
     * Complies with Bank of Ghana national GhQR standards and Algorithm 022.
     */
    public String generateGhQrPayload(
            String reference,
            long amountPesewas,
            String merchantName,
            String merchantCity,
            String terminalId
    ) {
        String pointOfInitiation = amountPesewas > 0 ? EmvcoQr.POINT_DYNAMIC : EmvcoQr.POINT_STATIC;
        String name = (merchantName != null && !merchantName.isBlank()) ? merchantName : "ELEPAY Merchant";
        String city = (merchantCity != null && !merchantCity.isBlank()) ? merchantCity : "Accra";
        String term = (terminalId != null && !terminalId.isBlank()) ? terminalId : "ELE01";

        EmvcoQr.Builder builder = EmvcoQr.builder()
                .pointOfInitiation(pointOfInitiation)
                .merchantCode(credentials != null ? credentials.institutionCode() : "ELEPAY")
                .merchantCategoryCode(EmvcoQr.DEFAULT_MCC)
                .currency(EmvcoQr.DEFAULT_CURRENCY_GHS)
                .countryCode(EmvcoQr.DEFAULT_COUNTRY)
                .merchantName(name)
                .merchantCity(city)
                .reference(reference);

        if (amountPesewas > 0) {
            builder.amount(BigDecimal.valueOf(amountPesewas, 2).toPlainString());
        }

        return builder.build();
    }

    /**
     * Verifies and decodes a GhQR payload, validating the EMVCo format,
     * Ghana country/currency codes, and Tag 63 CRC-16 checksum.
     */
    public GhQrVerification verifyGhQrPayload(String qrPayload) {
        if (qrPayload == null || qrPayload.length() < 20) {
            return GhQrVerification.invalid("QR payload is too short or empty");
        }

        try {
            EmvcoQr.Decoded decoded = EmvcoQr.decode(qrPayload);
            if (!decoded.valid()) {
                return GhQrVerification.invalid("Invalid QR CRC checksum or format indicator");
            }
            if (!"GH".equalsIgnoreCase(decoded.countryCode())) {
                return GhQrVerification.invalid("Invalid country code in GhQR: " + decoded.countryCode());
            }
            if (!EmvcoQr.DEFAULT_CURRENCY_GHS.equals(decoded.currency())) {
                return GhQrVerification.invalid("Invalid currency code in GhQR: " + decoded.currency());
            }

            Long amountPesewas = null;
            if (decoded.amount() != null && !decoded.amount().isBlank()) {
                amountPesewas = new BigDecimal(decoded.amount()).movePointRight(2).longValueExact();
            }

            String ref = decoded.additionalData() != null ? decoded.additionalData().get("05") : null;

            return new GhQrVerification(
                    true,
                    ref,
                    amountPesewas,
                    "GHS",
                    decoded.merchantName(),
                    decoded.merchantCode(),
                    null
            );
        } catch (Exception e) {
            return GhQrVerification.invalid("Malformed GhQR payload: " + e.getMessage());
        }
    }

    // ==================== Payment Rail Processing ====================

    @Override
    public Result charge(Charge request) {
        if (!supports("charge", request.channel(), request.network())) {
            return new Result(Status.FAILED, null, request.amount(), request.currency(), null, null, "Unsupported GhIPSS destination");
        }
        if (!isConfigured()) {
            return Result.unavailable("GhIPSS Instant Pay production credentials not configured for charge");
        }

        if ("ghqr".equalsIgnoreCase(request.channel())) {
            String qrPayload = generateGhQrPayload(
                    request.reference(),
                    request.amount(),
                    request.customerName(),
                    "Accra",
                    "TERM01"
            );
            String railRef = "GIP-QR-" + request.reference();
            return new Result(Status.PENDING, railRef, request.amount(), request.currency(), FLAT_FEE_PESEWAS, qrPayload,
                    "GhQR dynamic payment payload generated");
        }

        return submitTransfer("charge", request.reference(), request.amount(), request.currency(),
                request.msisdn(), request.customerName(), request.description());
    }

    @Override
    public Result payout(Payout request) {
        if (!supports("payout", request.type(), request.network())) {
            return new Result(Status.FAILED, null, request.amount(), request.currency(), null, null, "Unsupported GhIPSS destination");
        }
        if (!isConfigured()) {
            return Result.unavailable("GhIPSS Instant Pay production credentials not configured for payout");
        }
        return submitTransfer("payout", request.reference(), request.amount(), request.currency(),
                request.accountNumber(), request.accountName(), request.description());
    }

    @Override
    public Result refund(Refund request) {
        return Result.unknown("GhIPSS interbank refund requires formal return clearing message");
    }

    private Result submitTransfer(
            String direction,
            String ref,
            long amount,
            String currency,
            String accountNumber,
            String accountName,
            String narrative
    ) {
        if (amount <= 0 || !"GHS".equals(currency)) {
            return new Result(Status.FAILED, null, amount, currency, null, null, "Invalid Ghana currency or amount");
        }
        long start = System.currentTimeMillis();
        String session = sessionId(direction, ref).toString().replace("-", "").substring(0, 24);
        String railRef = "GIP-" + ref;

        try {
            String endpoint = direction.equals("charge") ? "/gip/v1/direct-debit" : "/gip/v1/transfer";
            Map<String, Object> bodyMap = Map.of(
                    "sessionID", session,
                    "channelCode", "7",
                    "institutionCode", credentials.institutionCode(),
                    "accountNumber", accountNumber != null ? accountNumber : "",
                    "accountName", accountName != null ? accountName : "Account Holder",
                    "amount", BigDecimal.valueOf(amount, 2).toPlainString(),
                    "currency", currency,
                    "paymentReference", ref,
                    "narrative", MtnProductionRail.sanitizeNote(narrative, ref)
            );
            String payload = json.writeValueAsString(bodyMap);
            String signature = computeHmac(credentials.signingSecret(), payload);

            HttpRequest req = buildRequest(endpoint)
                    .header("X-GhIPSS-Institution-Code", credentials.institutionCode())
                    .header("X-GhIPSS-API-Key", credentials.apiKey())
                    .header("X-GhIPSS-Signature", signature)
                    .header("X-Session-ID", session)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();

            Response response = send(req);
            long latency = System.currentTimeMillis() - start;
            boolean accepted = (response.status() == 200 || response.status() == 202) && !isNonJsonWafResponse(response);
            health.record(name(), accepted, false, latency);

            if (isNonJsonWafResponse(response)) {
                return Result.unknown("GhIPSS request rejected by upstream WAF (non-JSON HTML response)");
            }
            if (response.status() == 200 || response.status() == 202) {
                JsonNode root;
                try {
                    root = json.readTree(response.body());
                } catch (RuntimeException parseError) {
                    return Result.unknown("GhIPSS switch returned invalid non-JSON response");
                }
                String responseCode = root.path("responseCode").asString("");
                if ("00".equals(responseCode)) {
                    // Synchronous instant clearing confirmed
                    String txnId = root.path("gipTransactionId").asString(session);
                    return new Result(Status.SUCCEEDED, txnId, amount, currency, FLAT_FEE_PESEWAS, null,
                            "GhIPSS Instant Pay interbank transfer settled");
                }
                if ("09".equals(responseCode) || "96".equals(responseCode) || responseCode.isBlank()) {
                    return new Result(Status.PENDING, railRef, amount, currency, FLAT_FEE_PESEWAS, null,
                            "GhIPSS interbank clearing in progress");
                }
                return new Result(Status.FAILED, railRef, amount, currency, FLAT_FEE_PESEWAS, null,
                        "GhIPSS transfer declined: code " + responseCode);
            }

            if (response.status() == 400 || response.status() == 422) {
                return new Result(Status.FAILED, null, amount, currency, FLAT_FEE_PESEWAS, null,
                        "GhIPSS switch rejected request: " + response.body());
            }

            return Result.unknown("GhIPSS transfer outcome requires status verification");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unknown("GhIPSS request interrupted");
        } catch (HttpTimeoutException e) {
            health.record(name(), false, true, System.currentTimeMillis() - start);
            return Result.unknown("GhIPSS clearing switch timeout; query status");
        } catch (Exception e) {
            health.record(name(), false, false, System.currentTimeMillis() - start);
            return Result.unknown("GhIPSS switch unavailable: " + e.getMessage());
        }
    }

    @Override
    public Result query(String direction, String reference, String railReference) {
        if (!Set.of("charge", "payout").contains(direction)) {
            return Result.unknown("Unsupported GhIPSS query direction: " + direction);
        }
        if (!isConfigured()) {
            return Result.unavailable("GhIPSS Instant Pay production credentials not configured for query");
        }
        long start = System.currentTimeMillis();
        try {
            HttpRequest req = buildRequest("/gip/v1/query/" + reference)
                    .header("X-GhIPSS-Institution-Code", credentials.institutionCode())
                    .header("X-GhIPSS-API-Key", credentials.apiKey())
                    .GET().build();

            Response response = send(req);
            long latency = System.currentTimeMillis() - start;
            boolean ok = (response.status() == 200 && !isNonJsonWafResponse(response)) || response.status() == 404;
            health.record(name(), ok, false, latency);

            if (isNonJsonWafResponse(response)) {
                return Result.unknown("GhIPSS status query unavailable: upstream returned non-JSON WAF/HTML response");
            }
            if (response.status() == 404) {
                return new Result(Status.NOT_FOUND, null, null, null, null, null, "GhIPSS payment reference not found");
            }
            if (response.status() != 200) {
                return Result.unknown("GhIPSS status query failed: HTTP " + response.status());
            }

            JsonNode statusNode;
            try {
                statusNode = json.readTree(response.body());
            } catch (RuntimeException parseError) {
                return Result.unknown("GhIPSS status query unavailable: invalid non-JSON response");
            }
            return parseStatus(statusNode, reference);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unknown("GhIPSS status request interrupted");
        } catch (HttpTimeoutException e) {
            health.record(name(), false, true, System.currentTimeMillis() - start);
            return Result.unknown("GhIPSS status request timed out");
        } catch (Exception e) {
            health.record(name(), false, false, System.currentTimeMillis() - start);
            return Result.unknown("GhIPSS status query unavailable: " + e.getMessage());
        }
    }

    public static Result parseStatus(JsonNode node, String reference) {
        String ref = node.path("paymentReference").asString("");
        if (ref.isBlank()) ref = node.path("reference").asString("");
        if (!reference.equals(ref)) {
            return Result.unknown("GhIPSS reference mismatch");
        }

        String responseCode = node.path("responseCode").asString("");
        String status = node.path("status").asString("").toUpperCase(Locale.ROOT);

        if ("09".equals(responseCode) || "96".equals(responseCode) || "PENDING".equals(status)) {
            return new Result(Status.PENDING, null, null, null, null, null, "GhIPSS interbank clearing pending");
        }
        if (!"00".equals(responseCode) && !"SUCCESSFUL".equals(status) && !"COMPLETED".equals(status)) {
            String reason = node.path("narrative").asString("GhIPSS clearing declined (code " + responseCode + ")");
            return new Result(Status.FAILED, null, null, null, null, null, reason);
        }

        try {
            long amount = new BigDecimal(node.path("amount").asString("")).movePointRight(2).longValueExact();
            String currency = node.path("currency").asString("");
            String financialId = node.path("gipTransactionId").asString("");
            if (financialId.isBlank()) financialId = node.path("sessionID").asString("");
            if (amount <= 0 || !"GHS".equals(currency) || financialId.isBlank()) {
                return Result.unknown("GhIPSS success lacks financial evidence");
            }
            return new Result(Status.SUCCEEDED, financialId, amount, currency, FLAT_FEE_PESEWAS, null,
                    "GhIPSS interbank clearing confirmed");
        } catch (RuntimeException e) {
            return Result.unknown("GhIPSS amount evidence malformed");
        }
    }

    private HttpRequest.Builder buildRequest(String path) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "ELEPAY-Core/1.0")
                .header("Accept", "application/json");
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
            if (body.length > 65536) throw new IllegalStateException("GhIPSS response too large");
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
            throw new InvalidCallbackException("GhIPSS webhook secret is not configured");
        }

        try {
            com.elepay.common.CallbackAuthenticity.verify(secret, headers, body, "x-ghipss-signature");
        } catch (IllegalArgumentException e) {
            throw new InvalidCallbackException(e.getMessage());
        }

        try {
            JsonNode root = json.readTree(body);
            String event = root.path("event").asString("payout.success");
            JsonNode data = root.path("data").isMissingNode() ? root : root.path("data");
            String reference = data.path("reference").asString();
            String status = data.path("status").asString();
            String rawAmount = data.path("amount").asString("").trim();
            if (rawAmount.isEmpty()) {
                throw new InvalidCallbackException("GhIPSS callback amount is missing");
            }
            long amount = new BigDecimal(rawAmount).movePointRight(2).longValueExact();
            if (amount <= 0) {
                throw new InvalidCallbackException("GhIPSS callback amount must be positive");
            }
            String currency = data.path("currency").asString("GHS");
            String railRef = data.path("railReference").asString("GIP-" + reference);

            Status outcome = switch (status.toUpperCase(Locale.ROOT)) {
                case "SUCCESSFUL", "COMPLETED", "00" -> Status.SUCCEEDED;
                case "FAILED", "DECLINED" -> Status.FAILED;
                default -> Status.PENDING;
            };

            Result result = new Result(outcome, railRef, amount, currency, FLAT_FEE_PESEWAS, null, "GhIPSS callback processed");
            return new Callback("ghipss:" + reference + ":" + status, event, "payout", reference, railRef, result);
        } catch (InvalidCallbackException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidCallbackException("Failed to parse GhIPSS callback payload: " + e.getMessage());
        }
    }
}
