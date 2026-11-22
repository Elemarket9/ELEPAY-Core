package com.elepay.rails;

import com.elepay.common.Idempotency;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Direct partner adapter for GhIPSS (Ghana Interbank Payment and Settlement Systems / GIP).
 * Direct rail for instant bank clearing and settlement across Ghanaian commercial banks.
 *
 * <p><strong>Fees:</strong> Flat fee of 50 pesewas (0.50 GHS) per interbank transfer.
 * <p><strong>Callbacks:</strong> Verified using HMAC-SHA256 in {@code x-ghipss-signature}.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "elepay.rails.sandbox.enabled", havingValue = "true")
public class GhipssRail implements PaymentRail {
    private static final Logger log = LoggerFactory.getLogger(GhipssRail.class);
    public static final long FLAT_FEE_PESEWAS = 50L;

    private final JdbcClient db;
    private final ObjectMapper json;
    private final NetworkHealthDetector health;
    private final String webhookSecret;

    public GhipssRail(
            JdbcClient db,
            ObjectMapper json,
            NetworkHealthDetector health,
            @Value("${elepay.rails.ghipss.webhook-secret:ghipss-webhook-secret-mock}") String webhookSecret
    ) {
        this.db = db;
        this.json = json;
        this.health = health;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public String name() {
        return "ghipss";
    }

    @Override
    public boolean callbacksAreAuthoritative() {
        return true;
    }

    private Result process(String direction, String reference, long amount, String accountNumber) {
        long start = System.currentTimeMillis();
        String outcome = resolveOutcome(accountNumber);
        String railRef = "GIP-" + reference;
        long fee = FLAT_FEE_PESEWAS;

        if ("lost".equals(outcome)) {
            health.record(name(), false, true, System.currentTimeMillis() - start);
            return Result.unknown("GhIPSS clearing switch timeout before response");
        }

        String status = switch (outcome) {
            case "decline" -> "failed";
            case "timeout_succeeded", "ok" -> "succeeded";
            default -> "pending";
        };

        db.sql("""
                INSERT INTO simulated_rail_txn (reference, direction, amount, msisdn, outcome, status)
                VALUES (:r, :d, :a, :m, :o, :s) ON CONFLICT (reference) DO NOTHING""")
                .param("r", reference).param("d", direction).param("a", amount).param("m", accountNumber)
                .param("o", outcome).param("s", status).update();

        long latency = System.currentTimeMillis() - start;

        if ("timeout_succeeded".equals(outcome)) {
            health.record(name(), false, true, latency);
            return Result.unknown("GhIPSS timeout after transaction switched to destination bank");
        }

        if ("decline".equals(outcome)) {
            health.record(name(), false, false, latency);
            return new Result(Status.FAILED, railRef, amount, "GHS", fee, null, "Declined by beneficiary bank (invalid account or limit)");
        }

        health.record(name(), true, false, latency);
        return new Result(Status.PENDING, railRef, amount, "GHS", fee, null, "Interbank clearing in progress");
    }

    @Override
    public Result charge(Charge charge) {
        return process("charge", charge.reference(), charge.amount(), charge.msisdn());
    }

    @Override
    public Result payout(Payout payout) {
        return process("payout", payout.reference(), payout.amount(), payout.accountNumber());
    }

    @Override
    public Result refund(Refund refund) {
        return process("refund", refund.reference(), refund.amount(), refund.msisdn());
    }

    @Override
    public Result query(String direction, String reference, String railReference) {
        long start = System.currentTimeMillis();
        try {
            var res = db.sql("SELECT status, amount FROM simulated_rail_txn WHERE reference = :r")
                    .param("r", reference)
                    .query((rs, i) -> {
                        String st = rs.getString(1);
                        long amt = rs.getLong(2);
                        return switch (st) {
                            case "succeeded" -> new Result(Status.SUCCEEDED, "GIP-" + reference, amt, "GHS", FLAT_FEE_PESEWAS, null, "Settled");
                            case "failed" -> new Result(Status.FAILED, "GIP-" + reference, amt, "GHS", FLAT_FEE_PESEWAS, null, "Declined by bank");
                            case "returned" -> new Result(Status.RETURNED, "GIP-" + reference, amt, "GHS", FLAT_FEE_PESEWAS, null, "Returned by bank");
                            default -> new Result(Status.PENDING, "GIP-" + reference, amt, "GHS", FLAT_FEE_PESEWAS, null, "Pending");
                        };
                    }).optional().orElse(new Result(Status.NOT_FOUND, null, null, null, null, null, "Unknown at GhIPSS"));
            health.record(name(), res.status() != Status.UNKNOWN, false, System.currentTimeMillis() - start);
            return res;
        } catch (Exception e) {
            health.record(name(), false, true, System.currentTimeMillis() - start);
            return Result.unknown("GhIPSS query error: " + e.getMessage());
        }
    }

    @Override
    public Callback verifyCallback(Map<String, String> headers, String body) {
        String signature = headers.get("x-ghipss-signature");
        if (signature == null || !MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8), sign(body).getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidCallbackException("Invalid GhIPSS signature");
        }

        JsonNode node = json.readTree(body);
        JsonNode data = node.path("data");
        String statusStr = data.path("status").asString();
        Status mapped = switch (statusStr) {
            case "succeeded", "success" -> Status.SUCCEEDED;
            case "failed" -> Status.FAILED;
            case "returned" -> Status.RETURNED;
            default -> Status.PENDING;
        };
        String reference = data.path("reference").asString();
        long amount = data.path("amount").asLong();

        return new Callback(
                Idempotency.sha256(body),
                node.path("event").asString(),
                null,
                reference,
                "GIP-" + reference,
                new Result(mapped, "GIP-" + reference, amount, "GHS", FLAT_FEE_PESEWAS, null, statusStr)
        );
    }

    public String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("GhIPSS HMAC failure", e);
        }
    }

    private static String resolveOutcome(String account) {
        if (account == null || account.isEmpty()) return "ok";
        return switch (account.charAt(account.length() - 1)) {
            case '1' -> "decline";
            case '2' -> "timeout_succeeded";
            case '3' -> "hold";
            case '4' -> "lost";
            default -> "ok";
        };
    }
}
