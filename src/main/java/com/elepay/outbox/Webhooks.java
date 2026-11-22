package com.elepay.outbox;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * Merchant webhooks. Fan-out happens as an outbox consumer (one delivery row
 * per endpoint, deduplicated by primary key); a separate sender retries with
 * exponential backoff. Each request carries the event id and an HMAC-SHA256
 * signature: {@code Elepay-Signature: t=<unix>,v1=<hex(hmac(secret, t + "." + body))>}.
 * Receivers must deduplicate by event id because delivery is at least once.
 */
@Component
public class Webhooks implements OutboxConsumer {
    private static final Logger log = LoggerFactory.getLogger(Webhooks.class);
    private static final int MAX_ATTEMPTS = 12;

    private final JdbcClient db;
    private final RestClient http;
    private final org.apache.hc.client5.http.impl.classic.CloseableHttpClient transport;
    private final boolean enabled;

    private final com.elepay.common.OutboundUrlGuard guard;

    Webhooks(JdbcClient db, @Value("${elepay.outbox.enabled:true}") boolean enabled,
             com.elepay.common.OutboundUrlGuard guard) {
        this.db = db;
        this.enabled = enabled;
        this.guard = guard;
        var dns = new org.apache.hc.client5.http.DnsResolver() {
            @Override public java.net.InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                return guard.resolveForConnection(host);
            }
            @Override public String resolveCanonicalHostname(String host) throws java.net.UnknownHostException {
                guard.resolveForConnection(host);
                return host; // Keep original TLS hostname, never substitute an unvalidated canonical destination.
            }
        };
        var manager = org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dns)
                .setDefaultConnectionConfig(org.apache.hc.client5.http.config.ConnectionConfig.custom()
                        .setConnectTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(5))
                        .setSocketTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(10)).build()).build();
        this.transport = org.apache.hc.client5.http.impl.classic.HttpClients.custom()
                .setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .setDefaultRequestConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
                        .setConnectionRequestTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(5))
                        .setResponseTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(10)).build()).build();
        this.http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.HttpComponentsClientHttpRequestFactory(transport)).build();
    }

    @jakarta.annotation.PreDestroy
    void closeTransport() throws java.io.IOException { transport.close(); }

    /** Shared guarded transport for scheduled deliveries and developer operations. */
    public int sendSigned(String url, UUID event, String signature, String body) {
        guard.validate(url);
        try {
            return http.post().uri(java.net.URI.create(url.trim()))
                    .header("Content-Type", "application/json")
                    .header("Elepay-Event-Id", event.toString())
                    .header("Elepay-Signature", signature)
                    .body(body).retrieve().toBodilessEntity().getStatusCode().value();
        } catch (org.springframework.web.client.RestClientResponseException e) {
            return e.getStatusCode().value();
        }
    }

    public record Endpoint(UUID id, String url, String secret, boolean active) {}

    @Transactional
    public Endpoint register(UUID merchantId, String url) {
        guard.validate(url);
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        var endpoint = new Endpoint(UUID.randomUUID(), url, "whsec_" + HexFormat.of().formatHex(bytes), true);
        db.sql("INSERT INTO webhook_endpoint (id, merchant_id, url, secret) VALUES (:id, :m, :url, :secret)")
                .param("id", endpoint.id()).param("m", merchantId).param("url", url).param("secret", endpoint.secret()).update();
        return endpoint;
    }

    // ---- developer portal: endpoint management --------------------------------------------------

    public record EndpointView(UUID id, String url, String secret, String environment, String description,
                               List<String> eventTypes, boolean active, int failureCount,
                               java.time.OffsetDateTime lastDeliveryAt, java.time.OffsetDateTime createdAt) {}

    public void registerEndpoint(UUID id, UUID merchantId, String url, String secret, String environment,
                                 String description, List<String> eventTypes) {
        guard.validate(url);
        db.sql("""
                INSERT INTO webhook_endpoint (id, merchant_id, url, secret, active, environment, description, event_types)
                VALUES (:id, :m, :url, :secret, true, :env, :desc, :events)""")
                .param("id", id).param("m", merchantId).param("url", url).param("secret", secret)
                .param("env", environment).param("desc", description)
                .param("events", eventTypes.toArray(String[]::new)).update();
    }

    private static EndpointView endpointView(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new EndpointView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), List.of((String[]) rs.getArray(6).getArray()), rs.getBoolean(7), rs.getInt(8),
                rs.getObject(9, java.time.OffsetDateTime.class), rs.getObject(10, java.time.OffsetDateTime.class));
    }

    private static final String ENDPOINT_COLUMNS = """
            SELECT id, url, secret, COALESCE(environment, 'live'), description,
                   COALESCE(event_types, '{checkout.paid,payment.failed,payout.completed,refund.created,mandate.active}'),
                   active, COALESCE(failure_count, 0), last_delivery_at, created_at
            FROM webhook_endpoint""";

    public List<EndpointView> endpoints(UUID merchantId, String environment) {
        boolean filter = environment != null && !environment.isBlank();
        var client = db.sql(ENDPOINT_COLUMNS + " WHERE merchant_id = :m"
                + (filter ? " AND COALESCE(environment, 'live') = :env" : "") + " ORDER BY created_at DESC")
                .param("m", merchantId);
        if (filter) client = client.param("env", environment.toLowerCase());
        return client.query(Webhooks::endpointView).list();
    }

    public java.util.Optional<EndpointView> endpoint(UUID merchantId, UUID id) {
        return db.sql(ENDPOINT_COLUMNS + " WHERE id = :id AND merchant_id = :m")
                .param("id", id).param("m", merchantId).query(Webhooks::endpointView).optional();
    }

    public boolean updateEndpoint(UUID merchantId, UUID id, String url, List<String> eventTypes, Boolean active,
                                  String description) {
        if (url != null) guard.validate(url);
        StringBuilder sql = new StringBuilder("UPDATE webhook_endpoint SET updated_at = now() ");
        if (url != null) sql.append(", url = :url ");
        if (eventTypes != null) sql.append(", event_types = :events ");
        if (active != null) sql.append(", active = :active ");
        if (description != null) sql.append(", description = :desc ");
        sql.append(" WHERE id = :id AND merchant_id = :m");
        var client = db.sql(sql.toString()).param("id", id).param("m", merchantId);
        if (url != null) client = client.param("url", url);
        if (eventTypes != null) client = client.param("events", eventTypes.toArray(String[]::new));
        if (active != null) client = client.param("active", active);
        if (description != null) client = client.param("desc", description);
        return client.update() > 0;
    }

    public boolean setSecret(UUID merchantId, UUID id, String secret) {
        return db.sql("UPDATE webhook_endpoint SET secret = :secret, updated_at = now() WHERE id = :id AND merchant_id = :m")
                .param("secret", secret).param("id", id).param("m", merchantId).update() > 0;
    }

    /** Why ELEPAY refuses to call this URL, or null when it may. */
    public String destinationProblem(String url) {
        return guard.problem(url);
    }

    /** Health counters shown in the developer portal. */
    public void recordDeliveryOutcome(UUID endpointId, boolean delivered) {
        db.sql(delivered
                        ? "UPDATE webhook_endpoint SET failure_count = 0, last_delivery_at = now() WHERE id = :id"
                        : "UPDATE webhook_endpoint SET failure_count = failure_count + 1 WHERE id = :id")
                .param("id", endpointId).update();
    }

    public List<Endpoint> list(UUID merchantId) {
        return db.sql("SELECT id, url, '' AS secret, active FROM webhook_endpoint WHERE merchant_id = :m ORDER BY created_at")
                .param("m", merchantId)
                .query((rs, i) -> new Endpoint(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBoolean(4)))
                .list();
    }

    @Override public String name() { return "webhook-fanout"; }

    @Override public boolean accepts(String eventType) { return true; }

    @Override
    public void handle(Outbox.Event event) {
        if (event.merchantIds().length == 0) return;
        db.sql("""
                INSERT INTO webhook_delivery (event_id, endpoint_id)
                SELECT :event, id FROM webhook_endpoint WHERE active AND environment = 'live' AND merchant_id = ANY(:merchants)
                ON CONFLICT DO NOTHING""")
                .param("event", event.id()).param("merchants", event.merchantIds()).update();
    }

    @Scheduled(fixedDelayString = "${elepay.outbox.poll-ms:1000}")
    void send() {
        if (enabled) sendBatch(50);
    }

    @Transactional
    public int sendBatch(int limit) {
        record Pending(UUID event, UUID endpoint, String url, String secret, String body, int attempts) {}
        List<Pending> batch = db.sql("""
                SELECT d.event_id, d.endpoint_id, w.url, w.secret, e.payload::text, d.attempts
                FROM webhook_delivery d JOIN webhook_endpoint w ON w.id = d.endpoint_id
                JOIN outbox_event e ON e.id = d.event_id
                WHERE d.status = 'pending' AND w.active AND w.environment = 'live' AND d.next_attempt_at <= now()
                ORDER BY d.next_attempt_at LIMIT :limit FOR UPDATE OF d SKIP LOCKED""")
                .param("limit", limit)
                .query((rs, i) -> new Pending(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)))
                .list();
        int delivered = 0;
        for (Pending p : batch) {
            Integer status = null;
            String error = null;
            String unsafe = guard.problem(p.url());
            if (unsafe != null) {
                error = "Blocked destination: " + unsafe;
            } else try {
                long t = System.currentTimeMillis() / 1000;
                status = http.post().uri(java.net.URI.create(p.url().trim()))
                        .header("Content-Type", "application/json")
                        .header("Elepay-Event-Id", p.event().toString())
                        .header("Elepay-Signature", "t=" + t + ",v1=" + sign(p.secret(), t + "." + p.body()))
                        .body(p.body())
                        .retrieve().toBodilessEntity().getStatusCode().value();
            } catch (org.springframework.web.client.RestClientResponseException e) {
                status = e.getStatusCode().value();
                error = "Receiver returned an error";
            } catch (RuntimeException e) {
                error = "Webhook transport failed: " + e.getClass().getSimpleName();
            }
            boolean ok = status != null && status >= 200 && status < 300;
            if (ok) delivered++;
            String next = ok ? "delivered" : (p.attempts() + 1 >= MAX_ATTEMPTS ? "dead" : "pending");
            db.sql("""
                    UPDATE webhook_delivery SET status = :status, attempts = attempts + 1, last_status = :code,
                           last_error = :err, delivered_at = CASE WHEN :ok THEN now() END,
                           next_attempt_at = now() + make_interval(secs => least(3600, 5 * power(2, attempts)))
                    WHERE event_id = :e AND endpoint_id = :w""")
                    .param("status", next).param("code", status).param("err", error).param("ok", ok)
                    .param("e", p.event()).param("w", p.endpoint()).update();
            if (!ok) log.info("Webhook {} endpoint {} failed: {} {}", p.event(), p.endpoint(), status, error);
        }
        return delivered;
    }

    /** Redirects are never followed: a public URL must not bounce a delivery to an internal address. */
    public static SimpleClientHttpRequestFactory noRedirects() {
        return new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod)
                    throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
    }

    public static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Verifies an incoming Elepay-Signature header against the raw body using constant-time comparison
     * and replay window validation.
     * Header format: {@code Elepay-Signature: t=<unix>,v1=<hex(hmac(secret, t + "." + body))>}
     */
    public static boolean verifySignature(String secret, String signatureHeader, String rawBody) {
        return verifySignature(secret, signatureHeader, rawBody, com.elepay.common.HmacVerification.DEFAULT_TOLERANCE_SECONDS);
    }

    public static boolean verifySignature(String secret, String signatureHeader, String rawBody, long toleranceSeconds) {
        if (secret == null || signatureHeader == null || rawBody == null) return false;
        String[] parts = signatureHeader.split(",");
        String timestampStr = null;
        String signatureHex = null;
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith("t=")) {
                timestampStr = trimmed.substring(2);
            } else if (trimmed.startsWith("v1=")) {
                signatureHex = trimmed.substring(3);
            }
        }
        if (timestampStr == null || signatureHex == null) return false;
        try {
            long timestampSeconds = Long.parseLong(timestampStr);
            return com.elepay.common.HmacVerification.verifyWithTimestamp(secret, timestampSeconds, rawBody, signatureHex,
                    java.time.Instant.now(), toleranceSeconds);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
