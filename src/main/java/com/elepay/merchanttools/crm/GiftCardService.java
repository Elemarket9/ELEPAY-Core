package com.elepay.merchanttools.crm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain 7: Gift Card Issuance and Redemption Engine with Cryptographic PIN Protection.
 */
@Service
public class GiftCardService {

    private final JdbcClient db;

    public GiftCardService(JdbcClient db) {
        this.db = db;
    }

    public record GiftCardSummary(
            UUID id,
            UUID merchantId,
            String cardCode,
            long initialBalancePesewas,
            long currentBalancePesewas,
            String currency,
            String recipientName,
            String recipientPhone,
            String status,
            OffsetDateTime expiresAt
    ) {}

    public record RedemptionResult(
            UUID cardId,
            String cardCode,
            long amountDeductedPesewas,
            long remainingBalancePesewas,
            String status
    ) {}

    /**
     * Issues a new merchant gift card secured with a hashed PIN.
     */
    @Transactional
    public GiftCardSummary issueGiftCard(UUID merchantId, String cardCode, String rawPin,
                                         long initialBalancePesewas, String currency,
                                         String recipientName, String recipientPhone,
                                         OffsetDateTime expiresAt) {
        if (rawPin == null || rawPin.length() < 4) {
            throw new IllegalArgumentException("PIN must be at least 4 digits");
        }
        if (initialBalancePesewas <= 0) {
            throw new IllegalArgumentException("Initial balance must be greater than zero");
        }

        UUID id = UUID.randomUUID();
        String pinHash = hashPin(cardCode, rawPin);
        String curr = currency != null ? currency : "GHS";

        db.sql("""
            INSERT INTO crm_gift_card
            (id, merchant_id, card_code, pin_hash, initial_balance_pesewas, current_balance_pesewas,
             currency, recipient_name, recipient_phone, status, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
        """).params(id, merchantId, cardCode, pinHash, initialBalancePesewas, initialBalancePesewas,
                curr, recipientName, recipientPhone, expiresAt)
        .update();

        return new GiftCardSummary(id, merchantId, cardCode, initialBalancePesewas, initialBalancePesewas,
                curr, recipientName, recipientPhone, "ACTIVE", expiresAt);
    }

    /**
     * Redeems a gift card balance towards an order, verifying PIN and expiry.
     */
    @Transactional
    public RedemptionResult redeemGiftCard(UUID merchantId, String cardCode, String rawPin,
                                          long amountToRedeemPesewas) {
        var card = db.sql("""
            SELECT id, pin_hash, current_balance_pesewas, status, expires_at
            FROM crm_gift_card
            WHERE merchant_id = ? AND card_code = ? FOR UPDATE
        """).params(merchantId, cardCode)
        .query((rs, rowNum) -> new Object[] {
                rs.getObject("id", UUID.class),
                rs.getString("pin_hash"),
                rs.getLong("current_balance_pesewas"),
                rs.getString("status"),
                rs.getObject("expires_at", OffsetDateTime.class)
        }).single();

        UUID cardId = (UUID) card[0];
        String expectedHash = (String) card[1];
        long currentBalance = (Long) card[2];
        String status = (String) card[3];
        OffsetDateTime expiresAt = (OffsetDateTime) card[4];

        if (!"ACTIVE".equals(status)) {
            throw new IllegalStateException("Gift card is not active (status: " + status + ")");
        }
        if (expiresAt.isBefore(OffsetDateTime.now())) {
            db.sql("UPDATE crm_gift_card SET status = 'EXPIRED' WHERE id = ?").param(cardId).update();
            throw new IllegalStateException("Gift card has expired on " + expiresAt);
        }

        String providedHash = hashPin(cardCode, rawPin);
        if (!providedHash.equals(expectedHash)) {
            throw new IllegalArgumentException("Invalid gift card PIN");
        }

        long actualDeduction = Math.min(amountToRedeemPesewas, currentBalance);
        if (actualDeduction <= 0) {
            throw new IllegalStateException("Gift card has zero remaining balance");
        }

        long newBalance = currentBalance - actualDeduction;
        String newStatus = newBalance == 0 ? "REDEEMED" : "ACTIVE";

        db.sql("UPDATE crm_gift_card SET current_balance_pesewas = ?, status = ? WHERE id = ?")
                .params(newBalance, newStatus, cardId).update();

        return new RedemptionResult(cardId, cardCode, actualDeduction, newBalance, newStatus);
    }

    public Optional<GiftCardSummary> getGiftCard(UUID merchantId, String cardCode) {
        return db.sql("""
            SELECT id, merchant_id, card_code, initial_balance_pesewas, current_balance_pesewas,
                   currency, recipient_name, recipient_phone, status, expires_at
            FROM crm_gift_card
            WHERE merchant_id = ? AND card_code = ?
        """).params(merchantId, cardCode)
        .query((rs, rowNum) -> new GiftCardSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("card_code"),
                rs.getLong("initial_balance_pesewas"),
                rs.getLong("current_balance_pesewas"),
                rs.getString("currency"),
                rs.getString("recipient_name"),
                rs.getString("recipient_phone"),
                rs.getString("status"),
                rs.getObject("expires_at", OffsetDateTime.class)
        )).optional();
    }

    private static String hashPin(String cardCode, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String salted = cardCode + ":" + pin + ":elepay_gift_salt";
            byte[] hash = md.digest(salted.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
