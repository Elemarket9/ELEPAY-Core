package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.emtech.EmtechReporter;
import com.elepay.emtech.GhanaLocalities;
import com.elepay.emtech.RegulatoryFacts;
import com.elepay.emtech.ServerSidePlaces;
import com.elepay.identity.ApiKeys;
import com.elepay.merchant.MerchantAccess;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosTerminalService {
    private static final int PAIRING_TTL_MINUTES = 10;
    private static final int MAX_FAILED_PAIRING_ATTEMPTS = 5;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final JdbcClient db;
    private final EmtechReporter emtech;
    private final ApiKeys apiKeys;
    private final MerchantAccess merchants;
    private final PosDukptKeyManagementService dukptService;

    public PosTerminalService(
            JdbcClient db,
            EmtechReporter emtech,
            ApiKeys apiKeys,
            MerchantAccess merchants,
            PosDukptKeyManagementService dukptService) {
        this.db = db;
        this.emtech = emtech;
        this.apiKeys = apiKeys;
        this.merchants = merchants;
        this.dukptService = dukptService;
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
            String authToken,
            UUID credentialId,
            String apiKey,
            String signingSecret,
            String initialKsn
    ) {
        public PairingResult(UUID terminalId, String serialNumber, String status, String authToken) {
            this(terminalId, serialNumber, status, authToken, null, null, null, null);
        }
    }

    @Transactional
    public PosTerminal registerTerminal(UUID merchantId, UUID branchId, String serialNumber, String model) {
        return registerTerminal(merchantId, branchId, serialNumber, model, null);
    }

    @Transactional
    public PosTerminal registerTerminal(UUID merchantId, UUID branchId, String serialNumber, String model,
                                        RegulatoryProfile profile) {
        UUID id = UUID.randomUUID();
        String pairingCode = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        String pairingCodeHash = hashPairingCode(merchantId, pairingCode);
        OffsetDateTime pairingExpiresAt = OffsetDateTime.now().plusMinutes(PAIRING_TTL_MINUTES);
        String secretKey = UUID.randomUUID().toString().replace("-", "");

        var place = profile == null ? null : ServerSidePlaces.Place.of(profile.city(), profile.region(), profile.country(), "TERMINAL");
        db.sql("""
            INSERT INTO pos_terminal (
                id, merchant_id, branch_id, serial_number, model,
                pairing_code, pairing_code_hash, pairing_expires_at, failed_pairing_attempts,
                secret_key, status, created_at,
                city, region, country, address_line, location_description,
                latitude, longitude, connection_type, manufacturer, manufacture_year,
                device_name, device_description, inservice_at
            ) VALUES (
                :id, :merchantId, :branchId, :serialNumber, :model,
                NULL, :pairingCodeHash, :pairingExpiresAt, 0,
                :secretKey, 'INACTIVE', now(),
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
            .param("pairingCodeHash", pairingCodeHash)
            .param("pairingExpiresAt", pairingExpiresAt)
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
        PosTerminal persisted = getTerminal(id).orElseThrow();
        return new PosTerminal(
                persisted.id(), persisted.merchantId(), persisted.branchId(),
                persisted.serialNumber(), persisted.model(), pairingCode,
                persisted.secretKey(), persisted.hardwareFingerprint(),
                persisted.status(), persisted.pairedAt(), persisted.createdAt());
    }

    @Transactional
    public PosTerminal saveRegulatory(UUID terminalId, RegulatoryProfile profile) {
        var place = ServerSidePlaces.Place.of(profile.city(), profile.region(), profile.country(), "TERMINAL");
        if (place.city() == null || profile.latitude() == null || profile.longitude() == null || profile.connectionType() == null) {
            throw ApiException.badRequest("device_location_required",
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
        if (updated == 0) throw ApiException.notFound("Terminal not found");
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
                        EmtechReporter.instant(rs.getObject(15, OffsetDateTime.class)),
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

    @Transactional(noRollbackFor = ApiException.class)
    public PairingResult pairTerminal(String serialNumber, String pairingCode, String hardwareFingerprint) {
        return pairTerminalInternal(null, serialNumber, pairingCode, hardwareFingerprint, null, null, null, false);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public PairingResult pairTerminal(
            String merchantCode,
            String serialNumber,
            String pairingCode,
            String hardwareFingerprint,
            String attestationPublicKey,
            String attestationFormat,
            String attestationSignatureBase64) {
        return pairTerminalInternal(
                merchantCode, serialNumber, pairingCode, hardwareFingerprint,
                attestationPublicKey, attestationFormat, attestationSignatureBase64, true);
    }

    private PairingResult pairTerminalInternal(
            String merchantCode,
            String serialNumber,
            String pairingCode,
            String hardwareFingerprint,
            String attestationPublicKey,
            String attestationFormat,
            String attestationSignatureBase64,
            boolean requireAttestation) {
        if (serialNumber == null || serialNumber.isBlank()) {
            throw ApiException.badRequest("invalid_serial", "Terminal serial number is required");
        }
        PosTerminal terminal = findBySerialNumber(serialNumber)
                .orElseThrow(() -> ApiException.notFound("terminal_not_found", "Terminal not found with serial: " + serialNumber));
        UUID merchantId = terminal.merchantId();

        if (requireAttestation) {
            if (merchantCode == null || !merchantCode.matches("\\d{6}")) {
                throw ApiException.badRequest("pairing_failed", "Invalid merchant code or pairing code");
            }
            MerchantAccess.Merchant merchant = merchants.findByCode(merchantCode)
                    .orElseThrow(() -> ApiException.badRequest("pairing_failed", "Invalid merchant code or pairing code"));
            if (!merchantId.equals(merchant.id())) {
                throw ApiException.badRequest("pairing_failed", "Invalid merchant code or pairing code");
            }
            String canonicalChallenge = "pos-pair:" + merchantCode + ":" + serialNumber + ":" + (pairingCode == null ? "" : pairingCode);
            verifyEcdsaP256Attestation(attestationPublicKey, attestationSignatureBase64, canonicalChallenge);
        }

        // Serialize pairing attempts per merchant so concurrent guesses cannot bypass the 5-attempt lockout
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended('pos-pairing:' || :m, 0))")
                .param("m", merchantId.toString())
                .query((rs, n) -> 1)
                .single();

        record PairingState(String status, String pairingCodeHash, OffsetDateTime pairingExpiresAt, int failedAttempts) {}
        PairingState state = db.sql("""
            SELECT status, pairing_code_hash, pairing_expires_at, failed_pairing_attempts
            FROM pos_terminal
            WHERE id = :id
            FOR UPDATE
            """)
                .param("id", terminal.id())
                .query((rs, i) -> new PairingState(
                        rs.getString("status"),
                        rs.getString("pairing_code_hash"),
                        rs.getObject("pairing_expires_at", OffsetDateTime.class),
                        rs.getInt("failed_pairing_attempts")))
                .single();

        boolean codeValid = pairingCode != null
                && pairingCode.matches("\\d{6}")
                && state.pairingCodeHash() != null
                && state.pairingExpiresAt() != null
                && state.pairingExpiresAt().isAfter(OffsetDateTime.now())
                && state.failedAttempts() < MAX_FAILED_PAIRING_ATTEMPTS
                && "INACTIVE".equals(state.status())
                && MessageDigest.isEqual(
                        hashPairingCode(merchantId, pairingCode).getBytes(StandardCharsets.UTF_8),
                        state.pairingCodeHash().getBytes(StandardCharsets.UTF_8));

        if (!codeValid) {
            db.sql("""
                UPDATE pos_terminal
                SET failed_pairing_attempts = failed_pairing_attempts + 1
                WHERE id = :id AND status = 'INACTIVE'
                """)
                    .param("id", terminal.id())
                    .update();
            throw ApiException.badRequest("pairing_failed", "Invalid, expired, or locked pairing code");
        }

        ApiKeys.Created credential = apiKeys.createPosTerminal(
                merchantId,
                "pos-terminal:" + serialNumber,
                "pos-pairing");

        PosDukptKeyManagementService.TerminalDukptProfile dukpt =
                dukptService.provisionTerminal(terminal.id(), merchantId, null);
        String ksnPrefix = dukpt.initialKsn().substring(0, 15);
        String resolvedFormat = (attestationFormat != null && !attestationFormat.isBlank())
                ? attestationFormat.trim()
                : (attestationPublicKey != null && !attestationPublicKey.isBlank() ? "ECDSA_P256" : null);

        int paired = db.sql("""
            UPDATE pos_terminal
            SET status = 'ACTIVE',
                hardware_fingerprint = :fp,
                paired_at = now(),
                pairing_code_hash = NULL,
                pairing_expires_at = NULL,
                attestation_public_key = :attPub,
                attestation_format = :attFmt,
                credential_id = :credId,
                dukpt_ksn_prefix = :ksnPrefix
            WHERE id = :id AND status = 'INACTIVE'
            """)
            .param("fp", hardwareFingerprint)
            .param("attPub", attestationPublicKey)
            .param("attFmt", resolvedFormat)
            .param("credId", credential.id())
            .param("ksnPrefix", ksnPrefix)
            .param("id", terminal.id())
            .update();

        if (paired != 1) {
            throw ApiException.conflict("pairing_already_used", "Terminal pairing was already used");
        }
        String authToken = "POS_" + credential.secret();
        return new PairingResult(
                terminal.id(),
                serialNumber,
                "ACTIVE",
                authToken,
                credential.id(),
                credential.apiKey(),
                credential.signingSecret(),
                dukpt.initialKsn());
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

    static String hashPairingCode(UUID merchantId, String code) {
        return Idempotency.sha256("pos-pairing:" + merchantId + ":" + code);
    }

    private static void verifyEcdsaP256Attestation(
            String publicKeyPemOrBase64,
            String signatureBase64,
            String canonicalPayload) {
        if (publicKeyPemOrBase64 == null || publicKeyPemOrBase64.isBlank()
                || signatureBase64 == null || signatureBase64.isBlank()) {
            throw ApiException.badRequest("attestation_required",
                    "ECDSA P-256 attestation public key and signature are required");
        }
        try {
            String clean = publicKeyPemOrBase64
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(clean);
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey pk = kf.generatePublic(new X509EncodedKeySpec(der));
            if (!(pk instanceof ECPublicKey ecPk)
                    || ecPk.getParams().getCurve().getField().getFieldSize() != 256) {
                throw ApiException.badRequest("invalid_attestation_key",
                        "Attestation key must be ECDSA P-256 (secp256r1)");
            }
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initVerify(ecPk);
            sig.update(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            byte[] sigBytes = Base64.getDecoder().decode(signatureBase64.trim());
            if (!sig.verify(sigBytes)) {
                throw ApiException.badRequest("attestation_verification_failed",
                        "Terminal attestation signature is invalid");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("invalid_attestation",
                    "Failed to verify ECDSA P-256 terminal attestation");
        }
    }
}
