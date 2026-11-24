package com.elepay.common;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-time outbound incident alert dispatcher (Slack / PagerDuty / Telegram / ops webhook).
 * Fires asynchronously on high/critical OpsQueue events so ledger drift, hash-chain tamper,
 * custodian shortfalls, or overdue key rotations wake up operators immediately without ever
 * blocking or failing a database transaction.
 */
@Service
public class AlertDispatcher {
    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final String webhookUrl;
    private final OutboundUrlGuard urlGuard;
    private final ObjectMapper json;
    private final HttpClient http;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "elepay-alert-dispatcher");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public AlertDispatcher(
            @Value("${elepay.alerts.webhook-url:}") String webhookUrl,
            @Autowired(required = false) OutboundUrlGuard urlGuard,
            ObjectMapper json) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.urlGuard = urlGuard;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean isConfigured() {
        return !webhookUrl.isBlank();
    }

    public void notifyHighSeverity(String kind, String subject, Map<String, ?> details) {
        log.error("HIGH_SEVERITY_ALERT kind={} subject={} details={}", kind, subject, details);
        if (!isConfigured()) {
            return;
        }
        executor.submit(() -> sendAlert(kind, subject, details));
    }

    void sendAlert(String kind, String subject, Map<String, ?> details) {
        try {
            if (urlGuard != null) {
                urlGuard.validate(webhookUrl);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("service", "elepay-core");
            payload.put("severity", "HIGH");
            payload.put("kind", kind);
            payload.put("subject", subject);
            payload.put("details", details != null ? details : Map.of());
            payload.put("timestamp", Instant.now().toString());
            // Also include Slack-compatible "text" summary so Slack/Teams webhooks work out of the box
            payload.put("text", "[ELEPAY ALERT] " + kind + " (" + subject + ")");

            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "ELEPAY-Core/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 400) {
                log.warn("Alert webhook returned HTTP {} for kind={}", resp.statusCode(), kind);
            }
        } catch (Exception e) {
            log.warn("Failed to deliver alert webhook for kind={}: {}", kind, e.getMessage());
        }
    }
}
