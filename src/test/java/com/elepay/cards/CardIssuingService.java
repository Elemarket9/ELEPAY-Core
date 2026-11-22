package com.elepay.cards;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardIssuingService {

    private final JdbcClient db;
    private final SecureRandom random = new SecureRandom();

    public CardIssuingService(JdbcClient db) {
        this.db = db;
    }

    public record CardDetails(
            UUID id,
            UUID customerId,
            UUID merchantId,
            String cardType,
            String scheme,
            String maskedPan,
            String fullPan, // returned only upon creation
            String cvv2,
            int expiryMonth,
            int expiryYear,
            String status,
            long perTxLimitPesewas,
            long dailyLimitPesewas,
            long monthlyLimitPesewas,
            OffsetDateTime createdAt
    ) {}

    public static boolean isValidLuhn(String pan) {
        if (pan == null || pan.length() < 13 || pan.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean alternate = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            int n = pan.charAt(i) - '0';
            if (n < 0 || n > 9) return false;
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return (sum % 10 == 0);
    }

    public static int calculateLuhnCheckDigit(String numberWithoutCheck) {
        int sum = 0;
        boolean alternate = true; // since the check digit will be at the end (odd position from right)
        for (int i = numberWithoutCheck.length() - 1; i >= 0; i--) {
            int n = numberWithoutCheck.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return (10 - (sum % 10)) % 10;
    }

    public String generatePan(String scheme) {
        String bin;
        if ("VISA".equalsIgnoreCase(scheme)) {
            bin = "411111"; // Standard test BIN for Visa
        } else if ("MASTERCARD".equalsIgnoreCase(scheme)) {
            bin = "539999"; // Standard test BIN for Mastercard
        } else {
            throw new IllegalArgumentException("Unsupported scheme: " + scheme);
        }

        StringBuilder accountBuilder = new StringBuilder(bin);
        // Add 9 random digits to reach 15 digits
        for (int i = 0; i < 9; i++) {
            accountBuilder.append(random.nextInt(10));
        }

        String partialPan = accountBuilder.toString();
        int checkDigit = calculateLuhnCheckDigit(partialPan);
        String fullPan = partialPan + checkDigit;

        if (!isValidLuhn(fullPan)) {
            throw new IllegalStateException("Generated PAN failed Luhn check: " + fullPan);
        }
        return fullPan;
    }

    public String generateCvv2() {
        int code = random.nextInt(1000);
        return String.format("%03d", code);
    }

    public static String hashPan(String pan) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(pan.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String maskPan(String pan) {
        if (pan == null || pan.length() < 10) return "****";
        return pan.substring(0, 6) + "******" + pan.substring(pan.length() - 4);
    }

    @Transactional
    public CardDetails issueCard(UUID customerId, UUID merchantId, String cardType, String scheme) {
        String fullPan = generatePan(scheme);
        String panHash = hashPan(fullPan);
        String maskedPan = maskPan(fullPan);
        String cvv2 = generateCvv2();

        LocalDate now = LocalDate.now();
        int expiryMonth = now.getMonthValue();
        int expiryYear = now.getYear() + 3; // 3-year validity

        UUID cardId = UUID.randomUUID();
        // Virtual cards are ACTIVE immediately; Physical cards are PENDING_ACTIVATION
        String initialStatus = "PHYSICAL".equalsIgnoreCase(cardType) ? "PENDING_ACTIVATION" : "ACTIVE";

        long perTxLimit = 500000L;    // 5,000 GHS
        long dailyLimit = 2000000L;   // 20,000 GHS
        long monthlyLimit = 10000000L;// 100,000 GHS

        db.sql("""
            INSERT INTO card_registry
            (id, customer_id, merchant_id, card_type, scheme, masked_pan, pan_hash,
             encrypted_pan, cvv2, expiry_month, expiry_year, status, per_tx_limit_pesewas,
             daily_limit_pesewas, monthly_limit_pesewas, current_day_spent_pesewas,
             current_month_spent_pesewas, last_spend_date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, CURRENT_DATE)
        """).params(cardId, customerId, merchantId, cardType.toUpperCase(), scheme.toUpperCase(),
                maskedPan, panHash, "ENC:" + fullPan, cvv2, expiryMonth, expiryYear, initialStatus,
                perTxLimit, dailyLimit, monthlyLimit)
        .update();

        return new CardDetails(
                cardId, customerId, merchantId, cardType.toUpperCase(), scheme.toUpperCase(),
                maskedPan, fullPan, cvv2, expiryMonth, expiryYear, initialStatus,
                perTxLimit, dailyLimit, monthlyLimit, OffsetDateTime.now()
        );
    }

    public Optional<CardDetails> getCardById(UUID cardId) {
        return db.sql("""
            SELECT id, customer_id, merchant_id, card_type, scheme, masked_pan, cvv2,
                   expiry_month, expiry_year, status, per_tx_limit_pesewas,
                   daily_limit_pesewas, monthly_limit_pesewas, created_at
            FROM card_registry
            WHERE id = ?
        """).param(cardId)
        .query((rs, rowNum) -> new CardDetails(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("card_type"),
                rs.getString("scheme"),
                rs.getString("masked_pan"),
                null,
                rs.getString("cvv2"),
                rs.getInt("expiry_month"),
                rs.getInt("expiry_year"),
                rs.getString("status"),
                rs.getLong("per_tx_limit_pesewas"),
                rs.getLong("daily_limit_pesewas"),
                rs.getLong("monthly_limit_pesewas"),
                rs.getObject("created_at", OffsetDateTime.class)
        )).optional();
    }
}
