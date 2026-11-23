package com.elepay.fx;

import com.elepay.common.Idempotency;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Simulated remittance partner {@code sim-remit} for the sandbox and tests.
 * It moves no money and is never a partner-test or live result. Rates come
 * from configuration. Recipient accounts ending in 1 fail; others are paid
 * when the partner settles them or on the first status query.
 */
@Component
@ConditionalOnProperty(name = "elepay.rails.sandbox.enabled", havingValue = "true")
public class SimulatedFxPartner implements FxPartner {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final String secret;
    private final BigDecimal usdGhs;
    private final BigDecimal ghsUsd;

    SimulatedFxPartner(JdbcClient db, ObjectMapper json,
                       @Value("${elepay.fx.sandbox.webhook-secret:sim-remit-secret}") String secret,
                       @Value("${elepay.fx.sandbox.rate.USD-GHS:15.20}") BigDecimal usdGhs,
                       @Value("${elepay.fx.sandbox.rate.GHS-USD:0.0640}") BigDecimal ghsUsd) {
        this.db = db;
        this.json = json;
        this.secret = secret;
        this.usdGhs = usdGhs;
        this.ghsUsd = ghsUsd;
    }

    @Override public String name() { return "sim-remit"; }

    @Override
    public Quote quote(String sourceCurrency, String destinationCurrency, long sourceAmount) {
        BigDecimal rate = switch (sourceCurrency + "-" + destinationCurrency) {
            case "USD-GHS" -> usdGhs;
            case "GHS-USD" -> ghsUsd;
            default -> throw new IllegalArgumentException("Unsupported pair");
        };
        String ref = "SQ-" + UUID.randomUUID();
        db.sql("INSERT INTO simulated_fx_partner (reference, quote_ref, status) VALUES (:r, :r, 'quoted')")
                .param("r", ref).update();
        return new Quote(ref, rate, OffsetDateTime.now().plusMinutes(10));
    }

    @Override
    public boolean honours(String quoteReference) {
        return db.sql("SELECT status = 'quoted' FROM simulated_fx_partner WHERE reference = :r").param("r", quoteReference)
                .query(Boolean.class).optional().orElse(false);
    }

    /** Test hook: the partner withdraws a quote (for example after a market move). */
    public void withdraw(String quoteReference) {
        db.sql("UPDATE simulated_fx_partner SET status = 'withdrawn' WHERE reference = :r").param("r", quoteReference).update();
    }

    @Override
    public Execution query(String reference) {
        return db.sql("SELECT status FROM simulated_fx_partner WHERE reference = :r").param("r", reference)
                .query(String.class).optional().map(s -> switch (s) {
                    case "pending:paid", "paid" -> new Execution(Status.PAID, "SIMFX-" + reference, null);
                    case "pending:failed", "failed" -> new Execution(Status.FAILED, "SIMFX-" + reference, "Rejected by partner");
                    case "returned" -> new Execution(Status.RETURNED, "SIMFX-" + reference, "Returned by beneficiary bank");
                    default -> new Execution(Status.PENDING, "SIMFX-" + reference, null);
                }).orElse(new Execution(Status.NOT_FOUND, null, "Unknown reference"));
    }

    public void markReturned(String reference) {
        db.sql("UPDATE simulated_fx_partner SET status = 'returned' WHERE reference = :r").param("r", reference).update();
    }

    public Map.Entry<String, String> signed(Map<String, ?> payload) {
        String body = json.writeValueAsString(payload);
        return Map.entry(sign(body), body);
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void check(Map<String, String> headers, String body) {
        String signature = headers.get("x-partner-signature");
        if (signature == null || !java.security.MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8),
                sign(body).getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Invalid partner signature");
        }
    }

    @Override
    public Notification verify(Map<String, String> headers, String body) {
        check(headers, body);
        JsonNode node = json.readTree(body);
        Status status = switch (node.path("status").asString("")) {
            case "paid" -> Status.PAID;
            case "failed" -> Status.FAILED;
            case "returned" -> Status.RETURNED;
            default -> Status.PENDING;
        };
        return new Notification(Idempotency.sha256(body), node.path("reference").asString(), status,
                node.path("message").asString(null));
    }

}
