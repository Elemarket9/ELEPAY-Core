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
 * Pairs kitchen tablets and printer devices once, so a restarted device keeps receiving orders without a staff
 * sign-in. A manager creates a 6-digit code (valid 10 minutes); the device sends it with the merchant code and
 * receives a kitchen-only credential. Five wrong codes for a merchant cancel its open codes.
 */
@Service
public class KitchenDeviceService {
    static final int CODE_MINUTES = 10;
    static final int MAX_FAILED = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient db;
    private final ApiKeys keys;
    private final MerchantAccess merchants;
    private final KitchenStationService stations;

    public KitchenDeviceService(JdbcClient db, ApiKeys keys, MerchantAccess merchants, KitchenStationService stations) {
        this.db = db;
        this.keys = keys;
        this.merchants = merchants;
        this.stations = stations;
    }

    public record PairingCode(String code, String merchantCode, UUID stationId, OffsetDateTime expiresAt) {}

    public record Paired(String apiKey, String signingSecret, UUID deviceId, UUID merchantId, String merchantName,
                         UUID stationId, String stationName, boolean hasPrinter) {}

    @Transactional
    public PairingCode createCode(UUID merchantId, UUID stationId, String createdBy) {
        String merchantCode = merchants.find(merchantId).map(MerchantAccess.Merchant::merchantCode)
                .orElseThrow(() -> ApiException.notFound("Merchant not found"));
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        OffsetDateTime expires = OffsetDateTime.now().plusMinutes(CODE_MINUTES);
        db.sql("""
            INSERT INTO restaurant_kitchen_pairing (id, merchant_id, station_id, code_hash, created_by, expires_at)
            VALUES (:id, :m, :s, :hash, :by, :exp)
            """).param("id", UUID.randomUUID()).param("m", merchantId).param("s", stationId)
                .param("hash", hash(merchantId, code)).param("by", createdBy).param("exp", expires).update();
        return new PairingCode(code, merchantCode, stationId, expires);
    }

    /** Exchanges a code for a device credential. Every failure looks the same to the caller. */
    @Transactional(noRollbackFor = ApiException.class)
    public Paired pair(String merchantCode, String code, String deviceName) {
        ApiException invalid = ApiException.badRequest("pairing_failed", "Check the merchant code and pairing code and try again");
        if (merchantCode == null || !merchantCode.matches("\\d{6}") || code == null || !code.matches("\\d{6}")) throw invalid;
        String name = deviceName == null || deviceName.isBlank() ? "Kitchen device" : deviceName.strip();
        if (name.length() > 64) name = name.substring(0, 64);
        MerchantAccess.Merchant merchant = merchants.findByCode(merchantCode).orElseThrow(() -> invalid);
        UUID merchantId = merchant.id();
        // One pairing at a time per merchant, so concurrent guesses cannot race past the attempt limit.
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended('kitchen-pairing:' || :m, 0))")
                .param("m", merchantId.toString()).query((rs, n) -> 1).single();
        Optional<UUID> match = db.sql("""
            SELECT id FROM restaurant_kitchen_pairing
            WHERE merchant_id = :m AND used_at IS NULL AND expires_at > now() AND failed_attempts < :max
              AND code_hash = :hash
            """).param("m", merchantId).param("max", MAX_FAILED).param("hash", hash(merchantId, code))
                .query(UUID.class).optional();
        if (match.isEmpty()) {
            db.sql("""
                UPDATE restaurant_kitchen_pairing SET failed_attempts = failed_attempts + 1
                WHERE merchant_id = :m AND used_at IS NULL AND expires_at > now()
                """).param("m", merchantId).update();
            throw invalid;
        }
        UUID stationId = db.sql("SELECT station_id FROM restaurant_kitchen_pairing WHERE id = :id")
                .param("id", match.get()).query(UUID.class).optional().orElse(null);
        ApiKeys.Created credential = keys.createKitchenDevice(merchantId, name, "kitchen-pairing");
        db.sql("""
            UPDATE restaurant_kitchen_pairing SET used_at = now(), credential_id = :c, device_name = :name
            WHERE id = :id AND used_at IS NULL
            """).param("c", credential.id()).param("name", name).param("id", match.get()).update();
        Optional<KitchenStationService.Station> station = stationId == null ? Optional.empty() : stations.get(stationId);
        String stationName = station.map(KitchenStationService.Station::name).orElse(null);
        boolean hasPrinter = station.map(s ->
                (s.printerAddress() != null && !s.printerAddress().isBlank())
                        || "PRINTER".equalsIgnoreCase(s.output())
                        || "BOTH".equalsIgnoreCase(s.output())
        ).orElse(false);
        return new Paired(credential.apiKey(), credential.signingSecret(), credential.id(), merchantId,
                merchant.tradingName(), stationId, stationName, hasPrinter);
    }

    /** Verify a paired kitchen credential and its station through the owning modules. */
    public boolean permitsNotificationStation(UUID merchantId, UUID credentialId, UUID stationId) {
        if (merchants.find(merchantId).filter(m -> "active".equals(m.status())).isEmpty()) return false;
        if (keys.kitchenDevices(merchantId).stream()
                .noneMatch(k -> k.id().equals(credentialId) && k.revokedAt() == null)) return false;
        return db.sql("""
                SELECT EXISTS (SELECT 1 FROM restaurant_kitchen_pairing p
                    WHERE p.credential_id = :id AND p.merchant_id = :m AND p.used_at IS NOT NULL
                      AND (p.station_id IS NULL OR p.station_id = CAST(:station AS uuid))
                      AND (CAST(:station AS uuid) IS NULL OR EXISTS (
                          SELECT 1 FROM restaurant_kitchen_station s WHERE s.id = CAST(:station AS uuid)
                            AND s.merchant_id = :m AND s.active)))
                """).param("id",credentialId).param("m",merchantId).param("station",stationId)
                .query(Boolean.class).single();
    }

    public List<ApiKeys.KitchenDevice> list(UUID merchantId) {
        return keys.kitchenDevices(merchantId);
    }

    public void revoke(UUID merchantId, UUID deviceId) {
        if (!keys.revokeKitchenDevice(merchantId, deviceId)) throw ApiException.notFound("Device not found");
    }

    private static String hash(UUID merchantId, String code) {
        return Idempotency.sha256("kitchen-pairing:" + merchantId + ":" + code);
    }
}
