package com.elepay.cards;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * EMVCo Network Tokenization Boundary and Lifecycle Management.
 * Manages token provisioning, dynamic EMV cryptograms, expiry management, and status transitions (ACTIVE, SUSPENDED, REVOKED).
 */
@Service
public class NetworkTokenService {

    private final JdbcClient db;
    private final SecureRandom rng = new SecureRandom();
    private final byte[] tokenCryptogramKey;

    public record CryptogramResult(
            String cryptogram,
            String eci,
            long atc
    ) {}

    public NetworkTokenService(JdbcClient db) {
        this.db = db;
        this.tokenCryptogramKey = deriveCryptogramKey();
        initTable();
    }

    private byte[] deriveCryptogramKey() {
        try {
            return MessageDigest.getInstance("SHA-256").digest("elepay:network-token:tck:v1".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void initTable() {
        db.sql("""
            CREATE TABLE IF NOT EXISTS network_card_token (
                id                  uuid PRIMARY KEY,
                card_id             uuid NOT NULL REFERENCES card_registry(id) ON DELETE CASCADE,
                token_pan           varchar(19) NOT NULL UNIQUE,
                token_requester_id  varchar(64) NOT NULL,
                device_id           varchar(128),
                expiry_month        integer NOT NULL,
                expiry_year         integer NOT NULL,
                status              varchar(32) NOT NULL DEFAULT 'ACTIVE',
                created_at          timestamptz NOT NULL DEFAULT now(),
                updated_at          timestamptz NOT NULL DEFAULT now()
            )
        """).update();
        db.sql("CREATE INDEX IF NOT EXISTS idx_net_token_card ON network_card_token(card_id)").update();
        db.sql("CREATE INDEX IF NOT EXISTS idx_net_token_pan ON network_card_token(token_pan)").update();
    }

    @Transactional
    public NetworkToken provisionToken(UUID cardId, String tokenRequesterId, String deviceId) {
        var cardOpt = db.sql("""
            SELECT id, scheme, expiry_month, expiry_year, status
            FROM card_registry WHERE id = ?
        """).param(cardId).query((rs, rowNum) -> Map.of(
                "scheme", rs.getString("scheme"),
                "expiryMonth", rs.getInt("expiry_month"),
                "expiryYear", rs.getInt("expiry_year"),
                "status", rs.getString("status")
        )).optional();

        if (cardOpt.isEmpty()) {
            throw new IllegalArgumentException("Card not found: " + cardId);
        }

        var cardInfo = cardOpt.get();
        if (!"ACTIVE".equalsIgnoreCase((String) cardInfo.get("status"))) {
            throw new IllegalStateException("Cannot provision token for non-active card: " + cardInfo.get("status"));
        }

        String scheme = (String) cardInfo.get("scheme");
        int expiryMonth = (Integer) cardInfo.get("expiryMonth");
        int expiryYear = (Integer) cardInfo.get("expiryYear");

        // Generate 16-digit Token PAN with designated Token BIN
        String tokenBin = "VISA".equalsIgnoreCase(scheme) ? "480001" : "580001";
        StringBuilder sb = new StringBuilder(tokenBin);
        for (int i = 0; i < 9; i++) {
            sb.append(rng.nextInt(10));
        }
        int checkDigit = CardPanVault.calculateLuhnCheckDigit(sb.toString());
        String tokenPan = sb.append(checkDigit).toString();

        UUID tokenId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        db.sql("""
            INSERT INTO network_card_token
            (id, card_id, token_pan, token_requester_id, device_id, expiry_month, expiry_year, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
        """).params(tokenId, cardId, tokenPan, tokenRequesterId, deviceId, expiryMonth, expiryYear, now, now)
        .update();

        return new NetworkToken(
                tokenId, cardId, tokenPan, tokenRequesterId, deviceId,
                expiryMonth, expiryYear, TokenStatus.ACTIVE, now, now
        );
    }

    @Transactional
    public void suspendToken(UUID tokenId, String reason) {
        var token = getTokenById(tokenId).orElseThrow(() -> new IllegalArgumentException("Token not found: " + tokenId));
        if (token.status() == TokenStatus.REVOKED) {
            throw new IllegalStateException("Cannot suspend a REVOKED token");
        }
        db.sql("UPDATE network_card_token SET status = 'SUSPENDED', updated_at = now() WHERE id = ?")
                .param(tokenId).update();
    }

    @Transactional
    public void resumeToken(UUID tokenId) {
        var token = getTokenById(tokenId).orElseThrow(() -> new IllegalArgumentException("Token not found: " + tokenId));
        if (token.status() != TokenStatus.SUSPENDED) {
            throw new IllegalStateException("Token is not in SUSPENDED state: " + token.status());
        }
        db.sql("UPDATE network_card_token SET status = 'ACTIVE', updated_at = now() WHERE id = ?")
                .param(tokenId).update();
    }

    @Transactional
    public void revokeToken(UUID tokenId, String reason) {
        int rows = db.sql("UPDATE network_card_token SET status = 'REVOKED', updated_at = now() WHERE id = ?")
                .param(tokenId).update();
        if (rows == 0) {
            throw new IllegalArgumentException("Token not found: " + tokenId);
        }
    }

    @Transactional
    public void updateExpiry(UUID tokenId, int newMonth, int newYear) {
        if (newMonth < 1 || newMonth > 12) {
            throw new IllegalArgumentException("Invalid expiry month: " + newMonth);
        }
        YearMonth ym = YearMonth.of(newYear, newMonth);
        if (ym.isBefore(YearMonth.now())) {
            throw new IllegalArgumentException("Cannot update token with past expiry: " + ym);
        }
        var token = getTokenById(tokenId).orElseThrow(() -> new IllegalArgumentException("Token not found: " + tokenId));
        if (token.status() == TokenStatus.REVOKED) {
            throw new IllegalStateException("Cannot update expiry of REVOKED token");
        }
        db.sql("UPDATE network_card_token SET expiry_month = ?, expiry_year = ?, updated_at = now() WHERE id = ?")
                .params(newMonth, newYear, tokenId).update();
    }

    public CryptogramResult generateCryptogram(UUID tokenId, long amountPesewas, String currency, long atc, byte[] unpredictableNumber) {
        var token = getTokenById(tokenId).orElseThrow(() -> new IllegalArgumentException("Token not found: " + tokenId));
        if (token.status() != TokenStatus.ACTIVE) {
            throw new IllegalStateException("Cannot generate cryptogram for non-active token: " + token.status());
        }
        YearMonth expiry = YearMonth.of(token.expiryYear(), token.expiryMonth());
        if (expiry.isBefore(YearMonth.now())) {
            throw new IllegalStateException("Cannot generate cryptogram for expired token: " + expiry);
        }

        String nonceHex = unpredictableNumber != null ? HexFormat.of().formatHex(unpredictableNumber) : "00000000";
        String cryptogramInput = token.tokenPan() + ":" + amountPesewas + ":" + (currency != null ? currency : "GHS")
                + ":" + atc + ":" + nonceHex;

        byte[] hmac = hmacSha256(tokenCryptogramKey, cryptogramInput.getBytes(StandardCharsets.UTF_8));
        // EMVCo TAVV 20-byte base64 encoded cryptogram (28 chars)
        byte[] cryptogramBytes = Arrays.copyOf(hmac, 20);
        String cryptogram = Base64.getEncoder().encodeToString(cryptogramBytes);

        // Electronic Commerce Indicator: "05" indicates full authentication
        return new CryptogramResult(cryptogram, "05", atc);
    }

    public boolean validateCryptogram(UUID tokenId, long amountPesewas, String currency, long atc, byte[] unpredictableNumber, String submittedCryptogram) {
        if (submittedCryptogram == null || submittedCryptogram.isBlank()) {
            return false;
        }
        try {
            CryptogramResult expected = generateCryptogram(tokenId, amountPesewas, currency, atc, unpredictableNumber);
            return MessageDigest.isEqual(
                    expected.cryptogram().getBytes(StandardCharsets.UTF_8),
                    submittedCryptogram.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }

    public Optional<NetworkToken> findByTokenPan(String tokenPan) {
        return db.sql("""
            SELECT id, card_id, token_pan, token_requester_id, device_id,
                   expiry_month, expiry_year, status, created_at, updated_at
            FROM network_card_token
            WHERE token_pan = ?
        """).param(tokenPan).query(this::mapRecord).optional();
    }

    public Optional<NetworkToken> getTokenById(UUID tokenId) {
        return db.sql("""
            SELECT id, card_id, token_pan, token_requester_id, device_id,
                   expiry_month, expiry_year, status, created_at, updated_at
            FROM network_card_token
            WHERE id = ?
        """).param(tokenId).query(this::mapRecord).optional();
    }

    public List<NetworkToken> listTokensForCard(UUID cardId) {
        return db.sql("""
            SELECT id, card_id, token_pan, token_requester_id, device_id,
                   expiry_month, expiry_year, status, created_at, updated_at
            FROM network_card_token
            WHERE card_id = ?
            ORDER BY created_at DESC
        """).param(cardId).query(this::mapRecord).list();
    }

    private NetworkToken mapRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new NetworkToken(
                rs.getObject("id", UUID.class),
                rs.getObject("card_id", UUID.class),
                rs.getString("token_pan"),
                rs.getString("token_requester_id"),
                rs.getString("device_id"),
                rs.getInt("expiry_month"),
                rs.getInt("expiry_year"),
                TokenStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int calculateLuhnCheckDigit(String numberWithoutCheck) {
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
}
