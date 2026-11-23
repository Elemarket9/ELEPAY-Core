package com.elepay.rails;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing direct telecom and banking rail carrier sessions.
 * Persists and updates all interactions with MTN MoMo, Telecel Cash, AT Money, and GhIPSS
 * in the direct_carrier_session authoritative log table.
 */
@Service
public class DirectCarrierSessionService {

    public enum Carrier {
        MTN, TELECEL, AIRTELTIGO, GHIPSS
    }

    public enum SessionType {
        COLLECTION, DISBURSEMENT, NAME_INQUIRY, REVERSAL
    }

    public enum SessionStatus {
        INITIATED, SUBMITTED, PENDING_USER_APPROVAL, SUCCESS, FAILED, REVERSED
    }

    public record CarrierSessionRecord(
            UUID id,
            String carrier,
            String sessionType,
            String referenceId,
            String externalTransactionId,
            String channel,
            String customerPhone,
            long amountPesewas,
            String currency,
            String status,
            String statusCode,
            String errorMessage,
            String requestPayload,
            String responsePayload,
            int retryCount,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    private final JdbcClient db;

    public DirectCarrierSessionService(JdbcClient db) {
        this.db = db;
    }

    @Transactional
    public CarrierSessionRecord createSession(
            Carrier carrier,
            SessionType sessionType,
            String referenceId,
            String channel,
            String customerPhone,
            long amountPesewas,
            String currency,
            String requestPayload
    ) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String curr = (currency != null && !currency.isBlank()) ? currency : "GHS";

        db.sql("""
            INSERT INTO direct_carrier_session (
                id, carrier, session_type, reference_id, channel, customer_phone,
                amount_pesewas, currency, status, request_payload, retry_count,
                created_at, updated_at
            ) VALUES (
                :id, :carrier, :sessionType, :referenceId, :channel, :customerPhone,
                :amountPesewas, :currency, :status, CAST(:requestPayload AS jsonb), 0,
                :now, :now
            )
        """)
                .param("id", id)
                .param("carrier", carrier.name())
                .param("sessionType", sessionType.name())
                .param("referenceId", referenceId)
                .param("channel", channel != null ? channel : "MOBILE_MONEY")
                .param("customerPhone", customerPhone)
                .param("amountPesewas", amountPesewas)
                .param("currency", curr)
                .param("status", SessionStatus.INITIATED.name())
                .param("requestPayload", requestPayload != null ? requestPayload : "{}")
                .param("now", now)
                .update();

        return new CarrierSessionRecord(
                id, carrier.name(), sessionType.name(), referenceId, null,
                channel != null ? channel : "MOBILE_MONEY", customerPhone,
                amountPesewas, curr, SessionStatus.INITIATED.name(), null, null,
                requestPayload != null ? requestPayload : "{}", null, 0, now, now
        );
    }

    @Transactional
    public void updateSessionStatus(
            UUID sessionId,
            SessionStatus status,
            String externalTransactionId,
            String statusCode,
            String errorMessage,
            String responsePayload
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        db.sql("""
            UPDATE direct_carrier_session
            SET status = :status,
                external_transaction_id = COALESCE(:externalTransactionId, external_transaction_id),
                status_code = COALESCE(:statusCode, status_code),
                error_message = COALESCE(:errorMessage, error_message),
                response_payload = CASE WHEN :responsePayload IS NOT NULL THEN CAST(:responsePayload AS jsonb) ELSE response_payload END,
                updated_at = :now
            WHERE id = :id
        """)
                .param("id", sessionId)
                .param("status", status.name())
                .param("externalTransactionId", externalTransactionId)
                .param("statusCode", statusCode)
                .param("errorMessage", errorMessage)
                .param("responsePayload", responsePayload)
                .param("now", now)
                .update();
    }

    @Transactional
    public void markSuccess(UUID sessionId, String externalTransactionId, String responsePayload) {
        updateSessionStatus(sessionId, SessionStatus.SUCCESS, externalTransactionId, "200", null, responsePayload);
    }

    @Transactional
    public void markFailed(UUID sessionId, String statusCode, String errorMessage, String responsePayload) {
        updateSessionStatus(sessionId, SessionStatus.FAILED, null, statusCode, errorMessage, responsePayload);
    }

    @Transactional
    public void incrementRetry(UUID sessionId) {
        db.sql("""
            UPDATE direct_carrier_session
            SET retry_count = retry_count + 1,
                updated_at = now()
            WHERE id = :id
        """)
                .param("id", sessionId)
                .update();
    }

    public Optional<CarrierSessionRecord> findById(UUID sessionId) {
        return db.sql("""
            SELECT id, carrier, session_type, reference_id, external_transaction_id, channel,
                   customer_phone, amount_pesewas, currency, status, status_code, error_message,
                   request_payload::text, response_payload::text, retry_count, created_at, updated_at
            FROM direct_carrier_session
            WHERE id = :id
        """)
                .param("id", sessionId)
                .query((rs, rowNum) -> new CarrierSessionRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("carrier"),
                        rs.getString("session_type"),
                        rs.getString("reference_id"),
                        rs.getString("external_transaction_id"),
                        rs.getString("channel"),
                        rs.getString("customer_phone"),
                        rs.getLong("amount_pesewas"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getString("status_code"),
                        rs.getString("error_message"),
                        rs.getString("request_payload"),
                        rs.getString("response_payload"),
                        rs.getInt("retry_count"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)
                ))
                .optional();
    }

    public Optional<CarrierSessionRecord> findByCarrierAndReference(Carrier carrier, String referenceId) {
        return db.sql("""
            SELECT id, carrier, session_type, reference_id, external_transaction_id, channel,
                   customer_phone, amount_pesewas, currency, status, status_code, error_message,
                   request_payload::text, response_payload::text, retry_count, created_at, updated_at
            FROM direct_carrier_session
            WHERE carrier = :carrier AND reference_id = :referenceId
            ORDER BY created_at DESC
            LIMIT 1
        """)
                .param("carrier", carrier.name())
                .param("referenceId", referenceId)
                .query((rs, rowNum) -> new CarrierSessionRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("carrier"),
                        rs.getString("session_type"),
                        rs.getString("reference_id"),
                        rs.getString("external_transaction_id"),
                        rs.getString("channel"),
                        rs.getString("customer_phone"),
                        rs.getLong("amount_pesewas"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getString("status_code"),
                        rs.getString("error_message"),
                        rs.getString("request_payload"),
                        rs.getString("response_payload"),
                        rs.getInt("retry_count"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)
                ))
                .optional();
    }
}
