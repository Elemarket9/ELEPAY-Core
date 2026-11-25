package com.elepay.merchanttools.restaurant;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.identity.ApiKeys;
import com.elepay.merchant.MerchantAccess;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pairs a waiter's phone once, like a kitchen tablet. A manager creates a 6-digit code for a named waiter
 * (valid 10 minutes); the phone sends it with the merchant code and receives a waiter-only credential bound to
 * that waiter. The credential works only while the person is still a branch-assigned waiter of the merchant,
 * and a manager can revoke it. Five wrong codes for a merchant cancel its open codes.
 */
@Service
public class WaiterDeviceService {
    static final int CODE_MINUTES = 10;
    static final int MAX_FAILED = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient db;
    private final ApiKeys keys;
    private final MerchantAccess merchants;

    public WaiterDeviceService(JdbcClient db, ApiKeys keys, MerchantAccess merchants) {
        this.db = db;
        this.keys = keys;
        this.merchants = merchants;
    }

    public record PairingCode(String code, String merchantCode, String waiterSubject, OffsetDateTime expiresAt) {}

    public record Paired(String apiKey, String signingSecret, UUID deviceId, UUID merchantId, String merchantName,
                         UUID branchId) {}

    public record Device(UUID id, String waiterSubject, String deviceName, OffsetDateTime createdAt,
                         OffsetDateTime revokedAt) {}

    /** The waiter a request is acting for: from their own sign-in, or from their paired phone. */
    public record Waiter(String subject, UUID branchId) {}

    @Transactional
    public PairingCode createCode(UUID merchantId, String waiterSubject, String createdBy) {
        String merchantCode = merchants.find(merchantId).map(MerchantAccess.Merchant::merchantCode)
                .orElseThrow(() -> ApiException.notFound("Merchant not found"));
        merchants.membership(merchantId, waiterSubject)
                .filter(m -> "waiter".equals(m.role()) && m.branchId() != null)
                .orElseThrow(() -> ApiException.badRequest("waiter_required",
                        "Add this person as a branch waiter before pairing their phone"));
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        OffsetDateTime expires = OffsetDateTime.now().plusMinutes(CODE_MINUTES);
        db.sql("""
            INSERT INTO restaurant_waiter_pairing (id, merchant_id, waiter_subject, code_hash, created_by, expires_at)
            VALUES (:id, :m, :w, :hash, :by, :exp)
            """).param("id", UUID.randomUUID()).param("m", merchantId).param("w", waiterSubject)
                .param("hash", hash(merchantId, code)).param("by", createdBy).param("exp", expires).update();
        return new PairingCode(code, merchantCode, waiterSubject, expires);
    }

    /** Exchanges a code for a waiter phone credential. Every failure looks the same to the caller. */
    @Transactional(noRollbackFor = ApiException.class)
    public Paired pair(String merchantCode, String code, String deviceName) {
        ApiException invalid = ApiException.badRequest("pairing_failed", "Check the merchant code and pairing code and try again");
        if (merchantCode == null || !merchantCode.matches("\\d{6}") || code == null || !code.matches("\\d{6}")) throw invalid;
        String name = deviceName == null || deviceName.isBlank() ? "Waiter phone" : deviceName.strip();
        if (name.length() > 64) name = name.substring(0, 64);
        MerchantAccess.Merchant merchant = merchants.findByCode(merchantCode).orElseThrow(() -> invalid);
        UUID merchantId = merchant.id();
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended('waiter-pairing:' || :m, 0))")
                .param("m", merchantId.toString()).query((rs, n) -> 1).single();
        record Match(UUID id, String waiterSubject) {}
        Optional<Match> match = db.sql("""
            SELECT id, waiter_subject FROM restaurant_waiter_pairing
            WHERE merchant_id = :m AND used_at IS NULL AND expires_at > now() AND failed_attempts < :max
              AND code_hash = :hash
            """).param("m", merchantId).param("max", MAX_FAILED).param("hash", hash(merchantId, code))
                .query(Match.class).optional();
        if (match.isEmpty()) {
            db.sql("""
                UPDATE restaurant_waiter_pairing SET failed_attempts = failed_attempts + 1
                WHERE merchant_id = :m AND used_at IS NULL AND expires_at > now()
                """).param("m", merchantId).update();
            throw invalid;
        }
        var member = merchants.membership(merchantId, match.get().waiterSubject())
                .filter(m -> "waiter".equals(m.role()) && m.branchId() != null)
                .orElseThrow(() -> invalid);
        ApiKeys.Created credential = keys.createWaiterDevice(merchantId, name, "waiter-pairing");
        db.sql("""
            UPDATE restaurant_waiter_pairing SET used_at = now(), credential_id = :c
            WHERE id = :id AND used_at IS NULL
            """).param("c", credential.id()).param("id", match.get().id()).update();
        db.sql("""
            INSERT INTO restaurant_waiter_device (credential_id, merchant_id, waiter_subject, device_name)
            VALUES (:c, :m, :w, :name)
            """).param("c", credential.id()).param("m", merchantId).param("w", match.get().waiterSubject())
                .param("name", name).update();
        return new Paired(credential.apiKey(), credential.signingSecret(), credential.id(), merchantId,
                merchant.tradingName(), member.branchId());
    }

    /**
     * The waiter behind a paired phone, checked on every request: the device must belong to this merchant and
     * not be revoked, and its person must still be a branch-assigned waiter here (removal ends access at once).
     */
    public Optional<Waiter> resolve(UUID merchantId, UUID credentialId) {
        if (credentialId == null) return Optional.empty();
        return db.sql("""
            SELECT waiter_subject FROM restaurant_waiter_device
            WHERE credential_id = :c AND merchant_id = :m AND revoked_at IS NULL
            """).param("c", credentialId).param("m", merchantId).query(String.class).optional()
                .flatMap(subject -> merchants.membership(merchantId, subject)
                        .filter(m -> "waiter".equals(m.role()) && m.branchId() != null)
                        .map(m -> new Waiter(subject, m.branchId())));
    }

    public List<Device> list(UUID merchantId) {
        return db.sql("""
            SELECT credential_id AS id, waiter_subject, device_name, created_at, revoked_at
            FROM restaurant_waiter_device WHERE merchant_id = :m
            ORDER BY revoked_at NULLS FIRST, created_at DESC
            """).param("m", merchantId).query(Device.class).list();
    }

    @Transactional
    public void revoke(UUID merchantId, UUID deviceId) {
        if (!keys.revokeWaiterDevice(merchantId, deviceId)) throw ApiException.notFound("Device not found");
        db.sql("UPDATE restaurant_waiter_device SET revoked_at = COALESCE(revoked_at, now()) WHERE credential_id = :c AND merchant_id = :m")
                .param("c", deviceId).param("m", merchantId).update();
    }

    private static String hash(UUID merchantId, String code) {
        return Idempotency.sha256("waiter-pairing:" + merchantId + ":" + code);
    }
}
