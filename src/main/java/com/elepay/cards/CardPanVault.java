package com.elepay.cards;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * PCI DSS 4.0.1 compliant Cryptographic Vault for Card PAN and CVV2.
 * Uses AES-256-GCM envelope encryption with unique Data Encryption Keys (DEK),
 * Key Encryption Keys (KEK), and Additional Authenticated Data (AAD) bound to the card entity.
 */
@Component
public class CardPanVault {
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int DEK_KEY_SIZE = 32;
    public static final String VERSION_PREFIX = "v1:env:";

    public static final Set<String> PROHIBITED_TEST_BINS = Set.of(
            "411111", "539999", "400000", "555555"
    );

    private final byte[] kek;
    private final byte[] cvk;
    private final SecureRandom rng = new SecureRandom();

    public CardPanVault(
            @Value("${elepay.vault.master-key:${elepay.cards.master-key:}}") String masterKey
    ) {
        this.kek = deriveMasterKey(masterKey);
        this.cvk = hmac(this.kek, "elepay:cards:cvk:v1".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] deriveMasterKey(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("elepay.vault.master-key (or elepay.cards.master-key) is required");
        }
        String key = configured.trim();
        try {
            byte[] decoded = Base64.getDecoder().decode(key);
            if (decoded.length == 32) return decoded;
        } catch (IllegalArgumentException ignored) {}
        if (key.length() < 32) {
            throw new IllegalStateException("elepay.vault.master-key must be base64 of 32 bytes or at least 32 characters");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC generation failure", e);
        }
    }

    public static void assertProductionBin(String bin) {
        if (bin == null || bin.length() < 6) {
            throw new IllegalArgumentException("Invalid BIN length: " + bin);
        }
        String prefix6 = bin.substring(0, 6);
        if (PROHIBITED_TEST_BINS.contains(prefix6)) {
            throw new IllegalStateException("Test BIN prohibited in production: " + prefix6);
        }
    }

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
                if (n > 9) n -= 9;
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
                if (n > 9) n -= 9;
            }
            sum += n;
            alternate = !alternate;
        }
        return (10 - (sum % 10)) % 10;
    }

    /**
     * Envelope encryption:
     * 1. Generates ephemeral 256-bit DEK.
     * 2. Encrypts PAN with DEK using AES-256-GCM and AAD = "elepay:card:pan:v1:" + cardId.
     * 3. Wraps DEK with KEK using AES-256-GCM and AAD = "elepay:card:dek:v1:" + cardId.
     * 4. Combines ivDek + wrappedDek + ivPan + panCiphertext.
     */
    public String encryptPan(UUID cardId, String pan) {
        if (pan == null || pan.isBlank()) {
            throw new IllegalArgumentException("PAN cannot be blank");
        }
        try {
            byte[] dek = new byte[DEK_KEY_SIZE];
            rng.nextBytes(dek);

            // 1. Encrypt PAN with DEK
            byte[] ivPan = new byte[GCM_IV_LENGTH];
            rng.nextBytes(ivPan);
            Cipher panCipher = Cipher.getInstance("AES/GCM/NoPadding");
            panCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, ivPan));
            panCipher.updateAAD(("elepay:card:pan:v1:" + cardId).getBytes(StandardCharsets.UTF_8));
            byte[] panCiphertext = panCipher.doFinal(pan.getBytes(StandardCharsets.UTF_8));

            // 2. Wrap DEK with KEK
            byte[] ivDek = new byte[GCM_IV_LENGTH];
            rng.nextBytes(ivDek);
            Cipher dekCipher = Cipher.getInstance("AES/GCM/NoPadding");
            dekCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, ivDek));
            dekCipher.updateAAD(("elepay:card:dek:v1:" + cardId).getBytes(StandardCharsets.UTF_8));
            byte[] wrappedDek = dekCipher.doFinal(dek);

            // Zero out DEK in memory
            Arrays.fill(dek, (byte) 0);

            // 3. Assemble binary payload
            // Format: ivDek (12) + len(wrappedDek) (2) + wrappedDek + ivPan (12) + panCiphertext
            byte[] payload = new byte[GCM_IV_LENGTH + 2 + wrappedDek.length + GCM_IV_LENGTH + panCiphertext.length];
            int offset = 0;
            System.arraycopy(ivDek, 0, payload, offset, GCM_IV_LENGTH);
            offset += GCM_IV_LENGTH;
            payload[offset++] = (byte) ((wrappedDek.length >> 8) & 0xff);
            payload[offset++] = (byte) (wrappedDek.length & 0xff);
            System.arraycopy(wrappedDek, 0, payload, offset, wrappedDek.length);
            offset += wrappedDek.length;
            System.arraycopy(ivPan, 0, payload, offset, GCM_IV_LENGTH);
            offset += GCM_IV_LENGTH;
            System.arraycopy(panCiphertext, 0, payload, offset, panCiphertext.length);

            return VERSION_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to envelope-encrypt PAN", e);
        }
    }

    /**
     * Decrypts PAN using envelope unwrapping and AAD validation.
     */
    public String decryptPan(UUID cardId, String encryptedPan) {
        if (encryptedPan == null || !encryptedPan.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("Invalid or unauthenticated envelope ciphertext: " + encryptedPan);
        }
        try {
            byte[] payload = Base64.getDecoder().decode(encryptedPan.substring(VERSION_PREFIX.length()));
            int offset = 0;
            byte[] ivDek = Arrays.copyOfRange(payload, offset, offset + GCM_IV_LENGTH);
            offset += GCM_IV_LENGTH;
            int wrappedDekLen = ((payload[offset++] & 0xff) << 8) | (payload[offset++] & 0xff);
            byte[] wrappedDek = Arrays.copyOfRange(payload, offset, offset + wrappedDekLen);
            offset += wrappedDekLen;
            byte[] ivPan = Arrays.copyOfRange(payload, offset, offset + GCM_IV_LENGTH);
            offset += GCM_IV_LENGTH;
            byte[] panCiphertext = Arrays.copyOfRange(payload, offset, payload.length);

            // 1. Unwrap DEK with KEK
            Cipher dekCipher = Cipher.getInstance("AES/GCM/NoPadding");
            dekCipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, ivDek));
            dekCipher.updateAAD(("elepay:card:dek:v1:" + cardId).getBytes(StandardCharsets.UTF_8));
            byte[] dek = dekCipher.doFinal(wrappedDek);

            // 2. Decrypt PAN with DEK
            Cipher panCipher = Cipher.getInstance("AES/GCM/NoPadding");
            panCipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, ivPan));
            panCipher.updateAAD(("elepay:card:pan:v1:" + cardId).getBytes(StandardCharsets.UTF_8));
            byte[] plainPan = panCipher.doFinal(panCiphertext);

            // Zero out DEK in memory
            Arrays.fill(dek, (byte) 0);

            return new String(plainPan, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Envelope decryption failed or authentication tag mismatch", e);
        }
    }

    /**
     * Generates a 3-digit CVV2 cryptographically from PAN and Expiry using the secret CVK.
     * Never stored in any database.
     */
    public String computeCvv2(String pan, int expiryMonth, int expiryYear) {
        String data = pan + String.format("%02d%02d", expiryMonth, expiryYear % 100) + "000";
        byte[] hash = hmac(cvk, data.getBytes(StandardCharsets.UTF_8));
        int code = (((hash[0] & 0x7f) << 24) | ((hash[1] & 0xff) << 16) | ((hash[2] & 0xff) << 8) | (hash[3] & 0xff)) & 0x7fffffff;
        return String.format("%03d", code % 1000);
    }

    /**
     * Verifies CVV2 in constant time without retaining SAD.
     */
    public boolean verifyCvv2(String pan, int expiryMonth, int expiryYear, String presentedCvv2) {
        if (presentedCvv2 == null || presentedCvv2.isBlank() || presentedCvv2.length() != 3) {
            return false;
        }
        String expected = computeCvv2(pan, expiryMonth, expiryYear);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presentedCvv2.getBytes(StandardCharsets.UTF_8));
    }
}
