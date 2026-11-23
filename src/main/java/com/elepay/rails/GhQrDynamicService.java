package com.elepay.rails;

import com.elepay.common.qr.EmvcoQr;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for generating, validating, and fulfilling EMVCo-compliant Dynamic GhQR payment intents.
 * Implements strict CRC-16 CCITT integrity verification, expiration lifecycle, and payment reconciliation.
 */
@Service
public class GhQrDynamicService {

    public enum QrStatus {
        ACTIVE, PAID, EXPIRED, CANCELLED
    }

    public record GhQrIntentRecord(
            UUID id,
            String qrReference,
            UUID merchantId,
            String terminalId,
            long amountPesewas,
            String currency,
            String pointOfInitiationMethod,
            String crcChecksum,
            String payloadText,
            String status,
            String paymentReference,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt,
            OffsetDateTime paidAt
    ) {}

    public record QrValidationResult(
            boolean valid,
            String qrReference,
            Long amountPesewas,
            String currency,
            String merchantName,
            String errorMessage
    ) {}

    private final JdbcClient db;

    public GhQrDynamicService(JdbcClient db) {
        this.db = db;
    }

    @Transactional
    public GhQrIntentRecord createDynamicIntent(
            UUID merchantId,
            String terminalId,
            long amountPesewas,
            String currency,
            String merchantName,
            String merchantCity,
            Duration ttl
    ) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Amount must be positive integer minor units");
        }

        UUID id = UUID.randomUUID();
        String qrReference = "GHQR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String curr = (currency != null && !currency.isBlank()) ? currency : "GHS";
        String city = (merchantCity != null && !merchantCity.isBlank()) ? merchantCity : "Accra";
        String name = (merchantName != null && !merchantName.isBlank()) ? merchantName : "ELEPAY Merchant";

        BigDecimal majorUnits = BigDecimal.valueOf(amountPesewas).divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY);

        String payloadText = EmvcoQr.builder()
                .pointOfInitiation(EmvcoQr.POINT_DYNAMIC)
                .merchantId(merchantId.toString())
                .merchantName(name)
                .merchantCity(city)
                .currency(EmvcoQr.DEFAULT_CURRENCY_GHS)
                .amount(majorUnits.toPlainString())
                .reference(qrReference)
                .build();

        String crcChecksum = payloadText.substring(payloadText.length() - 4);
        Duration expiryDuration = (ttl != null && !ttl.isNegative() && !ttl.isZero()) ? ttl : Duration.ofMinutes(15);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plus(expiryDuration);

        db.sql("""
            INSERT INTO ghqr_dynamic_intent (
                id, qr_reference, merchant_id, terminal_id, amount_pesewas,
                currency, point_of_initiation_method, crc_checksum, payload_text,
                status, expires_at, created_at
            ) VALUES (
                :id, :qrRef, :merchantId, :terminalId, :amount,
                :curr, '12', :crc, :payload,
                :status, :expiresAt, :now
            )
        """)
                .param("id", id)
                .param("qrRef", qrReference)
                .param("merchantId", merchantId)
                .param("terminalId", terminalId)
                .param("amount", amountPesewas)
                .param("curr", curr)
                .param("crc", crcChecksum)
                .param("payload", payloadText)
                .param("status", QrStatus.ACTIVE.name())
                .param("expiresAt", expiresAt)
                .param("now", now)
                .update();

        return new GhQrIntentRecord(
                id, qrReference, merchantId, terminalId, amountPesewas,
                curr, "12", crcChecksum, payloadText, QrStatus.ACTIVE.name(),
                null, expiresAt, now, null
        );
    }

    public QrValidationResult validateQrPayload(String rawPayload) {
        if (rawPayload == null || rawPayload.length() < 8) {
            return new QrValidationResult(false, null, null, null, null, "Payload too short");
        }

        if (!rawPayload.regionMatches(true, rawPayload.length() - 8, "6304", 0, 4)) {
            return new QrValidationResult(false, null, null, null, null, "Malformed CRC-16 block");
        }

        int crcIndex = rawPayload.length() - 8;
        String dataWithoutCrcVal = rawPayload.substring(0, rawPayload.length() - 4);
        String expectedCrc = EmvcoQr.calculateCrc(dataWithoutCrcVal);
        String actualCrc = rawPayload.substring(rawPayload.length() - 4);

        if (!expectedCrc.equalsIgnoreCase(actualCrc)) {
            return new QrValidationResult(false, null, null, null, null, "Invalid CRC-16 checksum");
        }

        Map<String, String> tags = EmvcoQr.parseTlv(rawPayload);
        String name = tags.get(EmvcoQr.TAG_MERCHANT_NAME);
        String amtStr = tags.get(EmvcoQr.TAG_TRANSACTION_AMOUNT);
        String curr = tags.getOrDefault(EmvcoQr.TAG_TRANSACTION_CURRENCY, "936");
        String additional = tags.get(EmvcoQr.TAG_ADDITIONAL_DATA);

        String ref = null;
        if (additional != null) {
            Map<String, String> addMap = EmvcoQr.parseTlv(additional);
            ref = addMap.get("05");
            if (ref == null) ref = addMap.get("01");
        }

        Long amountPesewas = null;
        if (amtStr != null) {
            try {
                amountPesewas = new BigDecimal(amtStr).multiply(BigDecimal.valueOf(100)).longValueExact();
            } catch (Exception ignored) {}
        }

        return new QrValidationResult(true, ref, amountPesewas, curr, name, null);
    }

    @Transactional
    public GhQrIntentRecord markPaid(String qrReference, String paymentReference) {
        GhQrIntentRecord intent = findByReference(qrReference)
                .orElseThrow(() -> new IllegalArgumentException("Dynamic GhQR intent not found: " + qrReference));

        if (QrStatus.PAID.name().equals(intent.status())) {
            return intent;
        }

        if (OffsetDateTime.now().isAfter(intent.expiresAt())) {
            throw new IllegalStateException("Dynamic GhQR intent has expired at " + intent.expiresAt());
        }

        OffsetDateTime now = OffsetDateTime.now();
        db.sql("""
            UPDATE ghqr_dynamic_intent
            SET status = :status,
                payment_reference = :paymentRef,
                paid_at = :now
            WHERE qr_reference = :qrRef
        """)
                .param("status", QrStatus.PAID.name())
                .param("paymentRef", paymentReference)
                .param("now", now)
                .param("qrRef", qrReference)
                .update();

        return new GhQrIntentRecord(
                intent.id(), intent.qrReference(), intent.merchantId(), intent.terminalId(),
                intent.amountPesewas(), intent.currency(), intent.pointOfInitiationMethod(),
                intent.crcChecksum(), intent.payloadText(), QrStatus.PAID.name(),
                paymentReference, intent.expiresAt(), intent.createdAt(), now
        );
    }

    @Transactional
    public int expireStaleIntents() {
        return db.sql("""
            UPDATE ghqr_dynamic_intent
            SET status = 'EXPIRED'
            WHERE status = 'ACTIVE' AND expires_at < now()
        """).update();
    }

    public Optional<GhQrIntentRecord> findByReference(String qrReference) {
        return db.sql("""
            SELECT id, qr_reference, merchant_id, terminal_id, amount_pesewas,
                   currency, point_of_initiation_method, crc_checksum, payload_text,
                   status, payment_reference, expires_at, created_at, paid_at
            FROM ghqr_dynamic_intent
            WHERE qr_reference = :qrRef
        """)
                .param("qrRef", qrReference)
                .query((rs, rowNum) -> new GhQrIntentRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("qr_reference"),
                        rs.getObject("merchant_id", UUID.class),
                        rs.getString("terminal_id"),
                        rs.getLong("amount_pesewas"),
                        rs.getString("currency"),
                        rs.getString("point_of_initiation_method"),
                        rs.getString("crc_checksum"),
                        rs.getString("payload_text"),
                        rs.getString("status"),
                        rs.getString("payment_reference"),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("paid_at", OffsetDateTime.class)
                ))
                .optional();
    }
}
