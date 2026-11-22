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
 * Direct partner adapter for Telecel Cash (formerly Vodafone Cash Ghana).
 *
 * <p><strong>Fees:</strong> 0.75% capped at 750 pesewas (7.50 GHS).
 * <p><strong>Callbacks:</strong> Verified using HMAC-SHA256 signature in {@code x-telecel-signature}.
 */
@Component
public class TelecelCashRail implements PaymentRail {
    private static final Logger log = LoggerFactory.getLogger(TelecelCashRail.class);

    private final JdbcClient db;
    private final ObjectMapper json;
    private final NetworkHealthDetector health;
    private final String webhookSecret;

    public TelecelCashRail(
            JdbcClient db,
            ObjectMapper json,
            NetworkHealthDetector health,
            @Value("${elepay.rails.telecel.webhook-secret:telecel-webhook-secret-mock}") String webhookSecret
    ) {
        this.db = db;
        this.json = json;
        this.health = health;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public String name() {
        return "telecel";
    }

    @Override
    public boolean callbacksAreAuthoritative() {
        return true;
    }

    public static long calculateFee(long amount) {
        long fee = (amount * 75L) / 10000L; // 0.75%
        return Math.min(fee, 750L); // Capped at 750 pesewas
    }

    private Result process(String direction, String reference, long amount, String msisdn) {
        long start = System.currentTimeMillis();
        String outcome = resolveOutcome(msisdn);
        String railRef = "TEL-" + reference;
        long fee = calculateFee(amount);

        if ("lost".equals(outcome)) {
            health.record(name(), false, true, System.currentTimeMillis() - start);
            return Result.unknown("Telecel gateway timeout before response");
        }

        String status = switch (outcome) {
            case "decline" -> "failed";
            case "timeout_succeeded", "ok" -> "succeeded";
            default -> "pending";
        };

        db.sql("""
                INSERT INTO simulated_rail_txn (reference, direction, amount, msisdn, outcome, status)
                VALUES (:r, :d, :a, :m, :o, :s) ON CONFLICT (reference) DO NOTHING""")
                .param("r", reference).param("d", direction).param("a", amount).param("m", msisdn)
                .param("o", outcome).param("s", status).update();

        long latency = System.currentTimeMillis() - start;

        if ("timeout_succeeded".equals(outcome)) {
            health.record(name(), false, true, latency);
            return Result.unknown("Telecel timeout after transaction accepted");
        }

        if ("decline".equals(outcome)) {
            health.record(name(), false, false, latency);
            return new Result(Status.FAILED, railRef, amount, "GHS", fee, null, "Declined by Telecel Cash subscriber");
        }

        health.record(name(), true, false, latency);
        return new Result(Status.PENDING, railRef, amount, "GHS", fee, null, "Telecel voucher/prompt generated");
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
                        long fee = calculateFee(amt);
                        return switch (st) {
                            case "succeeded" -> new Result(Status.SUCCEEDED, "TEL-" + reference, amt, "GHS", fee, null, "Completed");
                            case "failed" -> new Result(Status.FAILED, "TEL-" + reference, amt, "GHS", fee, null, "Declined");
                            case "returned" -> new Result(Status.RETURNED, "TEL-" + reference, amt, "GHS", fee, null, "Returned by Telecel");
                            default -> new Result(Status.PENDING, "TEL-" + reference, amt, "GHS", fee, null, "Pending");
                        };
                    }).optional().orElse(new Result(Status.NOT_FOUND, null, null, null, null, null, "Unknown at Telecel"));
            health.record(name(), res.status() != Status.UNKNOWN, false, System.currentTimeMillis() - start);
            return res;
        } catch (Exception e) {
            health.record(name(), false, true, System.currentTimeMillis() - start);
            return Result.unknown("Telecel query error: " + e.getMessage());
        }
    }

    @Override
    public Callback verifyCallback(Map<String, String> headers, String body) {
        String signature = headers.get("x-telecel-signature");
        if (signature == null || !MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8), sign(body).getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidCallbackException("Invalid Telecel signature");
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
        long fee = calculateFee(amount);

        return new Callback(
                Idempotency.sha256(body),
                node.path("event").asString(),
                null,
                reference,
                "TEL-" + reference,
                new Result(mapped, "TEL-" + reference, amount, "GHS", fee, null, statusStr)
        );
    }

    public String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Telecel HMAC failure", e);
        }
    }

    private static String resolveOutcome(String msisdn) {
        if (msisdn == null || msisdn.isEmpty()) return "ok";
        return switch (msisdn.charAt(msisdn.length() - 1)) {
            case '1' -> "decline";
            case '2' -> "timeout_succeeded";
            case '3' -> "hold";
            case '4' -> "lost";
            default -> "ok";
        };
    }
}
