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

    /** Ciphertext written with the configured key carries this prefix; older rows have none. */
    static final String V2 = "v2:";

    private final JdbcClient db;
    private final CardBinLookupService binLookup;
    private final byte[] vaultKey;
    private final byte[] fingerprintKey;
    private final byte[] legacyKey;
    private final SecureRandom rng = new SecureRandom();

    public CardTokenVault(JdbcClient db, CardBinLookupService binLookup, String masterKey) {
        this(db, binLookup, masterKey, "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CardTokenVault(
            JdbcClient db,
            CardBinLookupService binLookup,
            @Value("${elepay.vault.master-key:}") String masterKey,
            @Value("${elepay.vault.legacy-master-key:}") String legacyMasterKey
    ) {
        this.db = db;
        this.binLookup = binLookup;
        this.vaultKey = vaultKey(masterKey);
        // Separate key for fingerprints, derived from the vault key (key separation).
        this.fingerprintKey = hmac(vaultKey, "elepay:card-fingerprint:v2".getBytes(StandardCharsets.UTF_8));
        // Legacy token migration is opt-in, with the old key supplied externally.
        // A hardcoded fallback would allow anyone with the source to decrypt historic PANs.
        this.legacyKey = legacyMasterKey == null || legacyMasterKey.isBlank()
                ? null : Arrays.copyOf(legacyMasterKey.getBytes(StandardCharsets.UTF_8), 32);
    }

    /**
     * The 256-bit vault key: base64 of 32 random bytes in production. A non-production passphrase of at least
     * 32 characters is stretched with SHA-256. There is no default.
     */
    static byte[] vaultKey(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("elepay.vault.master-key is required (base64 of 32 random bytes)");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(configured.trim());
            if (decoded.length == 32) return decoded;
        } catch (IllegalArgumentException notBase64) {
            // fall through to passphrase handling
        }
        if (configured.length() < 32) {
            throw new IllegalStateException("elepay.vault.master-key must be base64 of 32 bytes or at least 32 characters");
        }
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(configured.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Keyed fingerprint (PCI DSS 3.5.1.1): HMAC-SHA256 of the card number under a vault-derived key. */
    String fingerprint(String pan) {
        return java.util.HexFormat.of().formatHex(hmac(fingerprintKey, pan.getBytes(StandardCharsets.UTF_8)));
    }

    public record CardInput(
            String pan,
            int expiryMonth,
            int expiryYear,
            String cardholderName,
            String cvv,
            UUID customerId,
            String ownerSubject
    ) {
        public CardInput(String pan, int expiryMonth, int expiryYear, String cardholderName, String cvv, UUID customerId) {
            this(pan, expiryMonth, expiryYear, cardholderName, cvv, customerId, null);
        }
    }

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
        String fingerprint = fingerprint(cleanPan);

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
                                        encrypted_pan, status, owner_subject, fingerprint_version)
                VALUES (:id, :tok, :cust, :masked, :fp, :scheme, :ctype, :bank, :month, :year,
                        :name, :enc, 'active', :owner, 2)""")
                .param("owner", input.ownerSubject())
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

    /** The subject that tokenized the card ("merchant:<id>" for API keys), if recorded. */
    public Optional<String> ownerOf(String token) {
        return db.sql("SELECT owner_subject FROM card_token WHERE token = :t").param("t", token)
                .query((rs, i) -> Optional.ofNullable(rs.getString(1))).optional().flatMap(o -> o);
    }

    /**
     * Upgrades rows written by earlier releases: re-encrypts each card number under the configured vault key
     * and replaces the unkeyed fingerprint with a keyed one. Safe to run repeatedly.
     */
    @Transactional
    public int upgradeLegacyRows() {
        record Legacy(UUID id, String encrypted) {}
        List<Legacy> rows = db.sql("""
                SELECT id, encrypted_pan FROM card_token
                WHERE fingerprint_version < 2 OR encrypted_pan NOT LIKE 'v2:%' FOR UPDATE SKIP LOCKED""")
                .query((rs, i) -> new Legacy(rs.getObject(1, UUID.class), rs.getString(2))).list();
        int upgraded = 0;
        for (Legacy row : rows) {
            String pan;
            try {
                pan = decrypt(row.encrypted());
            } catch (IllegalStateException e) {
                log.warn("Card token {} could not be decrypted for upgrade; set elepay.vault.legacy-master-key", row.id());
                continue;
            }
            db.sql("""
                    UPDATE card_token SET encrypted_pan = :enc, fingerprint = :fp, fingerprint_version = 2,
                           updated_at = now() WHERE id = :id""")
                    .param("enc", encrypt(pan)).param("fp", fingerprint(pan)).param("id", row.id()).update();
            upgraded++;
        }
        if (upgraded > 0) log.info("Upgraded {} card tokens to the configured vault key and keyed fingerprints", upgraded);
        return upgraded;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void upgradeOnStartup() {
        upgradeLegacyRows();
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
            cipher.updateAAD(AAD);
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return V2 + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Vault encryption failure", e);
        }
    }

    private static final byte[] AAD = "elepay:card_token:pan:v2".getBytes(StandardCharsets.UTF_8);

    private String decrypt(String stored) {
        boolean current = stored.startsWith(V2);
        if (!current && legacyKey == null) {
            throw new IllegalStateException(
                    "Legacy card tokens require an externally supplied migration key; refusing fallback decryption");
        }
        String base64Combined = current ? stored.substring(V2.length()) : stored;
        try {
            byte[] combined = Base64.getDecoder().decode(base64Combined);
            if (combined.length < GCM_IV_LENGTH) throw new IllegalArgumentException("Invalid ciphertext");
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] cipherText = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(current ? vaultKey : legacyKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            if (current) cipher.updateAAD(AAD);
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
