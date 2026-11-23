package com.elepay.rails;

import com.elepay.ledger.Ledger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GhIPSS Instant Pay (GIP) Interbank Clearing & Settlement Service.
 * Implements ISO 8583 / ISO 20022 clearing session lifecycle, ProxyPay alias resolution,
 * STAN/RRN generation, clearing cycle alignment, and balanced ledger settlement.
 */
@Service
public class GhipssClearingService {

    public enum ProxyType {
        PHONE, EMAIL, GHANA_CARD
    }

    public enum ClearingStatus {
        PENDING, MATCHED, SETTLED, DISPUTED, RETURNED
    }

    public record ProxyResolution(
            ProxyType proxyType,
            String proxyValue,
            String institutionCode,
            String accountNumber,
            String accountName,
            boolean verified
    ) {}

    public record GhipssClearingSession(
            UUID id,
            String sessionId,
            String clearingCycle,
            String originatingInstitutionCode,
            String destinationInstitutionCode,
            String destinationAccountNumber,
            String destinationAccountName,
            String beneficiaryProxyType,
            String beneficiaryProxyValue,
            long amountPesewas,
            String currency,
            String isoMessageType,
            String stan,
            String rrn,
            String status,
            UUID ledgerTxId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    private static final DateTimeFormatter RRN_DATE_FORMAT = DateTimeFormatter.ofPattern("yyDDDHHmmss").withZone(ZoneOffset.UTC);
    private final AtomicLong stanSequence = new AtomicLong(100000 + new SecureRandom().nextInt(800000));
    private final Map<String, ProxyResolution> proxyDirectory = new ConcurrentHashMap<>();

    private final JdbcClient db;
    private final Ledger ledger;

    public GhipssClearingService(JdbcClient db, Ledger ledger) {
        this.db = db;
        this.ledger = ledger;
    }

    public void registerProxy(ProxyType proxyType, String proxyValue, String institutionCode, String accountNumber, String accountName) {
        String key = proxyType.name() + ":" + proxyValue.trim().toLowerCase();
        proxyDirectory.put(key, new ProxyResolution(proxyType, proxyValue.trim(), institutionCode, accountNumber, accountName, true));
    }

    public Optional<ProxyResolution> resolveProxy(ProxyType proxyType, String proxyValue) {
        if (proxyValue == null || proxyValue.isBlank()) {
            return Optional.empty();
        }
        String key = proxyType.name() + ":" + proxyValue.trim().toLowerCase();
        return Optional.ofNullable(proxyDirectory.get(key));
    }

    public String generateStan() {
        long seq = stanSequence.incrementAndGet() % 1000000;
        return String.format("%06d", seq);
    }

    public String generateRrn() {
        String prefix = RRN_DATE_FORMAT.format(Instant.now());
        if (prefix.length() > 11) {
            return prefix.substring(0, 11) + (new SecureRandom().nextInt(9) + 1);
        }
        return String.format("%-12s", prefix).replace(' ', '0');
    }

    public String determineCurrentClearingCycle() {
        int hour = OffsetDateTime.now(ZoneOffset.UTC).getHour();
        if (hour < 6) return "CYCLE_1_0600";
        if (hour < 12) return "CYCLE_2_1200";
        if (hour < 18) return "CYCLE_3_1800";
        return "CYCLE_4_2300";
    }

    @Transactional
    public GhipssClearingSession initiateTransfer(
            String originatingInstitution,
            String destinationInstitution,
            String destinationAccount,
            String destinationAccountName,
            ProxyType proxyType,
            String proxyValue,
            long amountPesewas,
            String currency,
            String isoMessageType
    ) {
        if (amountPesewas <= 0) {
            throw new IllegalArgumentException("Amount must be positive integer minor units");
        }

        UUID id = UUID.randomUUID();
        String sessionId = "GIP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String stan = generateStan();
        String rrn = generateRrn();
        String clearingCycle = determineCurrentClearingCycle();
        String mti = (isoMessageType != null && !isoMessageType.isBlank()) ? isoMessageType : "pacs.008";
        String curr = (currency != null && !currency.isBlank()) ? currency : "GHS";
        OffsetDateTime now = OffsetDateTime.now();

        db.sql("""
            INSERT INTO ghipss_clearing_record (
                id, session_id, clearing_cycle, originating_institution_code,
                destination_institution_code, destination_account_number, destination_account_name,
                beneficiary_proxy_type, beneficiary_proxy_value, amount_pesewas, currency,
                iso_message_type, stan, rrn, status, created_at, updated_at
            ) VALUES (
                :id, :sessionId, :clearingCycle, :origInst,
                :destInst, :destAcc, :destName,
                :proxyType, :proxyVal, :amount, :curr,
                :mti, :stan, :rrn, :status, :now, :now
            )
        """)
                .param("id", id)
                .param("sessionId", sessionId)
                .param("clearingCycle", clearingCycle)
                .param("origInst", originatingInstitution)
                .param("destInst", destinationInstitution)
                .param("destAcc", destinationAccount)
                .param("destName", destinationAccountName)
                .param("proxyType", proxyType != null ? proxyType.name() : null)
                .param("proxyVal", proxyValue)
                .param("amount", amountPesewas)
                .param("curr", curr)
                .param("mti", mti)
                .param("stan", stan)
                .param("rrn", rrn)
                .param("status", ClearingStatus.PENDING.name())
                .param("now", now)
                .update();

        return new GhipssClearingSession(
                id, sessionId, clearingCycle, originatingInstitution, destinationInstitution,
                destinationAccount, destinationAccountName, proxyType != null ? proxyType.name() : null,
                proxyValue, amountPesewas, curr, mti, stan, rrn, ClearingStatus.PENDING.name(),
                null, now, now
        );
    }

    @Transactional
    public GhipssClearingSession settleTransfer(String sessionId) {
        GhipssClearingSession session = getSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        if (ClearingStatus.SETTLED.name().equals(session.status())) {
            return session;
        }

        // Post balanced zero-sum journal legs in Ledger
        String ghipssClearingAccount = "system:clearing:ghipss:" + session.currency();
        String interbankSettlementAccount = "system:settlement:interbank:" + session.currency();

        ledger.openSystem(ghipssClearingAccount, "clearing", session.currency(), true);
        ledger.openSystem(interbankSettlementAccount, "clearing", session.currency(), true);

        var posting = Ledger.Posting.of(
                "ghipss",
                "SETTLE-" + session.sessionId(),
                "INTERBANK_SETTLEMENT",
                List.of(
                        Ledger.Leg.debit(ghipssClearingAccount, session.amountPesewas()),
                        Ledger.Leg.credit(interbankSettlementAccount, session.amountPesewas())
                )
        ).describedAs("GhIPSS Interbank clearing settlement RRN " + session.rrn());

        UUID ledgerTxId = ledger.post(posting).transactionId();
        OffsetDateTime now = OffsetDateTime.now();

        db.sql("""
            UPDATE ghipss_clearing_record
            SET status = :status,
                ledger_tx_id = :ledgerTxId,
                updated_at = :now
            WHERE session_id = :sessionId
        """)
                .param("status", ClearingStatus.SETTLED.name())
                .param("ledgerTxId", ledgerTxId)
                .param("now", now)
                .param("sessionId", sessionId)
                .update();

        return new GhipssClearingSession(
                session.id(), session.sessionId(), session.clearingCycle(), session.originatingInstitutionCode(),
                session.destinationInstitutionCode(), session.destinationAccountNumber(), session.destinationAccountName(),
                session.beneficiaryProxyType(), session.beneficiaryProxyValue(), session.amountPesewas(),
                session.currency(), session.isoMessageType(), session.stan(), session.rrn(),
                ClearingStatus.SETTLED.name(), ledgerTxId, session.createdAt(), now
        );
    }

    @Transactional
    public void markDisputed(String sessionId, String disputeReason) {
        OffsetDateTime now = OffsetDateTime.now();
        db.sql("""
            UPDATE ghipss_clearing_record
            SET status = :status,
                updated_at = :now
            WHERE session_id = :sessionId
        """)
                .param("status", ClearingStatus.DISPUTED.name())
                .param("now", now)
                .param("sessionId", sessionId)
                .update();
    }

    public Optional<GhipssClearingSession> getSession(String sessionId) {
        return db.sql("""
            SELECT id, session_id, clearing_cycle, originating_institution_code,
                   destination_institution_code, destination_account_number, destination_account_name,
                   beneficiary_proxy_type, beneficiary_proxy_value, amount_pesewas, currency,
                   iso_message_type, stan, rrn, status, ledger_tx_id, created_at, updated_at
            FROM ghipss_clearing_record
            WHERE session_id = :sessionId
        """)
                .param("sessionId", sessionId)
                .query((rs, rowNum) -> new GhipssClearingSession(
                        rs.getObject("id", UUID.class),
                        rs.getString("session_id"),
                        rs.getString("clearing_cycle"),
                        rs.getString("originating_institution_code"),
                        rs.getString("destination_institution_code"),
                        rs.getString("destination_account_number"),
                        rs.getString("destination_account_name"),
                        rs.getString("beneficiary_proxy_type"),
                        rs.getString("beneficiary_proxy_value"),
                        rs.getLong("amount_pesewas"),
                        rs.getString("currency"),
                        rs.getString("iso_message_type"),
                        rs.getString("stan"),
                        rs.getString("rrn"),
                        rs.getString("status"),
                        rs.getObject("ledger_tx_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)
                ))
                .optional();
    }
}
