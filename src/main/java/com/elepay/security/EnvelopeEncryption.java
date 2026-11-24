package com.elepay.security;

import com.elepay.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Field-level envelope encryption (Algorithm 075).
 * Encrypts sensitive fields using per-record data keys (DEK) wrapped by master key encryption keys (KEK).
 * Uses AES-GCM-256 with 12-byte IVs, 128-bit authentication tags, and bound Additional Authenticated Data (AAD).
 */
public class EnvelopeEncryption {

    private static final String AES_GCM_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int DEK_LENGTH_BITS = 256;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public record EncryptedField(
            String keyId,
            int keyVersion,
            String ivBase64,
            String wrappedDekBase64,
            String ciphertextBase64,
            String aad
    ) {
        public String serialize() {
            return String.format("ENC:v1:%s:%d:%s:%s:%s:%s",
                    keyId,
                    keyVersion,
                    ivBase64,
                    wrappedDekBase64,
                    ciphertextBase64,
                    Base64.getEncoder().encodeToString(aad != null ? aad.getBytes(StandardCharsets.UTF_8) : new byte[0]));
        }

        public static EncryptedField deserialize(String serialized) {
            if (serialized == null || !serialized.startsWith("ENC:v1:")) {
                throw ApiException.badRequest("invalid_ciphertext", "Serialized ciphertext must start with ENC:v1:");
            }
            String[] parts = serialized.split(":", 8);
            if (parts.length != 8) {
                throw ApiException.badRequest("invalid_ciphertext", "Malformed serialized ciphertext envelope");
            }
            String keyId = parts[2];
            int keyVersion;
            String ivBase64 = parts[4];
            String wrappedDekBase64 = parts[5];
            String ciphertextBase64 = parts[6];
            String aad;
            try {
                keyVersion = Integer.parseInt(parts[3]);
                aad = new String(Base64.getDecoder().decode(parts[7]), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("invalid_ciphertext", "Malformed serialized ciphertext envelope");
            }
            return new EncryptedField(keyId, keyVersion, ivBase64, wrappedDekBase64, ciphertextBase64, aad);
        }
    }

    public static SecretKey generateDek() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(DEK_LENGTH_BITS, SECURE_RANDOM);
            return keyGen.generateKey();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate AES-256 DEK", e);
        }
    }

    public static EncryptedField encrypt(String plaintext, String aad, String keyId, int keyVersion, SecretKey kek) {
        if (plaintext == null) {
            return null;
        }
        try {
            // 1. Generate unique DEK
            SecretKey dek = generateDek();

            // 2. Encrypt plaintext using DEK with AES-GCM
            byte[] iv = new byte[IV_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher dataCipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            dataCipher.init(Cipher.ENCRYPT_MODE, dek, spec);
            if (aad != null && !aad.isEmpty()) {
                dataCipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }
            byte[] ciphertext = dataCipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // 3. Wrap DEK using KEK with AES-GCM
            byte[] dekIv = new byte[IV_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(dekIv);
            Cipher kekCipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            kekCipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, dekIv));
            kekCipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            byte[] wrappedDekPayload = kekCipher.doFinal(dek.getEncoded());

            // Combine dekIv + wrappedDekPayload for storage
            byte[] combinedWrappedDek = new byte[IV_LENGTH_BYTES + wrappedDekPayload.length];
            System.arraycopy(dekIv, 0, combinedWrappedDek, 0, IV_LENGTH_BYTES);
            System.arraycopy(wrappedDekPayload, 0, combinedWrappedDek, IV_LENGTH_BYTES, wrappedDekPayload.length);

            return new EncryptedField(
                    keyId,
                    keyVersion,
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(combinedWrappedDek),
                    Base64.getEncoder().encodeToString(ciphertext),
                    aad != null ? aad : ""
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to perform envelope encryption", e);
        }
    }

    public static String decrypt(EncryptedField field, SecretKey kek) {
        if (field == null) {
            return null;
        }
        try {
            // 1. Unwrap DEK using KEK
            byte[] combinedWrappedDek = Base64.getDecoder().decode(field.wrappedDekBase64());
            byte[] dekIv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combinedWrappedDek, 0, dekIv, 0, IV_LENGTH_BYTES);
            byte[] wrappedDekPayload = new byte[combinedWrappedDek.length - IV_LENGTH_BYTES];
            System.arraycopy(combinedWrappedDek, IV_LENGTH_BYTES, wrappedDekPayload, 0, wrappedDekPayload.length);

            Cipher kekCipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            kekCipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, dekIv));
            kekCipher.updateAAD(field.keyId().getBytes(StandardCharsets.UTF_8));
            byte[] rawDek = kekCipher.doFinal(wrappedDekPayload);
            SecretKey dek = new SecretKeySpec(rawDek, "AES");

            // 2. Decrypt ciphertext using DEK
            byte[] iv = Base64.getDecoder().decode(field.ivBase64());
            byte[] ciphertext = Base64.getDecoder().decode(field.ciphertextBase64());
            Cipher dataCipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            dataCipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            if (field.aad() != null && !field.aad().isEmpty()) {
                dataCipher.updateAAD(field.aad().getBytes(StandardCharsets.UTF_8));
            }
            byte[] plaintextBytes = dataCipher.doFinal(ciphertext);
            return new String(plaintextBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw ApiException.badRequest("decryption_failed", "Failed to decrypt envelope ciphertext");
        }
    }
}
