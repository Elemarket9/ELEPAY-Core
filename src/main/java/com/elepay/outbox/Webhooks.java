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
    private final boolean enabled;

    Webhooks(JdbcClient db, @Value("${elepay.outbox.enabled:true}") boolean enabled) {
        this.db = db;
        this.enabled = enabled;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public record Endpoint(UUID id, String url, String secret, boolean active) {}

    @Transactional
    public Endpoint register(UUID merchantId, String url) {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        var endpoint = new Endpoint(UUID.randomUUID(), url, "whsec_" + HexFormat.of().formatHex(bytes), true);
        db.sql("INSERT INTO webhook_endpoint (id, merchant_id, url, secret) VALUES (:id, :m, :url, :secret)")
                .param("id", endpoint.id()).param("m", merchantId).param("url", url).param("secret", endpoint.secret()).update();
        return endpoint;
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
                SELECT :event, id FROM webhook_endpoint WHERE active AND merchant_id = ANY(:merchants)
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
                WHERE d.status = 'pending' AND d.next_attempt_at <= now()
                ORDER BY d.next_attempt_at LIMIT :limit FOR UPDATE OF d SKIP LOCKED""")
                .param("limit", limit)
                .query((rs, i) -> new Pending(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)))
                .list();
        int delivered = 0;
        for (Pending p : batch) {
            Integer status = null;
            String error = null;
            try {
                long t = System.currentTimeMillis() / 1000;
                status = http.post().uri(p.url())
                        .header("Content-Type", "application/json")
                        .header("Elepay-Event-Id", p.event().toString())
                        .header("Elepay-Signature", "t=" + t + ",v1=" + sign(p.secret(), t + "." + p.body()))
                        .body(p.body())
                        .retrieve().toBodilessEntity().getStatusCode().value();
            } catch (RuntimeException e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
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
            if (!ok) log.info("Webhook {} to {} failed: {} {}", p.event(), p.url(), status, error);
        }
        return delivered;
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
}
