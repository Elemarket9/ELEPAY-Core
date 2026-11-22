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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PCI DSS 4.0.1 Compliant Card Issuing Service.
 * - Never stores CVV2 or PIN blocks in the database.
 * - Prohibits standard test BINs (411111, 539999).
 * - Implements AES-256-GCM authenticated envelope encryption with unique DEKs and AAD.
 */
@Service
public class CardIssuingService {

    public static final String PRODUCTION_VISA_BIN = "402234";
    public static final String PRODUCTION_MASTERCARD_BIN = "524128";

    private final JdbcClient db;
    private final CardPanVault vault;
    private final SecureRandom random = new SecureRandom();
    private final String visaBin;
    private final String mastercardBin;

    @org.springframework.beans.factory.annotation.Autowired
    public CardIssuingService(
            JdbcClient db,
            CardPanVault vault,
            @Value("${elepay.cards.issuing.bin.visa:402234}") String visaBin,
            @Value("${elepay.cards.issuing.bin.mastercard:524128}") String mastercardBin
    ) {
        this.db = db;
        this.vault = vault;
        this.visaBin = visaBin;
        this.mastercardBin = mastercardBin;
    }

    public CardIssuingService(JdbcClient db, CardPanVault vault) {
        this(db, vault, PRODUCTION_VISA_BIN, PRODUCTION_MASTERCARD_BIN);
    }

    public CardIssuingService(JdbcClient db) {
        this(db, new CardPanVault());
    }

    public record CardDetails(
            UUID id,
            UUID customerId,
            UUID merchantId,
            String cardType,
            String scheme,
            String maskedPan,
            String fullPan, // returned only upon initial creation
            String cvv2,    // returned only upon initial creation, NEVER stored in database
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
        boolean alternate = true;
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
            bin = visaBin;
        } else if ("MASTERCARD".equalsIgnoreCase(scheme)) {
            bin = mastercardBin;
        } else {
            throw new IllegalArgumentException("Unsupported scheme: " + scheme);
        }

        // Strictly enforce PCI DSS mandate: Never generate production cards using test BINs
        CardPanVault.assertProductionBin(bin);

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

        LocalDate now = LocalDate.now();
        int expiryMonth = now.getMonthValue();
        int expiryYear = now.getYear() + 3; // 3-year validity

        UUID cardId = UUID.randomUUID();
        // Virtual cards are ACTIVE immediately; Physical cards are PENDING_ACTIVATION
        String initialStatus = "PHYSICAL".equalsIgnoreCase(cardType) ? "PENDING_ACTIVATION" : "ACTIVE";

        long perTxLimit = 500000L;    // 5,000 GHS
        long dailyLimit = 2000000L;   // 20,000 GHS
        long monthlyLimit = 10000000L;// 100,000 GHS

        // Compute dynamic CVV2 using secret Card Verification Key (never persisted)
        String cvv2 = vault.computeCvv2(fullPan, expiryMonth, expiryYear);

        // Real AES-256-GCM envelope encryption with unique DEK and cardId-bound AAD
        String encryptedPan = vault.encryptPan(cardId, fullPan);

        // PCI DSS 4.0.1: cvv2 column is strictly NULL in database storage
        db.sql("""
            INSERT INTO card_registry
            (id, customer_id, merchant_id, card_type, scheme, masked_pan, pan_hash,
             encrypted_pan, cvv2, expiry_month, expiry_year, status, per_tx_limit_pesewas,
             daily_limit_pesewas, monthly_limit_pesewas, current_day_spent_pesewas,
             current_month_spent_pesewas, last_spend_date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, 0, 0, CURRENT_DATE)
        """).params(cardId, customerId, merchantId, cardType.toUpperCase(), scheme.toUpperCase(),
                maskedPan, panHash, encryptedPan, expiryMonth, expiryYear, initialStatus,
                perTxLimit, dailyLimit, monthlyLimit)
        .update();

        // Return details with fullPan and cvv2 only for initial cardholder presentation
        return new CardDetails(
                cardId, customerId, merchantId, cardType.toUpperCase(), scheme.toUpperCase(),
                maskedPan, fullPan, cvv2, expiryMonth, expiryYear, initialStatus,
                perTxLimit, dailyLimit, monthlyLimit, OffsetDateTime.now()
        );
    }

    public Optional<CardDetails> getCardById(UUID cardId) {
        return db.sql("""
            SELECT id, customer_id, merchant_id, card_type, scheme, masked_pan,
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
                null, // fullPan never retrieved after issuance
                null, // cvv2 never stored or retrieved
                rs.getInt("expiry_month"),
                rs.getInt("expiry_year"),
                rs.getString("status"),
                rs.getLong("per_tx_limit_pesewas"),
                rs.getLong("daily_limit_pesewas"),
                rs.getLong("monthly_limit_pesewas"),
                rs.getObject("created_at", OffsetDateTime.class)
        )).optional();
    }

    public boolean verifyCvv2(String pan, int expiryMonth, int expiryYear, String cvv2) {
        return vault.verifyCvv2(pan, expiryMonth, expiryYear, cvv2);
    }

    public String decryptPan(UUID cardId, String encryptedPan) {
        return vault.decryptPan(cardId, encryptedPan);
    }

    public CardPanVault vault() {
        return vault;
    }
}
