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
 * Direct partner adapter for Card Acquiring (Visa, Mastercard, Gh-Link).
 * Coordinates card tokenization via {@link CardTokenVault} and 3DS 2.x
 * authentication via {@link ThreeDSecureService}.
 *
 * <p><strong>Fees:</strong> 1.5% interchange and acquirer fee.
 * <p><strong>Callbacks:</strong> Verified using HMAC-SHA256 in {@code x-card-signature}.
 */
@Component
public class CardAcquirerRail implements PaymentRail {
    private static final Logger log = LoggerFactory.getLogger(CardAcquirerRail.class);

    private final JdbcClient db;
    private final ObjectMapper json;
    private final CardTokenVault vault;
    private final ThreeDSecureService threeDs;
    private final NetworkHealthDetector health;
    private final String webhookSecret;

    public CardAcquirerRail(
            JdbcClient db,
            ObjectMapper json,
            CardTokenVault vault,
            ThreeDSecureService threeDs,
            NetworkHealthDetector health,
            @Value("${elepay.rails.card.webhook-secret:card-webhook-secret-mock}") String webhookSecret
    ) {
        this.db = db;
        this.json = json;
        this.vault = vault;
        this.threeDs = threeDs;
        this.health = health;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public String name() {
        return "card";
    }

    @Override
    public boolean callbacksAreAuthoritative() {
        return true;
    }

    public static long calculateFee(long amount) {
        return (amount * 150L) / 10000L; // 1.5%
    }

    @Override
    public Result charge(Charge charge) {
        long start = System.currentTimeMillis();
        long fee = calculateFee(charge.amount());
        String railRef = "CRD-" + charge.reference();

        // 3DS 2.x risk check and evaluation
        ThreeDSecureService.ThreeDSSession session = threeDs.initiate(new ThreeDSecureService.InitRequest(
                charge.reference(), charge.amount(), charge.currency(), null, null
        ));

        if ("frictionless".equals(session.flowType()) && "authenticated".equals(session.status())) {
            // Low risk -> Frictionless: authenticated immediately with liability shift
            db.sql("""
                    INSERT INTO simulated_rail_txn (reference, direction, amount, msisdn, outcome, status)
                    VALUES (:r, 'charge', :a, :m, 'ok', 'succeeded') ON CONFLICT (reference) DO NOTHING""")
                    .param("r", charge.reference()).param("a", charge.amount()).param("m", charge.msisdn()).update();
            health.record(name(), true, false, System.currentTimeMillis() - start);
            return new Result(Status.SUCCEEDED, railRef, charge.amount(), charge.currency(), fee, null, "3DS 2.x frictionless authenticated");
        }

        // Higher risk -> Challenge flow: redirect customer to 3DS challenge URL
        db.sql("""
                INSERT INTO simulated_rail_txn (reference, direction, amount, msisdn, outcome, status)
                VALUES (:r, 'charge', :a, :m, 'hold', 'pending') ON CONFLICT (reference) DO NOTHING""")
                .param("r", charge.reference()).param("a", charge.amount()).param("m", charge.msisdn()).update();

        health.record(name(), true, false, System.currentTimeMillis() - start);
        return new Result(Status.PENDING, railRef, charge.amount(), charge.currency(), fee, session.challengeUrl(), "3DS 2.x authentication challenge required");
    }

    @Override
    public Result payout(Payout payout) {
        // Direct OCT (Original Credit Transaction) to card
        long start = System.currentTimeMillis();
        long fee = calculateFee(payout.amount());
        String railRef = "CRD-OCT-" + payout.reference();

        db.sql("""
                INSERT INTO simulated_rail_txn (reference, direction, amount, msisdn, outcome, status)
                VALUES (:r, 'payout', :a, :m, 'ok', 'succeeded') ON CONFLICT (reference) DO NOTHING""")
                .param("r", payout.reference()).param("a", payout.amount()).param("m", payout.accountNumber()).update();

        health.record(name(), true, false, System.currentTimeMillis() - start);
        return new Result(Status.SUCCEEDED, railRef, payout.amount(), payout.currency(), fee, null, "Card OCT payout completed");
    }

    @Override
    public Result refund(Refund refund) {
        long start = System.currentTimeMillis();
        long fee = 0L;
        String railRef = "CRD-REF-" + refund.reference();

        db.sql("""
                INSERT INTO simulated_rail_txn (reference, direction, amount, msisdn, outcome, status)
                VALUES (:r, 'refund', :a, :m, 'ok', 'succeeded') ON CONFLICT (reference) DO NOTHING""")
                .param("r", refund.reference()).param("a", refund.amount()).param("m", refund.msisdn()).update();

        health.record(name(), true, false, System.currentTimeMillis() - start);
        return new Result(Status.SUCCEEDED, railRef, refund.amount(), refund.currency(), fee, null, "Card refund processed");
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
                            case "succeeded" -> new Result(Status.SUCCEEDED, "CRD-" + reference, amt, "GHS", fee, null, "Card authorization captured");
                            case "failed" -> new Result(Status.FAILED, "CRD-" + reference, amt, "GHS", fee, null, "Card authorization declined");
                            case "returned" -> new Result(Status.RETURNED, "CRD-" + reference, amt, "GHS", fee, null, "Card payment chargeback/reversal");
                            default -> new Result(Status.PENDING, "CRD-" + reference, amt, "GHS", fee, null, "Awaiting 3DS challenge completion");
                        };
                    }).optional().orElse(new Result(Status.NOT_FOUND, null, null, null, null, null, "Card transaction not found"));
            health.record(name(), res.status() != Status.UNKNOWN, false, System.currentTimeMillis() - start);
            return res;
        } catch (Exception e) {
            health.record(name(), false, true, System.currentTimeMillis() - start);
            return Result.unknown("Card query error: " + e.getMessage());
        }
    }

    @Override
    public Callback verifyCallback(Map<String, String> headers, String body) {
        String signature = headers.get("x-card-signature");
        if (signature == null || !MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8), sign(body).getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidCallbackException("Invalid card acquirer HMAC signature");
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
                "CRD-" + reference,
                new Result(mapped, "CRD-" + reference, amount, "GHS", calculateFee(amount), null, statusStr)
        );
    }

    public String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Card HMAC failure", e);
        }
    }
}
