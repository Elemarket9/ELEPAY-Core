package com.elepay.rails;

import com.elepay.common.Idempotency;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Simulated rail for the sandbox and automated tests. It moves no real
 * money; every record it touches is labelled with rail {@code sandbox}.
 * Outcomes are selected by the last digit of the MSISDN or account number:
 * <ul>
 *   <li>{@code 1} - declined (FAILED)</li>
 *   <li>{@code 2} - submission times out (UNKNOWN) although the rail succeeded</li>
 *   <li>{@code 3} - stays pending until a callback or {@link #settle} is triggered</li>
 *   <li>{@code 4} - submission times out and the rail never received it (NOT_FOUND)</li>
 *   <li>anything else - accepted as pending, succeeds on the first status query or callback</li>
 * </ul>
 * Callbacks are signed: hex HMAC-SHA512 of the body in {@code x-sandbox-signature}.
 */
@Component
@ConditionalOnProperty(name = "elepay.rails.sandbox.enabled", havingValue = "true")
public class SimulatedRail implements PaymentRail {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final String secret;
    private final String baseUrl;

    SimulatedRail(JdbcClient db, ObjectMapper json,
                      @Value("${elepay.rails.sandbox.webhook-secret:sandbox-webhook-secret}") String secret,
                      @Value("${elepay.public-base-url:http://127.0.0.1:8080}") String baseUrl) {
        this.db = db;
        this.json = json;
        this.secret = secret;
        this.baseUrl = baseUrl;
    }

    @Override public String name() { return "sandbox"; }

    @Override public boolean callbacksAreAuthoritative() { return true; }

    private static String outcome(String account) {
        if (account == null || account.isEmpty()) return "ok";
        return switch (account.charAt(account.length() - 1)) {
            case '1' -> "decline";
            case '2' -> "timeout_succeeded";
            case '3' -> "hold";
            case '4' -> "lost";
            default -> "ok";
        };
    }

    private Result record(String direction, String reference, long amount, String account, String channel) {
        String outcome = "card".equals(channel) ? "hold" : outcome(account);
        if (outcome.equals("lost")) return Result.unknown("Simulated timeout before rail received request");
        String status = switch (outcome) {
            case "decline" -> "failed";
            case "timeout_succeeded", "ok" -> "succeeded";
            default -> "pending";
        };
        db.sql("""
                INSERT INTO simulated_rail_txn (reference, direction, amount, msisdn, outcome, status)
                VALUES (:r, :d, :a, :m, :o, :s) ON CONFLICT (reference) DO NOTHING""")
                .param("r", reference).param("d", direction).param("a", amount).param("m", account)
                .param("o", outcome).param("s", status).update();
        if (outcome.equals("timeout_succeeded")) return Result.unknown("Simulated timeout after rail accepted request");
        if (outcome.equals("decline")) return new Result(Status.FAILED, "SIM-" + reference, amount, "GHS", 0L, null, "Declined by simulated rail");
        String redirect = "card".equals(channel) ? baseUrl + "/v1/public/sandbox-rail/authorize/" + reference : null;
        return new Result(Status.PENDING, "SIM-" + reference, null, null, null, redirect, "Awaiting approval");
    }

    @Override
    public Result charge(Charge charge) {
        return record("charge", charge.reference(), charge.amount(), charge.msisdn(), charge.channel());
    }

    @Override
    public Result payout(Payout payout) {
        return record("payout", payout.reference(), payout.amount(), payout.accountNumber(), "momo");
    }

    @Override
    public Result refund(Refund refund) {
        return record("refund", refund.reference(), refund.amount(), refund.msisdn(), "momo");
    }

    @Override
    public Result query(String direction, String reference, String railReference) {
        return db.sql("SELECT status, amount FROM simulated_rail_txn WHERE reference = :r")
                .param("r", reference)
                .query((rs, i) -> switch (rs.getString(1)) {
                    case "succeeded" -> new Result(Status.SUCCEEDED, "SIM-" + reference, rs.getLong(2), "GHS", 0L, null, null);
                    case "failed" -> new Result(Status.FAILED, "SIM-" + reference, rs.getLong(2), "GHS", 0L, null, "Declined");
                    default -> new Result(Status.PENDING, "SIM-" + reference, null, null, null, null, null);
                })
                .optional().orElse(new Result(Status.NOT_FOUND, null, null, null, null, null, "Unknown reference"));
    }

    /** Marks a held transaction as final at the simulated rail (customer approved or declined). */
    public void settle(String reference, boolean success) {
        db.sql("UPDATE simulated_rail_txn SET status = :s WHERE reference = :r AND status = 'pending'")
                .param("s", success ? "succeeded" : "failed").param("r", reference).update();
    }

    /** Builds a signed callback body for the given reference, as the rail would send it. */
    public Map.Entry<String, String> callback(String reference, String status, Long amountOverride) {
        long amount = amountOverride != null ? amountOverride : db.sql("SELECT amount FROM simulated_rail_txn WHERE reference = :r")
                .param("r", reference).query(Long.class).optional().orElse(0L);
        String body = json.writeValueAsString(Map.of(
                "event", "transaction." + status,
                "data", Map.of("reference", reference, "status", status, "amount", amount, "currency", "GHS")));
        return Map.entry(sign(body), body);
    }

    String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Callback verifyCallback(Map<String, String> headers, String body) {
        String signature = headers.get("x-sandbox-signature");
        if (signature == null || !java.security.MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8), sign(body).getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidCallbackException("Bad signature");
        }
        JsonNode node = json.readTree(body);
        JsonNode data = node.path("data");
        String status = data.path("status").asString();
        Status mapped = switch (status) {
            case "succeeded", "success" -> Status.SUCCEEDED;
            case "failed" -> Status.FAILED;
            default -> Status.PENDING;
        };
        String reference = data.path("reference").asString();
        return new Callback(Idempotency.sha256(body), node.path("event").asString(), null, reference, null,
                new Result(mapped, "SIM-" + reference, data.path("amount").asLong(), data.path("currency").asString(),
                        0L, null, status));
    }
}
