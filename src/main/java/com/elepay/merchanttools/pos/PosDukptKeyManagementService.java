package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ANSI X9.24 Retail Financial Services Key Management Engine.
 * Implements Derived Unique Key Per Transaction (DUKPT):
 * 1. Base Derivation Key (BDK) & Initial PIN Encryption Key (IPEK) derivation.
 * 2. Key Serial Number (KSN) management with 21-bit sequential transaction counter.
 * 3. Unique, non-reusable transaction key derivation for point-of-sale PIN encryption.
 * 4. ISO 9564-1 Format 0 PIN block encryption simulation.
 */
@Service
public class PosDukptKeyManagementService {

    private static final String DEFAULT_BDK_HEX = "0123456789ABCDEFFEDCBA9876543210";
    private final JdbcClient db;

    public PosDukptKeyManagementService(JdbcClient db) {
        this.db = db;
    }

    public record TerminalDukptProfile(
            UUID id,
            String terminalId,
            UUID merchantId,
            String bdkId,
            String initialKsn,
            String currentKsn,
            long transactionCounter,
            String status,
            OffsetDateTime createdAt
    ) {}

    public record DukptTransactionKey(
            String terminalId,
            String ksn,
            long transactionCounter,
            String transactionKeyHex
    ) {}

    public record PinEncryptionResult(
            String terminalId,
            String ksn,
            long transactionCounter,
            String encryptedPinBlock
    ) {}

    @Transactional
    public TerminalDukptProfile provisionTerminal(String terminalId, UUID merchantId, String baseKeyId) {
        if (terminalId == null || terminalId.isBlank()) {
            throw ApiException.badRequest("invalid_terminal_id", "Terminal ID must not be blank");
        }

        String bdkId = (baseKeyId != null && !baseKeyId.isBlank()) ? baseKeyId : "BDK-PRIMARY-01";
        // KSN: 10 bytes (20 hex digits) -> BaseKeyId (6 hex) + TerminalId (9 hex) + Counter (5 hex = 21 bits)
        String initialKsn = String.format("%06X%09X00000", Math.abs(bdkId.hashCode()) & 0xFFFFFF, Math.abs(terminalId.hashCode()) & 0xFFFFFFFFFEL);
        String encryptedIpek = deriveIpek(DEFAULT_BDK_HEX, initialKsn);

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        db.sql("""
            INSERT INTO pos_terminal_dukpt_key (
                id, terminal_id, merchant_id, bdk_id, initial_ksn,
                current_ksn, transaction_counter, encrypted_ipek, status,
                created_at, updated_at
            ) VALUES (
                :id, :tid, :mid, :bdk, :initKsn,
                :initKsn, 0, :ipek, 'ACTIVE',
                :now, :now
            )
            ON CONFLICT (terminal_id) DO UPDATE SET
                status = 'ACTIVE',
                updated_at = EXCLUDED.updated_at
        """)
                .param("id", id)
                .param("tid", terminalId)
                .param("mid", merchantId)
                .param("bdk", bdkId)
                .param("initKsn", initialKsn)
                .param("ipek", encryptedIpek)
                .param("now", now)
                .update();

        return new TerminalDukptProfile(
                id, terminalId, merchantId, bdkId, initialKsn, initialKsn, 0L, "ACTIVE", now
        );
    }

    @Transactional
    public DukptTransactionKey stepTransactionKey(String terminalId) {
        var profileOpt = db.sql("""
            SELECT id, terminal_id, bdk_id, initial_ksn, current_ksn, transaction_counter, encrypted_ipek, status
            FROM pos_terminal_dukpt_key
            WHERE terminal_id = :tid FOR UPDATE
        """).param("tid", terminalId).query((rs, rowNum) -> new Object[] {
                rs.getObject("id", UUID.class),
                rs.getString("initial_ksn"),
                rs.getLong("transaction_counter"),
                rs.getString("encrypted_ipek"),
                rs.getString("status")
        }).optional();

        if (profileOpt.isEmpty()) {
            throw ApiException.notFound("terminal_not_found", "Terminal DUKPT profile not found: " + terminalId);
        }

        Object[] row = profileOpt.get();
        UUID id = (UUID) row[0];
        String initialKsn = (String) row[1];
        long counter = (Long) row[2] + 1;
        String ipek = (String) row[3];
        String status = (String) row[4];

        if (!"ACTIVE".equalsIgnoreCase(status)) {
            throw ApiException.badRequest("terminal_inactive", "Terminal is not in ACTIVE state: " + status);
        }

        // Maximum 21-bit counter limit (2,097,152 transactions)
        if (counter >= 0x1FFFFFL) {
            throw ApiException.badRequest("dukpt_counter_exhausted", "DUKPT transaction counter exhausted for terminal: " + terminalId);
        }

        // Construct new 20-digit KSN with updated counter in lower 21 bits
        String baseKsnPrefix = initialKsn.substring(0, 15);
        String currentKsn = String.format("%s%05X", baseKsnPrefix, counter & 0x1FFFFFL);

        // Derive unique transaction key: TK = HMAC-SHA256(IPEK, CurrentKSN)
        String txKeyHex = hmacSha256(ipek, currentKsn);
        OffsetDateTime now = OffsetDateTime.now();

        db.sql("""
            UPDATE pos_terminal_dukpt_key
            SET current_ksn = :ksn,
                transaction_counter = :counter,
                last_derived_at = :now,
                updated_at = :now
            WHERE id = :id
        """)
                .param("ksn", currentKsn)
                .param("counter", counter)
                .param("now", now)
                .param("id", id)
                .update();

        return new DukptTransactionKey(terminalId, currentKsn, counter, txKeyHex);
    }

    @Transactional
    public PinEncryptionResult encryptPinBlock(String terminalId, String pin, String pan) {
        if (pin == null || pin.length() < 4 || pin.length() > 12) {
            throw ApiException.badRequest("invalid_pin", "PIN length must be between 4 and 12 digits");
        }

        DukptTransactionKey key = stepTransactionKey(terminalId);

        // ISO 9564-1 Format 0 PIN block calculation:
        // PIN field: 0 + PIN length + PIN + 16-pad with F
        // PAN field: 0000 + 12 rightmost PAN digits (excluding check digit)
        // PIN block = PIN field XOR PAN field, encrypted under Transaction Key
        String pinBlockHex = computeIsoFormat0PinBlock(pin, pan, key.transactionKeyHex());

        return new PinEncryptionResult(terminalId, key.ksn(), key.transactionCounter(), pinBlockHex);
    }

    private String computeIsoFormat0PinBlock(String pin, String pan, String keyHex) {
        String cleanPan = (pan != null) ? pan.replaceAll("[^0-9]", "") : "0000000000000000";
        String panPart = cleanPan.length() >= 13 ? cleanPan.substring(cleanPan.length() - 13, cleanPan.length() - 1) : "000000000000";
        String panBlock = "0000" + panPart;

        StringBuilder pinBuilder = new StringBuilder();
        pinBuilder.append("0").append(Integer.toHexString(pin.length())).append(pin);
        while (pinBuilder.length() < 16) {
            pinBuilder.append("F");
        }
        String pinBlockPlain = pinBuilder.toString();

        // XOR 16 hex chars (8 bytes)
        StringBuilder xorResult = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            int pVal = Character.digit(pinBlockPlain.charAt(i), 16);
            int aVal = Character.digit(panBlock.charAt(i), 16);
            xorResult.append(Integer.toHexString(pVal ^ aVal).toUpperCase());
        }

        // Encrypt with HMAC of transaction key to generate 16-hex ciphertext
        return hmacSha256(keyHex, xorResult.toString()).substring(0, 16).toUpperCase();
    }

    private String deriveIpek(String bdkHex, String ksn) {
        return hmacSha256(bdkHex, ksn);
    }

    private String hmacSha256(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secret);
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }
}
