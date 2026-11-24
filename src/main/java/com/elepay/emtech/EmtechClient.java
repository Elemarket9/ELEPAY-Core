package com.elepay.emtech;

import com.elepay.common.OutboundUrlGuard;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Posts one Emtech object. Tokens are cached until shortly before {@code expiryMS}.
 * The auth header is {@code x-sandbox-app-auth}, not {@code Authorization}.
 * 5xx and 429 are retried with backoff. 400 is not. A 401 code 002 refreshes the token once.
 */
@Component
public class EmtechClient {
    private static final Logger log = LoggerFactory.getLogger(EmtechClient.class);
    public static final String AUTH_HEADER = "x-sandbox-app-auth";

    public enum Outcome { SUBMITTED, REJECTED, RETRY }

    public record SendResult(Outcome outcome, int httpStatus, String code, String message) {}

    private final EmtechSettings settings;
    private final OutboundUrlGuard guard;
    private final ObjectMapper json;
    private final EmtechSchemaValidator validator;
    private final RestClient http;
    private final CloseableHttpClient transport;

    private volatile String token;
    private volatile Instant tokenDeadline = Instant.EPOCH;

    public EmtechClient(EmtechSettings settings, OutboundUrlGuard guard, ObjectMapper json) {
        this.settings = settings;
        this.guard = guard;
        this.json = json;
        this.validator = new EmtechSchemaValidator(json);
        Timeout connect = Timeout.of(settings.connectTimeout());
        Timeout read = Timeout.of(settings.readTimeout());
        var dns = new org.apache.hc.client5.http.DnsResolver() {
            @Override public java.net.InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                return guard.resolveForConnection(host);
            }
            @Override public String resolveCanonicalHostname(String host) throws java.net.UnknownHostException {
                guard.resolveForConnection(host);
                return host;
            }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(dns)
                .setDefaultConnectionConfig(org.apache.hc.client5.http.config.ConnectionConfig.custom()
                        .setConnectTimeout(connect).setSocketTimeout(read).build()).build();
        this.transport = HttpClients.custom().setConnectionManager(manager)
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .setDefaultRequestConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
                        .setConnectionRequestTimeout(connect).setResponseTimeout(read).build()).build();
        this.http = RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(transport)).build();
    }

    @jakarta.annotation.PreDestroy
    void closeTransport() throws IOException {
        transport.close();
    }

    public SendResult submit(String resource, JsonNode payload) {
        if (!settings.enabled()) {
            return new SendResult(Outcome.RETRY, 0, "disabled", "Emtech reporting is disabled");
        }
        if (!settings.credentialsPresent()) {
            return new SendResult(Outcome.RETRY, 0, "unconfigured", "Emtech base URL or credentials are not set");
        }
        try {
            validator.validate(schema(resource), payload);
        } catch (EmtechValidationException e) {
            return new SendResult(Outcome.REJECTED, 0, "validation", e.getMessage());
        }
        String path = path(resource);
        guard.validatePartnerEgress(endpoint(path));
        String body = json.writeValueAsString(payload);
        String bearer = token(false);
        boolean refreshed = false;
        int networkTries = 0;
        while (true) {
            HttpResult result = post(path, body, bearer);
            if (result.status() == 200) return new SendResult(Outcome.SUBMITTED, 200, null, null);
            if (result.status() == 401 && !refreshed) {
                bearer = token(true);
                refreshed = true;
                continue;
            }
            if (result.status() == 429 || result.status() >= 500) {
                if (networkTries >= settings.maxRetries()) {
                    return new SendResult(Outcome.RETRY, result.status(), result.code(), result.message());
                }
                networkTries++;
                sleepQuietly(backoff(networkTries, result.retryAfterMs()));
                continue;
            }
            return new SendResult(Outcome.REJECTED, result.status(), result.code(), result.message());
        }
    }

    private long backoff(int attempt, Long retryAfterMs) {
        if (retryAfterMs != null && retryAfterMs > 0) return Math.min(retryAfterMs, 30_000);
        if (settings.retryBaseMs() <= 0) return 0;
        long delay = settings.retryBaseMs() * (1L << Math.min(attempt - 1, 16));
        return Math.min(delay, 30_000);
    }

    private HttpResult post(String path, String body, String bearer) {
        try {
            http.post().uri(URI.create(endpoint(path)))
                    .header("Content-Type", "application/json")
                    .header(AUTH_HEADER, "Bearer " + bearer)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return new HttpResult(200, null, null, null);
        } catch (RestClientResponseException e) {
            return parse(e.getStatusCode().value(), e.getResponseBodyAsString(), retryAfter(e));
        }
    }

    private HttpResult parse(int status, String responseBody, Long retryAfterMs) {
        String code = null;
        String message = "HTTP " + status;
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                JsonNode node = json.readTree(responseBody);
                if (node.hasNonNull("code")) code = node.get("code").asString();
                if (node.hasNonNull("message")) message = node.get("message").asString();
                if (node.path("errorDetails").isArray() && !node.path("errorDetails").isEmpty()) {
                    message = message + " " + node.path("errorDetails");
                }
            } catch (RuntimeException ignored) {
                message = "HTTP " + status;
            }
        }
        return new HttpResult(status, code, redact(message), retryAfterMs);
    }

    private String token(boolean force) {
        if (!force && token != null && Instant.now().isBefore(tokenDeadline)) return token;
        synchronized (this) {
            if (!force && token != null && Instant.now().isBefore(tokenDeadline)) return token;
            String url = endpoint("/v1/auth/token");
            guard.validatePartnerEgress(url);
            String request = json.writeValueAsString(java.util.Map.of(
                    "clientId", settings.clientId(), "clientSecret", settings.clientSecret()));
            String response;
            try {
                response = http.post().uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .body(request)
                        .retrieve()
                        .body(String.class);
            } catch (RestClientResponseException e) {
                throw new IllegalStateException("Emtech token request failed with HTTP " + e.getStatusCode().value());
            }
            JsonNode node = json.readTree(response == null ? "{}" : response);
            String access = node.path("accessToken").asString(null);
            if (access == null || access.isBlank()) {
                throw new IllegalStateException("Emtech token response did not include an access token");
            }
            long expiryMs = node.path("expiryMS").asLong(3_600_000);
            Duration skew = Duration.ofMillis(Math.min(60_000, Math.max(1_000, expiryMs / 10)));
            token = access;
            tokenDeadline = Instant.now().plusMillis(expiryMs).minus(skew);
            log.info("Emtech access token cached until {}", tokenDeadline);
            return access;
        }
    }

    private String endpoint(String path) {
        String base = settings.baseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + path;
    }

    private String redact(String message) {
        if (message == null) return null;
        String cleaned = message;
        if (!settings.clientSecret().isBlank()) cleaned = cleaned.replace(settings.clientSecret(), "[REDACTED]");
        if (!settings.clientId().isBlank()) cleaned = cleaned.replace(settings.clientId(), "[REDACTED]");
        if (token != null) cleaned = cleaned.replace(token, "[REDACTED]");
        return cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned;
    }

    private static Long retryAfter(RestClientResponseException e) {
        String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        if (header == null) return null;
        try {
            return Long.parseLong(header.trim()) * 1000;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Emtech retry interrupted");
        }
    }

    public static String schema(String resource) {
        return switch (resource) {
            case "DEVICE" -> "deviceV2";
            case "TRANSACTION" -> "cryptoPSPTransactionV2";
            case "DEVICE_EVENT" -> "deviceEvent";
            case "COMPLAINT" -> "consumerComplaints";
            default -> throw new IllegalArgumentException("Unknown Emtech resource " + resource);
        };
    }

    public static String path(String resource) {
        return switch (resource) {
            case "DEVICE" -> "/compliance/payment-service/v2/devices";
            case "TRANSACTION" -> "/compliance/payment-service/v2/transactions";
            case "DEVICE_EVENT" -> "/compliance/payment-service/v1/device-events";
            case "COMPLAINT" -> "/compliance/payment-service/v1/consumer-complaints";
            default -> throw new IllegalArgumentException("Unknown Emtech resource " + resource);
        };
    }

    private record HttpResult(int status, String code, String message, Long retryAfterMs) {}
}
