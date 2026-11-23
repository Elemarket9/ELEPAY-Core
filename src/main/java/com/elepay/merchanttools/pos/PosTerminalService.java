package com.elepay.merchanttools.pos;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import com.elepay.emtech.EmtechReporter;
import com.elepay.emtech.GhanaLocalities;
import com.elepay.emtech.RegulatoryFacts;
import com.elepay.emtech.ServerSidePlaces;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosTerminalService {
    private final JdbcClient db;
    private final EmtechReporter emtech;

    public PosTerminalService(JdbcClient db, EmtechReporter emtech) {
        this.db = db;
        this.emtech = emtech;
    }

    /** Shop location captured once at device registration. Not a customer's GPS fix. */
    public record RegulatoryProfile(String city, String region, String country, String address, String location,
                                    String latitude, String longitude, String connectionType, String manufacturer,
                                    Integer manufactureYear, String deviceName, String deviceDescription) {}

    public record PosTerminal(
            UUID id,
            UUID merchantId,
            UUID branchId,
            String serialNumber,
            String model,
            @com.fasterxml.jackson.annotation.JsonIgnore String pairingCode,
            @com.fasterxml.jackson.annotation.JsonIgnore String secretKey,
            String hardwareFingerprint,
            String status,
            OffsetDateTime pairedAt,
            OffsetDateTime createdAt
    ) {}

    public record PairingResult(
            UUID terminalId,
            String serialNumber,
            String status,
            String authToken
    ) {}

    @Transactional
    public PosTerminal registerTerminal(UUID merchantId, UUID branchId, String serialNumber, String model) {
        return registerTerminal(merchantId, branchId, serialNumber, model, null);
    }

    @Transactional
    public PosTerminal registerTerminal(UUID merchantId, UUID branchId, String serialNumber, String model,
                                        RegulatoryProfile profile) {
        UUID id = UUID.randomUUID();
        String pairingCode = String.format("%06d", new java.security.SecureRandom().nextInt(1_000_000));
        String secretKey = UUID.randomUUID().toString().replace("-", "");

        var place = profile == null ? null : ServerSidePlaces.Place.of(profile.city(), profile.region(), profile.country(), "TERMINAL");
        db.sql("""
            INSERT INTO pos_terminal (
                id, merchant_id, branch_id, serial_number, model,
                pairing_code, secret_key, status, created_at,
                city, region, country, address_line, location_description,
                latitude, longitude, connection_type, manufacturer, manufacture_year,
                device_name, device_description, inservice_at
            ) VALUES (
                :id, :merchantId, :branchId, :serialNumber, :model,
                :pairingCode, :secretKey, 'INACTIVE', now(),
                :city, :region, :country, :address, :location,
                :lat, :lon, :connection, :manufacturer, :year,
                :deviceName, :description, now()
            )
            """)
            .param("id", id)
            .param("merchantId", merchantId)
            .param("branchId", branchId)
            .param("serialNumber", serialNumber)
            .param("model", model)
            .param("pairingCode", pairingCode)
            .param("secretKey", secretKey)
            .param("city", place == null ? null : place.city())
            .param("region", place == null ? null : place.region())
            .param("country", place == null ? "GH" : place.country())
            .param("address", profile == null ? null : profile.address())
            .param("location", profile == null ? null : profile.location())
            .param("lat", profile == null || profile.latitude() == null ? null : new java.math.BigDecimal(profile.latitude()))
            .param("lon", profile == null || profile.longitude() == null ? null : new java.math.BigDecimal(profile.longitude()))
            .param("connection", profile == null ? null : profile.connectionType())
            .param("manufacturer", profile == null ? null : profile.manufacturer())
            .param("year", profile == null ? null : profile.manufactureYear())
            .param("deviceName", profile == null ? null : profile.deviceName())
            .param("description", profile == null ? null : profile.deviceDescription())
            .update();
        report(id);
        return getTerminal(id).orElseThrow();
    }

    @Transactional
    public PosTerminal saveRegulatory(UUID terminalId, RegulatoryProfile profile) {
        var place = ServerSidePlaces.Place.of(profile.city(), profile.region(), profile.country(), "TERMINAL");
        if (place.city() == null || profile.latitude() == null || profile.longitude() == null || profile.connectionType() == null) {
            throw com.elepay.common.ApiException.badRequest("device_location_required",
                    "Device registration needs city, country, connection type and the shop's latitude and longitude");
        }
        int updated = db.sql("""
                UPDATE pos_terminal SET city = :city, region = :region, country = :country,
                    address_line = :address, location_description = :location,
                    latitude = :lat, longitude = :lon, connection_type = :connection,
                    manufacturer = :manufacturer, manufacture_year = :year,
                    device_name = coalesce(:deviceName, device_name),
                    device_description = :description,
                    inservice_at = coalesce(inservice_at, created_at)
                WHERE id = :id""")
                .param("city", place.city()).param("region", place.region() == null
                        ? GhanaLocalities.region(place.city()).orElse(null) : place.region())
                .param("country", place.country()).param("address", profile.address())
                .param("location", profile.location())
                .param("lat", new java.math.BigDecimal(profile.latitude()))
                .param("lon", new java.math.BigDecimal(profile.longitude()))
                .param("connection", profile.connectionType())
                .param("manufacturer", profile.manufacturer()).param("year", profile.manufactureYear())
                .param("deviceName", profile.deviceName()).param("description", profile.deviceDescription())
                .param("id", terminalId).update();
        if (updated == 0) throw com.elepay.common.ApiException.notFound("Terminal not found");
        report(terminalId);
        return getTerminal(terminalId).orElseThrow();
    }

    private void report(UUID terminalId) {
        var row = db.sql("""
                SELECT city, region, country, address_line, location_description, latitude, longitude,
                       connection_type, manufacturer, manufacture_year, device_name, device_description,
                       model, device_type, coalesce(inservice_at, paired_at, created_at)
                FROM pos_terminal WHERE id = :id""")
                .param("id", terminalId)
                .query((rs, i) -> new RegulatoryFacts.Device(
                        terminalId.toString(), rs.getString(14), rs.getString(11), rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getBigDecimal(6) == null ? null : rs.getBigDecimal(6).stripTrailingZeros().toPlainString(),
                        rs.getBigDecimal(7) == null ? null : rs.getBigDecimal(7).stripTrailingZeros().toPlainString(),
                        rs.getString(13), rs.getString(9), rs.getString(12),
                        com.elepay.emtech.EmtechReporter.instant(rs.getObject(15, java.time.OffsetDateTime.class)),
                        rs.getString(8), (Integer) rs.getObject(10)))
                .optional().orElse(null);
        if (row != null) emtech.device(row);
    }

    public Optional<PosTerminal> getTerminal(UUID id) {
        return db.sql("""
            SELECT id, merchant_id, branch_id, serial_number, model,
                   pairing_code, secret_key, hardware_fingerprint, status,
                   paired_at, created_at
            FROM pos_terminal WHERE id = :id
            """)
            .param("id", id)
            .query(PosTerminal.class)
            .optional();
    }

    public Optional<PosTerminal> findBySerialNumber(String serialNumber) {
        return db.sql("""
            SELECT id, merchant_id, branch_id, serial_number, model,
                   pairing_code, secret_key, hardware_fingerprint, status,
                   paired_at, created_at
            FROM pos_terminal WHERE serial_number = :serialNumber
            """)
            .param("serialNumber", serialNumber)
            .query(PosTerminal.class)
            .optional();
    }

    public List<PosTerminal> listTerminals(UUID merchantId) {
        return db.sql("""
            SELECT id, merchant_id, branch_id, serial_number, model,
                   pairing_code, secret_key, hardware_fingerprint, status,
                   paired_at, created_at
            FROM pos_terminal WHERE merchant_id = :merchantId
            ORDER BY created_at DESC
            """)
            .param("merchantId", merchantId)
            .query(PosTerminal.class)
            .list();
    }

    @Transactional
    public PairingResult pairTerminal(String serialNumber, String pairingCode, String hardwareFingerprint) {
        PosTerminal terminal = findBySerialNumber(serialNumber)
                .orElseThrow(() -> new IllegalArgumentException("Terminal not found with serial: " + serialNumber));

        if (!"INACTIVE".equals(terminal.status()) || terminal.createdAt().isBefore(OffsetDateTime.now().minusMinutes(10))
                || pairingCode == null || !java.security.MessageDigest.isEqual(pairingCode.getBytes(java.nio.charset.StandardCharsets.UTF_8), terminal.pairingCode().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid pairing code");
        }

        int paired = db.sql("""
            UPDATE pos_terminal
            SET status = 'ACTIVE', hardware_fingerprint = :fp, paired_at = now()
            WHERE id = :id AND status = 'INACTIVE'
            """)
            .param("fp", hardwareFingerprint)
            .param("id", terminal.id())
            .update();

        if (paired != 1) throw new IllegalStateException("Terminal pairing was already used");
        String authToken = "POS_" + UUID.randomUUID().toString().replace("-", "") + "_" + terminal.secretKey().substring(0, 8);
        return new PairingResult(terminal.id(), serialNumber, "ACTIVE", authToken);
    }

    @Transactional
    public PosTerminal updateStatus(UUID terminalId, String status) {
        db.sql("""
            UPDATE pos_terminal
            SET status = :status
            WHERE id = :id
            """)
            .param("status", status)
            .param("id", terminalId)
            .update();

        return getTerminal(terminalId).orElseThrow();
    }
}
