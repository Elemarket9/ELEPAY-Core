package com.elepay.rails;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Algorithm 019: Card tokenization boundary.
 * Isolates sensitive cardholder data within a dedicated security perimeter.
 *
 * <p><strong>Security Invariants:</strong>
 * <ul>
 *   <li>Raw Primary Account Numbers (PANs) are encrypted using AES-GCM-256 and never logged.</li>
 *   <li>Card Verification Values (CVV/CVC) are NEVER retained in memory or persisted.</li>
 *   <li>Only masked PANs and opaque tokens ({@code tok_card_...}) are exposed to application modules.</li>
 *   <li>Decryption is restricted to the card rail execution boundary.</li>
 * </ul>
 */
@Service
public class CardTokenVault {
    private static final Logger log = LoggerFactory.getLogger(CardTokenVault.class);
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    private final JdbcClient db;
    private final CardBinLookupService binLookup;
    private final byte[] vaultKey;
    private final SecureRandom rng = new SecureRandom();

    public CardTokenVault(
            JdbcClient db,
            CardBinLookupService binLookup,
            @Value("${elepay.vault.master-key:elepay-default-secret-card-vault-key-32b!}") String masterKey
    ) {
        this.db = db;
        this.binLookup = binLookup;
        byte[] keyBytes = masterKey.getBytes(StandardCharsets.UTF_8);
        this.vaultKey = Arrays.copyOf(keyBytes, 32); // Ensure 256 bits
    }

    public record CardInput(
            String pan,
            int expiryMonth,
            int expiryYear,
            String cardholderName,
            String cvv,
            UUID customerId
    ) {}

    public record TokenizedCard(
            UUID id,
            String token,
            UUID customerId,
            String maskedPan,
            String scheme,
            String cardType,
            String issuerBank,
            int expiryMonth,
            int expiryYear,
            String cardholderName,
            String status,
            OffsetDateTime createdAt
    ) {}

    /**
     * Tokenizes a payment card after validating Luhn checksum and expiry.
     * Note: The CVV is strictly discarded and never retained.
     */
    @Transactional
    public TokenizedCard tokenize(CardInput input) {
        String cleanPan = input.pan() == null ? "" : input.pan().replaceAll("[^0-9]", "");
        if (cleanPan.length() < 13 || cleanPan.length() > 19) {
            throw ApiException.badRequest("invalid_pan_length", "Card number length must be between 13 and 19 digits");
        }
        if (!passesLuhn(cleanPan)) {
            throw ApiException.badRequest("invalid_luhn_checksum", "Card number failed Luhn validation check");
        }

        // Validate expiry
        if (input.expiryMonth() < 1 || input.expiryMonth() > 12) {
            throw ApiException.badRequest("invalid_expiry_month", "Card expiry month must be between 1 and 12");
        }
        YearMonth expiry = YearMonth.of(input.expiryYear(), input.expiryMonth());
        if (expiry.isBefore(YearMonth.now())) {
            throw ApiException.badRequest("card_expired", "Card is already expired");
        }

        // Validate CVV format if present, but DO NOT store it
        if (input.cvv() != null && !input.cvv().isBlank()) {
            String cleanCvv = input.cvv().trim();
            if (!cleanCvv.matches("^\\d{3,4}$")) {
                throw ApiException.badRequest("invalid_cvv", "Card security code must be 3 or 4 digits");
            }
        }

        // Resolve BIN metadata
        var binOpt = binLookup.lookup(cleanPan);
        String scheme = binOpt.map(CardBinLookupService.CardBin::scheme).orElseGet(() -> detectScheme(cleanPan));
        String cardType = binOpt.map(CardBinLookupService.CardBin::cardType).orElse("debit");
        String issuerBank = binOpt.map(CardBinLookupService.CardBin::issuerBank).orElse("Unknown Bank");

        // Compute masked PAN: keep first 6 and last 4, mask middle
        String maskedPan = maskPan(cleanPan);
        String fingerprint = Idempotency.sha256("elepay:card:fingerprint:" + cleanPan);

        // Check if card is already tokenized for this customer
        if (input.customerId() != null) {
            Optional<TokenizedCard> existing = findByFingerprint(input.customerId(), fingerprint);
            if (existing.isPresent() && "active".equals(existing.get().status())) {
                return existing.get();
            }
        }

        UUID id = UUID.randomUUID();
        String token = "tok_card_" + UUID.randomUUID().toString().replace("-", "");
        String encryptedPan = encrypt(cleanPan);

        db.sql("""
                INSERT INTO card_token (id, token, customer_id, masked_pan, fingerprint, scheme, card_type,
                                        issuer_bank, expiry_month, expiry_year, cardholder_name,
                                        encrypted_pan, status)
                VALUES (:id, :tok, :cust, :masked, :fp, :scheme, :ctype, :bank, :month, :year,
                        :name, :enc, 'active')""")
                .param("id", id)
                .param("tok", token)
                .param("cust", input.customerId())
                .param("masked", maskedPan)
                .param("fp", fingerprint)
                .param("scheme", scheme)
                .param("ctype", cardType)
                .param("bank", issuerBank)
                .param("month", input.expiryMonth())
                .param("year", input.expiryYear())
                .param("name", input.cardholderName())
                .param("enc", encryptedPan)
                .update();

        return findByToken(token).orElseThrow();
    }

    public Optional<TokenizedCard> findByToken(String token) {
        return db.sql("SELECT * FROM card_token WHERE token = :t")
                .param("t", token)
                .query(this::mapRecord)
                .optional();
    }

    public Optional<TokenizedCard> findByFingerprint(UUID customerId, String fingerprint) {
        return db.sql("SELECT * FROM card_token WHERE customer_id = :c AND fingerprint = :fp AND status = 'active'")
                .param("c", customerId)
                .param("fp", fingerprint)
                .query(this::mapRecord)
                .optional();
    }

    public List<TokenizedCard> listForCustomer(UUID customerId) {
        return db.sql("SELECT * FROM card_token WHERE customer_id = :c AND status = 'active' ORDER BY created_at DESC")
                .param("c", customerId)
                .query(this::mapRecord)
                .list();
    }

    @Transactional
    public void revoke(String token) {
        int updated = db.sql("""
                UPDATE card_token SET status = 'revoked', updated_at = now(), revoked_at = now()
                WHERE token = :t AND status = 'active'""")
                .param("t", token)
                .update();
        if (updated == 0) {
            throw ApiException.notFound("token_not_found", "Active card token not found");
        }
        log.info("Card token {} revoked successfully", token);
    }

    /**
     * Decrypts PAN strictly for the card rail adapter.
     */
    public String decryptPanForRail(String token) {
        var card = findByToken(token).orElseThrow(() -> ApiException.notFound("token_not_found", "Card token not found"));
        if (!"active".equals(card.status())) {
            throw ApiException.unprocessable("token_inactive", "Card token is " + card.status());
        }
        String encrypted = db.sql("SELECT encrypted_pan FROM card_token WHERE token = :t")
                .param("t", token)
                .query(String.class)
                .single();
        return decrypt(encrypted);
    }

    public static boolean passesLuhn(String pan) {
        int sum = 0;
        boolean alternate = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            int n = pan.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            alternate = !alternate;
        }
        return (sum % 10 == 0);
    }

    private static String maskPan(String pan) {
        if (pan.length() <= 10) return "******";
        String first6 = pan.substring(0, 6);
        String last4 = pan.substring(pan.length() - 4);
        int maskedLength = pan.length() - 10;
        return first6 + "*".repeat(maskedLength) + last4;
    }

    private static String detectScheme(String pan) {
        if (pan.startsWith("4")) return "visa";
        if (pan.matches("^(5[1-5]|2[2-7]).*")) return "mastercard";
        if (pan.startsWith("9001")) return "gh-link";
        if (pan.matches("^(506|6500).*")) return "verve";
        if (pan.matches("^3[47].*")) return "amex";
        return "card";
    }

    private String encrypt(String plainText) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            rng.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(vaultKey, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Vault encryption failure", e);
        }
    }

    private String decrypt(String base64Combined) {
        try {
            byte[] combined = Base64.getDecoder().decode(base64Combined);
            if (combined.length < GCM_IV_LENGTH) throw new IllegalArgumentException("Invalid ciphertext");
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] cipherText = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(vaultKey, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Vault decryption failure", e);
        }
    }

    private TokenizedCard mapRecord(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new TokenizedCard(
                rs.getObject("id", UUID.class),
                rs.getString("token"),
                rs.getObject("customer_id", UUID.class),
                rs.getString("masked_pan"),
                rs.getString("scheme"),
                rs.getString("card_type"),
                rs.getString("issuer_bank"),
                rs.getInt("expiry_month"),
                rs.getInt("expiry_year"),
                rs.getString("cardholder_name"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }
}
