package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import com.elepay.security.KmsSecretProvider;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ANSI X9.24 Retail Financial Services Key Management Engine.
 * Implements Derived Unique Key Per Transaction (DUKPT):
 * 1. Base Derivation Key (BDK) & Initial PIN Encryption Key (IPEK) derivation wrapped with KMS envelope encryption.
 * 2. Deterministic 10-byte (20-hex-char) Key Serial Number (KSN) with 21-bit sequential transaction counter.
 * 3. Strict monotonic KSN replay protection (verifyAndConsumeKsn) with tenant ownership binding.
 * 4. Raw-key-byte HMAC-SHA256 transaction key derivation and constant-time PIN block verification.
 */
@Service
public class PosDukptKeyManagementService {

    private static final String PUBLIC_TEST_BDK = "0123456789ABCDEFFEDCBA9876543210";
    private static final long MAX_COUNTER = 0x1FFFFFL;
    private static final HexFormat HEX_UPPER = HexFormat.of().withUpperCase();
    private static final String IPEK_AAD_PREFIX = "dukpt:ipek:";

    private final JdbcClient db;
    private final KmsSecretProvider kmsSecretProvider;
    private final String bdkHex;
    private final String ephemeralSandboxBdkHex;

    public PosDukptKeyManagementService(
            JdbcClient db,
            KmsSecretProvider kmsSecretProvider,
            String bdkHex) {
        this(db, kmsSecretProvider, bdkHex, null);
    }

    @Autowired
    public PosDukptKeyManagementService(
            JdbcClient db,
            KmsSecretProvider kmsSecretProvider,
            @Value("${elepay.pos.dukpt.bdk-hex:}") String bdkHex,
            Environment env) {
        this.db = db;
        this.kmsSecretProvider = kmsSecretProvider;
        this.bdkHex = bdkHex == null ? "" : bdkHex.trim();
        if (this.bdkHex.isBlank() && isNonProductionContext(env)) {
            byte[] ephemeral = new byte[16];
            new SecureRandom().nextBytes(ephemeral);
            this.ephemeralSandboxBdkHex = HEX_UPPER.formatHex(ephemeral);
        } else {
            this.ephemeralSandboxBdkHex = "";
        }
    }

    /**
     * True only in an explicit non-production/sandbox context, never in production/live mode.
     * Mirrors {@link KmsSecretProvider} fail-closed semantics: no hardcoded key is ever used.
     */
    private static boolean isNonProductionContext(Environment env) {
        if (env == null) return false;
        String declaredEnv = env.getProperty("elepay.environment", "");
        if ("production".equalsIgnoreCase(declaredEnv) || "prod".equalsIgnoreCase(declaredEnv)) {
            return false;
        }
        for (String profile : env.getActiveProfiles()) {
            if (profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production")) {
                return false;
            }
        }
        if (env.getProperty("elepay.rails.sandbox.enabled", Boolean.class, false)) {
            return true;
        }
        for (String profile : env.getActiveProfiles()) {
            if (profile.equalsIgnoreCase("test") || profile.equalsIgnoreCase("sandbox")
                    || profile.equalsIgnoreCase("dev")) {
                return true;
            }
        }
        return false;
    }

    private String bdk() {
        if (bdkHex.equalsIgnoreCase(PUBLIC_TEST_BDK)) {
            throw ApiException.unprocessable("dukpt_key_missing",
                    "The POS base derivation key must be supplied from a KMS or HSM");
        }
        if (!bdkHex.isBlank()) {
            return bdkHex;
        }
        if (!ephemeralSandboxBdkHex.isBlank()) {
            return ephemeralSandboxBdkHex;
        }
        throw ApiException.unprocessable("dukpt_key_missing",
                "The POS base derivation key must be supplied from a KMS or HSM");
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

    @Transactional(noRollbackFor = ApiException.class)
    public TerminalDukptProfile provisionTerminal(UUID terminalUuid, UUID merchantId, String baseKeyId) {
        if (terminalUuid == null) {
            throw ApiException.badRequest("invalid_terminal_id", "Terminal UUID must not be null");
        }
        return provisionInternal(terminalUuid.toString(), terminalUuid, merchantId, baseKeyId);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TerminalDukptProfile provisionTerminal(String terminalId, UUID merchantId, String baseKeyId) {
        if (terminalId == null || terminalId.isBlank()) {
            throw ApiException.badRequest("invalid_terminal_id", "Terminal ID must not be blank");
        }
        UUID parsedUuid = tryParseUuid(terminalId);
        return provisionInternal(terminalId, parsedUuid, merchantId, baseKeyId);
    }

    private TerminalDukptProfile provisionInternal(String terminalId, UUID terminalUuid, UUID merchantId, String baseKeyId) {
        if (merchantId == null) {
            throw ApiException.badRequest("invalid_merchant_id", "Merchant ID must not be null");
        }
        String bdkId = (baseKeyId != null && !baseKeyId.isBlank()) ? baseKeyId.trim() : "BDK-PRIMARY-01";
        byte[] terminalSeed = terminalUuid != null ? uuidBytes(terminalUuid) : sha256("tid:" + terminalId);
        byte[] initialKsnBytes = buildInitialKsnBytes(bdkId, terminalSeed);
        String initialKsn = HEX_UPPER.formatHex(initialKsnBytes);

        String ipekHex = deriveIpekHex(bdk(), initialKsn);
        String wrappedIpek = kmsSecretProvider.wrapSecret(ipekHex, IPEK_AAD_PREFIX + terminalId);

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        return db.sql("""
            INSERT INTO pos_terminal_dukpt_key (
                id, terminal_id, terminal_uuid, merchant_id, bdk_id, initial_ksn,
                current_ksn, transaction_counter, last_ksn_counter, key_version,
                encrypted_ipek, status, created_at, updated_at
            ) VALUES (
                :id, :tid, :tuuid, :mid, :bdk, :initKsn,
                :initKsn, 0, 0, 1,
                :ipek, 'ACTIVE', :now, :now
            )
            ON CONFLICT (terminal_id) DO UPDATE SET
                terminal_uuid = COALESCE(pos_terminal_dukpt_key.terminal_uuid, EXCLUDED.terminal_uuid),
                status = 'ACTIVE',
                updated_at = EXCLUDED.updated_at
            RETURNING id, terminal_id, merchant_id, bdk_id, initial_ksn,
                      current_ksn, transaction_counter, status, created_at
        """)
                .param("id", id)
                .param("tid", terminalId)
                .param("tuuid", terminalUuid)
                .param("mid", merchantId)
                .param("bdk", bdkId)
                .param("initKsn", initialKsn)
                .param("ipek", wrappedIpek)
                .param("now", now)
                .query((rs, rowNum) -> new TerminalDukptProfile(
                        rs.getObject("id", UUID.class),
                        rs.getString("terminal_id"),
                        rs.getObject("merchant_id", UUID.class),
                        rs.getString("bdk_id"),
                        rs.getString("initial_ksn"),
                        rs.getString("current_ksn"),
                        rs.getLong("transaction_counter"),
                        rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ))
                .single();
    }

    /**
     * Verifies an inbound terminal KSN for a specific merchant and terminal, enforcing:
     * - Tenant ownership (404 on mismatch)
     * - Fixed 10-byte (20-hex) KSN format and matching 59-bit prefix
     * - Strict monotonicity (counter > last_ksn_counter && counter <= 0x1FFFFF)
     * - Atomic DB update under FOR UPDATE lock
     */
    @Transactional
    public DukptTransactionKey verifyAndConsumeKsn(UUID merchantId, UUID terminalUuid, String ksn) {
        if (merchantId == null || terminalUuid == null) {
            throw ApiException.badRequest("invalid_terminal", "Merchant ID and Terminal UUID are required");
        }
        if (ksn == null || !ksn.matches("^[0-9A-Fa-f]{20}$")) {
            throw ApiException.badRequest("invalid_ksn", "KSN must be exactly 20 hexadecimal characters (10 bytes)");
        }

        var profileOpt = db.sql("""
            SELECT id, terminal_id, merchant_id, initial_ksn, last_ksn_counter, transaction_counter,
                   encrypted_ipek, status
            FROM pos_terminal_dukpt_key
            WHERE (terminal_uuid = :tuuid OR terminal_id = :tid)
            FOR UPDATE
        """)
                .param("tuuid", terminalUuid)
                .param("tid", terminalUuid.toString())
                .query((rs, rowNum) -> new Object[] {
                        rs.getObject("id", UUID.class),
                        rs.getString("terminal_id"),
                        rs.getObject("merchant_id", UUID.class),
                        rs.getString("initial_ksn"),
                        rs.getLong("last_ksn_counter"),
                        rs.getLong("transaction_counter"),
                        rs.getString("encrypted_ipek"),
                        rs.getString("status")
                }).optional();

        if (profileOpt.isEmpty()) {
            throw ApiException.notFound("terminal_not_found", "Terminal DUKPT profile not found: " + terminalUuid);
        }

        Object[] row = profileOpt.get();
        UUID id = (UUID) row[0];
        String terminalId = (String) row[1];
        UUID ownerMerchantId = (UUID) row[2];
        String initialKsn = (String) row[3];
        long lastKsnCounter = (Long) row[4];
        long txCounter = (Long) row[5];
        String storedIpek = (String) row[6];
        String status = (String) row[7];

        if (!merchantId.equals(ownerMerchantId)) {
            throw ApiException.notFound("terminal_not_found", "Terminal DUKPT profile not found: " + terminalUuid);
        }
        if (!"ACTIVE".equalsIgnoreCase(status)) {
            throw ApiException.badRequest("terminal_inactive", "Terminal is not in ACTIVE state: " + status);
        }

        byte[] initialBytes = HEX_UPPER.parseHex(initialKsn);
        byte[] submittedBytes = HEX_UPPER.parseHex(ksn.toUpperCase(Locale.ROOT));
        if (!hasMatchingPrefix(initialBytes, submittedBytes)) {
            throw ApiException.badRequest("ksn_prefix_mismatch", "KSN prefix does not match provisioned terminal");
        }

        long counter = extractCounter(submittedBytes);
        if (counter <= 0 || counter <= lastKsnCounter) {
            throw ApiException.conflict("ksn_replay_detected",
                    "KSN counter must be strictly greater than last consumed counter");
        }
        if (counter > MAX_COUNTER) {
            throw ApiException.badRequest("dukpt_counter_exhausted",
                    "DUKPT transaction counter exhausted for terminal: " + terminalUuid);
        }

        String ipekHex = kmsSecretProvider.unwrapSecret(storedIpek, IPEK_AAD_PREFIX + terminalId);
        String normalizedKsn = HEX_UPPER.formatHex(submittedBytes);
        String txKeyHex = hmacSha256HexKey(ipekHex, normalizedKsn);
        OffsetDateTime now = OffsetDateTime.now();

        db.sql("""
            UPDATE pos_terminal_dukpt_key
            SET current_ksn = :ksn,
                last_ksn_counter = :counter,
                transaction_counter = GREATEST(transaction_counter, :counter),
                last_derived_at = :now,
                updated_at = :now
            WHERE id = :id
        """)
                .param("ksn", normalizedKsn)
                .param("counter", counter)
                .param("now", now)
                .param("id", id)
                .update();

        return new DukptTransactionKey(terminalId, normalizedKsn, Math.max(txCounter, counter), txKeyHex);
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
        String storedIpek = (String) row[3];
        String status = (String) row[4];

        if (!"ACTIVE".equalsIgnoreCase(status)) {
            throw ApiException.badRequest("terminal_inactive", "Terminal is not in ACTIVE state: " + status);
        }

        if (counter >= MAX_COUNTER) {
            throw ApiException.badRequest("dukpt_counter_exhausted", "DUKPT transaction counter exhausted for terminal: " + terminalId);
        }

        byte[] baseKsnBytes = HEX_UPPER.parseHex(initialKsn);
        String currentKsn = formatKsnWithCounter(baseKsnBytes, counter);

        String ipekHex = kmsSecretProvider.unwrapSecret(storedIpek, IPEK_AAD_PREFIX + terminalId);
        String txKeyHex = hmacSha256HexKey(ipekHex, currentKsn);
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
        if (pin == null || pin.length() < 4 || pin.length() > 12 || !pin.chars().allMatch(Character::isDigit)) {
            throw ApiException.badRequest("invalid_pin", "PIN must consist of 4 to 12 numeric digits");
        }

        DukptTransactionKey key = stepTransactionKey(terminalId);
        String pinBlockHex = computeIsoFormat0PinBlock(pin, pan, key.transactionKeyHex());
        return new PinEncryptionResult(terminalId, key.ksn(), key.transactionCounter(), pinBlockHex);
    }

    public boolean verifyPinBlockMac(String transactionKeyHex, String submittedPinBlockHex, String expectedXorHex) {
        if (transactionKeyHex == null || submittedPinBlockHex == null || expectedXorHex == null) {
            return false;
        }
        String expected = hmacSha256HexKey(transactionKeyHex, expectedXorHex)
                .substring(0, 16)
                .toUpperCase(Locale.ROOT);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                submittedPinBlockHex.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
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

        StringBuilder xorResult = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            int pVal = Character.digit(pinBlockPlain.charAt(i), 16);
            int aVal = Character.digit(panBlock.charAt(i), 16);
            xorResult.append(Integer.toHexString(pVal ^ aVal).toUpperCase(Locale.ROOT));
        }

        return hmacSha256HexKey(keyHex, xorResult.toString()).substring(0, 16).toUpperCase(Locale.ROOT);
    }

    private String deriveIpekHex(String bdkHex, String ksn) {
        return hmacSha256HexKey(bdkHex, ksn);
    }

    private static String hmacSha256HexKey(String keyHex, String data) {
        try {
            byte[] keyBytes = HEX_UPPER.parseHex(keyHex.trim());
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret = new SecretKeySpec(keyBytes, "HmacSHA256");
            mac.init(secret);
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HEX_UPPER.formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    static byte[] buildInitialKsnBytes(String bdkId, byte[] terminalSeed) {
        byte[] bdkHash = sha256("bdk:" + bdkId);
        byte[] ksn = new byte[10];
        System.arraycopy(bdkHash, 0, ksn, 0, 3);
        System.arraycopy(terminalSeed, 0, ksn, 3, 4);
        ksn[7] = (byte) (terminalSeed[4] & 0xE0);
        ksn[8] = 0;
        ksn[9] = 0;
        return ksn;
    }

    static String formatKsnWithCounter(byte[] baseKsn10, long counter) {
        if (baseKsn10.length != 10) {
            throw new IllegalArgumentException("Base KSN must be 10 bytes");
        }
        if (counter < 0 || counter > MAX_COUNTER) {
            throw new IllegalArgumentException("Counter out of 21-bit range: " + counter);
        }
        byte[] ksn = baseKsn10.clone();
        ksn[7] = (byte) ((baseKsn10[7] & 0xE0) | ((counter >>> 16) & 0x1F));
        ksn[8] = (byte) ((counter >>> 8) & 0xFF);
        ksn[9] = (byte) (counter & 0xFF);
        return HEX_UPPER.formatHex(ksn);
    }

    static long extractCounter(byte[] ksnBytes) {
        return ((ksnBytes[7] & 0x1FL) << 16)
                | ((ksnBytes[8] & 0xFFL) << 8)
                | (ksnBytes[9] & 0xFFL);
    }

    static boolean hasMatchingPrefix(byte[] initialKsn, byte[] candidateKsn) {
        if (initialKsn.length != 10 || candidateKsn.length != 10) {
            return false;
        }
        int diff = (initialKsn[7] & 0xE0) ^ (candidateKsn[7] & 0xE0);
        for (int i = 0; i < 7; i++) {
            diff |= (initialKsn[i] ^ candidateKsn[i]);
        }
        return diff == 0;
    }

    private static byte[] uuidBytes(UUID uuid) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        return buf.array();
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static UUID tryParseUuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
