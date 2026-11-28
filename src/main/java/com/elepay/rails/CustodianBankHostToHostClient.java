package com.elepay.rails;

import com.elepay.common.OutboundUrlGuard;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Production host-to-host HTTP adapter for ELEPAY's contracted Custodian Bank Trust Account.
 *
 * <p>Opt-in only via {@code elepay.custodian.h2h.enabled=true}. Enforces:
 * <ul>
 *   <li>Strict HTTPS host allowlist configured explicitly via {@code elepay.custodian.h2h.allowed-hosts}
 *       plus {@link OutboundUrlGuard} SSRF checks (no commercial bank host is assumed or hardcoded).</li>
 *   <li>Zero hardcoded credentials — all secrets come from environment variables.</li>
 *   <li>HMAC-SHA256 request signing ({@code X-Custodian-Signature}) with timestamp and
 *       {@code X-Idempotency-Key} binding.</li>
 *   <li>Fail-closed timeout/408/409/429/5xx semantics: ambiguous network or HTTP outcomes always return
 *       {@link DisbursementStatus#UNKNOWN} so callers must reconcile via {@link #queryDisbursement}
 *       before any retry.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "elepay.custodian.h2h.enabled", havingValue = "true")
public class CustodianBankHostToHostClient implements CustodianBankGateway {

    private static final Logger log = LoggerFactory.getLogger(CustodianBankHostToHostClient.class);

    public record Credentials(
            String institutionCode,
            String trustAccountNumber,
            String apiKey,
            String signingSecret
    ) {
        public boolean isValid() {
            return notBlank(institutionCode)
                    && notBlank(trustAccountNumber)
                    && notBlank(apiKey)
                    && notBlank(signingSecret);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }

        @Override
        public String toString() {
            return "CustodianCredentials[institution=" + institutionCode + ", redacted]";
        }
    }

    private final URI baseUri;
    private final Credentials credentials;
    private final ObjectMapper json;
    private final HttpClient client;
    private final OutboundUrlGuard urlGuard;

    @Autowired
    public CustodianBankHostToHostClient(
            ObjectMapper json,
            @Value("${elepay.custodian.h2h.base-url:}") String baseUrl,
            @Value("${elepay.custodian.h2h.allowed-hosts:}") String allowedHostsCsv,
            @Value("${elepay.custodian.h2h.institution-code:}") String institutionCode,
            @Value("${elepay.custodian.h2h.trust-account-number:}") String trustAccountNumber,
            @Value("${elepay.custodian.h2h.api-key:}") String apiKey,
            @Value("${elepay.custodian.h2h.signing-secret:}") String signingSecret,
            OutboundUrlGuard urlGuard
    ) {
        this(
                json,
                productionBase(baseUrl, parseAllowedHosts(allowedHostsCsv)),
                new Credentials(institutionCode, trustAccountNumber, apiKey, signingSecret),
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                urlGuard != null ? urlGuard : new OutboundUrlGuard(false, false)
        );
    }

    public CustodianBankHostToHostClient(
            ObjectMapper json,
            URI baseUri,
            Credentials credentials,
            HttpClient client
    ) {
        this(json, baseUri, credentials, client, null);
    }

    public CustodianBankHostToHostClient(
            ObjectMapper json,
            URI baseUri,
            Credentials credentials,
            HttpClient client,
            OutboundUrlGuard urlGuard
    ) {
        this.json = Objects.requireNonNull(json, "json is required");
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri is required");
        this.credentials = Objects.requireNonNull(credentials, "credentials is required");
        this.client = client != null
                ? client
                : HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        this.urlGuard = urlGuard;
    }

    public static Set<String> parseAllowedHosts(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        java.util.Set<String> hosts = new java.util.LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                hosts.add(trimmed);
            }
        }
        return Set.copyOf(hosts);
    }

    /**
     * Validates that the configured Custodian Bank URL is HTTPS on port 443/8443, targets an
     * explicitly configured custodian host in {@code allowedHosts}, and carries no userinfo,
     * base path, query string, or fragment. Fails closed if {@code allowedHosts} is empty.
     */
    public static URI productionBase(String url, Set<String> allowedHosts) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            throw new IllegalArgumentException(
                    "elepay.custodian.h2h.allowed-hosts must be configured with your contracted Custodian Bank host(s)");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(
                    "elepay.custodian.h2h.base-url is required when elepay.custodian.h2h.enabled=true");
        }
        URI u = URI.create(url.trim());
        int port = u.getPort();
        boolean validPort = port == -1 || port == 443 || port == 8443;
        boolean validPath = u.getPath() == null || u.getPath().isEmpty() || "/".equals(u.getPath());
        if (!"https".equalsIgnoreCase(u.getScheme())
                || u.getHost() == null
                || !allowedHosts.contains(u.getHost().toLowerCase(Locale.ROOT))
                || !validPort
                || !validPath
                || u.getUserInfo() != null
                || u.getQuery() != null
                || u.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Custodian Bank base URL must be HTTPS to an allowlisted custodian host "
                            + allowedHosts + ", got: " + url);
        }
        String hostLower = u.getHost().toLowerCase(Locale.ROOT);
        if (isForbiddenHostLiteral(hostLower)) {
            throw new IllegalArgumentException(
                    "Custodian Bank base URL must not target loopback, private, link-local, or internal hosts: " + url);
        }
        return u;
    }

    private static boolean isForbiddenHostLiteral(String hostLower) {
        String stripped = (hostLower.startsWith("[") && hostLower.endsWith("]"))
                ? hostLower.substring(1, hostLower.length() - 1)
                : hostLower;
        if (stripped.equals("localhost")
                || stripped.endsWith(".localhost")
                || stripped.endsWith(".internal")
                || stripped.endsWith(".local")) {
            return true;
        }
        boolean looksLikeIpLiteral = stripped.contains(":")
                || stripped.matches("^(0x[0-9a-f]+|[0-9]+)(\\.(0x[0-9a-f]+|[0-9]+))*$");
        if (!looksLikeIpLiteral) {
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByName(stripped);
            return !OutboundUrlGuard.isPublic(addr);
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public TrustBalanceSnapshot fetchTrustBalance(String trustAccountCode, String currency) {
        requireValidCredentials();
        String cur = (currency == null || currency.isBlank()) ? "GHS" : currency.trim().toUpperCase(Locale.ROOT);
        String path = "/v1/trust-accounts/"
                + URLEncoder.encode(credentials.trustAccountNumber(), StandardCharsets.UTF_8)
                + "/balance?currency=" + URLEncoder.encode(cur, StandardCharsets.UTF_8);

        HttpRequest req = signedRequest("GET", path, "", null);
        HttpResponse<String> resp = send(req, "fetchTrustBalance");
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Custodian Bank balance query failed with HTTP " + resp.statusCode());
        }
        try {
            JsonNode root = json.readTree(resp.body());
            long available = root.path("availableBalancePesewas").asLong(-1L);
            long ledgerBal = root.path("ledgerBalancePesewas").asLong(available);
            if (available < 0 || ledgerBal < 0) {
                throw new IllegalStateException("Custodian Bank returned invalid negative/missing balance");
            }
            String asOfRaw = root.path("asOf").asText("");
            Instant asOf = asOfRaw.isBlank() ? Instant.now() : Instant.parse(asOfRaw);
            String stmtRef = root.path("statementReference").asText(null);
            return new TrustBalanceSnapshot(trustAccountCode, cur, available, ledgerBal, asOf, stmtRef);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Custodian Bank balance response", e);
        }
    }

    @Override
    public DisbursementResponse initiateDisbursement(DisbursementInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction is required");
        requireValidCredentials();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("idempotencyKey", instruction.idempotencyKey());
        payload.put("sourceTrustAccount", credentials.trustAccountNumber());
        payload.put("trustAccountCode", instruction.trustAccountCode());
        payload.put("amountPesewas", instruction.amountPesewas());
        payload.put("currency", instruction.currency());
        payload.put("destinationType", instruction.destinationType());
        payload.put("destinationBankOrNetwork", instruction.destinationBankOrNetwork());
        payload.put("destinationAccountNumber", instruction.destinationAccountNumber());
        payload.put("destinationAccountName", instruction.destinationAccountName());
        payload.put("purpose", instruction.purpose());
        payload.put("narration", instruction.narration());

        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize disbursement instruction", e);
        }

        HttpRequest req = signedRequest("POST", "/v1/disbursements", body, instruction.idempotencyKey());
        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return parseDisbursementResponse(resp, false);
        } catch (HttpTimeoutException e) {
            log.warn("Custodian Bank disbursement timed out for key={}; returning UNKNOWN (fail-closed)",
                    instruction.idempotencyKey());
            return new DisbursementResponse(DisbursementStatus.UNKNOWN, null, "Custodian Bank request timed out");
        } catch (IOException e) {
            log.warn("Custodian Bank disbursement I/O error for key={}: {}; returning UNKNOWN (fail-closed)",
                    instruction.idempotencyKey(), e.getMessage());
            return new DisbursementResponse(DisbursementStatus.UNKNOWN, null, "Custodian Bank network error");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DisbursementResponse(DisbursementStatus.UNKNOWN, null, "Custodian Bank request interrupted");
        }
    }

    @Override
    public DisbursementResponse queryDisbursement(String idempotencyKey, String bankReference) {
        requireValidCredentials();
        String lookup = (bankReference != null && !bankReference.isBlank()) ? bankReference : idempotencyKey;
        if (lookup == null || lookup.isBlank()) {
            throw new IllegalArgumentException("Either idempotencyKey or bankReference is required");
        }
        String path = "/v1/disbursements/" + URLEncoder.encode(lookup, StandardCharsets.UTF_8);
        HttpRequest req = signedRequest("GET", path, "", null);
        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                return new DisbursementResponse(DisbursementStatus.UNKNOWN, bankReference, "Disbursement not found yet");
            }
            return parseDisbursementResponse(resp, true);
        } catch (IOException e) {
            return new DisbursementResponse(DisbursementStatus.UNKNOWN, bankReference, "Custodian Bank status query failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DisbursementResponse(DisbursementStatus.UNKNOWN, bankReference, "Custodian Bank status query interrupted");
        }
    }

    private DisbursementResponse parseDisbursementResponse(HttpResponse<String> resp, boolean isQuery) {
        int code = resp.statusCode();
        // Ambiguous HTTP statuses (5xx, 408 Request Timeout, 409 Conflict, 429 Too Many Requests,
        // 401/403 auth/WAF errors, and ALL non-2xx on status queries) MUST fail closed to UNKNOWN
        // so funds are never released or double-disbursed.
        if (code >= 500 || code == 408 || code == 409 || code == 429 || code == 401 || code == 403) {
            return new DisbursementResponse(DisbursementStatus.UNKNOWN, null, "Custodian Bank HTTP " + code);
        }
        if (isQuery && (code < 200 || code >= 300)) {
            return new DisbursementResponse(DisbursementStatus.UNKNOWN, null, "Custodian Bank query HTTP " + code);
        }
        JsonNode root;
        try {
            root = (resp.body() == null || resp.body().isBlank()) ? null : json.readTree(resp.body());
        } catch (Exception e) {
            return new DisbursementResponse(DisbursementStatus.UNKNOWN, null, "Unparseable Custodian Bank response");
        }
        String bankRef = root == null ? null : root.path("bankReference").asText(null);
        String msg = root == null ? ("HTTP " + code) : root.path("message").asText("HTTP " + code);
        if (code == 400 || code == 422) {
            return new DisbursementResponse(DisbursementStatus.REJECTED, bankRef, msg);
        }
        if (code < 200 || code >= 300) {
            return new DisbursementResponse(DisbursementStatus.UNKNOWN, bankRef, msg);
        }
        String statusStr = root == null ? "" : root.path("status").asText("").trim().toUpperCase(Locale.ROOT);
        DisbursementStatus status = switch (statusStr) {
            case "SETTLED", "COMPLETED", "SUCCEEDED" -> DisbursementStatus.SETTLED;
            case "ACCEPTED", "PENDING", "PROCESSING" -> DisbursementStatus.ACCEPTED;
            case "REJECTED", "FAILED", "DECLINED" -> DisbursementStatus.REJECTED;
            default -> DisbursementStatus.UNKNOWN;
        };
        Long amountPesewas = parseOptionalAmount(root);
        String currency = parseOptionalCurrency(root);
        return new DisbursementResponse(status, bankRef, msg, amountPesewas, currency);
    }

    private static Long parseOptionalAmount(JsonNode root) {
        if (root == null) return null;
        JsonNode amtNode = root.get("amountPesewas");
        if (amtNode == null || amtNode.isNull()) return null;
        if (amtNode.isIntegralNumber()) {
            return amtNode.asLong();
        }
        if (amtNode.isTextual()) {
            String raw = amtNode.asText("").trim();
            if (!raw.isEmpty()) {
                try {
                    return Long.parseLong(raw);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String parseOptionalCurrency(JsonNode root) {
        if (root == null) return null;
        JsonNode curNode = root.get("currency");
        if (curNode == null || curNode.isNull() || !curNode.isTextual()) return null;
        String c = curNode.asText("").trim().toUpperCase(Locale.ROOT);
        return c.isEmpty() ? null : c;
    }

    private HttpRequest signedRequest(String method, String path, String body, String idempotencyKey) {
        String base = baseUri.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String fullUrl = base + path;
        if (urlGuard != null) {
            urlGuard.validatePartnerEgress(fullUrl);
        }
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = sign(timestamp, method, path, idempotencyKey, body, credentials.signingSecret());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("X-Custodian-Institution", credentials.institutionCode())
                .header("X-Custodian-Api-Key", credentials.apiKey())
                .header("X-Custodian-Timestamp", timestamp)
                .header("X-Custodian-Signature", signature);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            builder.header("X-Idempotency-Key", idempotencyKey);
        }
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    private HttpResponse<String> send(HttpRequest req, String op) {
        try {
            return client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Custodian Bank " + op + " network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Custodian Bank " + op + " interrupted", e);
        }
    }

    private void requireValidCredentials() {
        if (!credentials.isValid()) {
            throw new IllegalStateException(
                    "Custodian Bank H2H credentials are incomplete; set institution-code, trust-account-number, api-key, and signing-secret");
        }
    }

    /** Deterministic HMAC-SHA256 request signature over {@code timestamp.method.path.idempotencyKey.body}. */
    public static String sign(String timestamp, String method, String path, String body, String secret) {
        return sign(timestamp, method, path, "", body, secret);
    }

    /** Deterministic HMAC-SHA256 request signature binding {@code X-Idempotency-Key}. */
    public static String sign(
            String timestamp,
            String method,
            String path,
            String idempotencyKey,
            String body,
            String secret
    ) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String idem = idempotencyKey == null ? "" : idempotencyKey;
            String canonical = timestamp + "." + method + "." + path + "." + idem + "." + (body == null ? "" : body);
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 signing failed", e);
        }
    }
}
