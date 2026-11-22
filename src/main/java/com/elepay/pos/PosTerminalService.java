package com.elepay.pos;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosTerminalService {
    private final JdbcClient db;

    public PosTerminalService(JdbcClient db) {
        this.db = db;
    }

    public record PosTerminal(
            UUID id,
            UUID merchantId,
            UUID branchId,
            String serialNumber,
            String model,
            String pairingCode,
            String secretKey,
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
        UUID id = UUID.randomUUID();
        String pairingCode = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        String secretKey = UUID.randomUUID().toString().replace("-", "");

        db.sql("""
            INSERT INTO pos_terminal (
                id, merchant_id, branch_id, serial_number, model,
                pairing_code, secret_key, status, created_at
            ) VALUES (
                :id, :merchantId, :branchId, :serialNumber, :model,
                :pairingCode, :secretKey, 'INACTIVE', now()
            )
            """)
            .param("id", id)
            .param("merchantId", merchantId)
            .param("branchId", branchId)
            .param("serialNumber", serialNumber)
            .param("model", model)
            .param("pairingCode", pairingCode)
            .param("secretKey", secretKey)
            .update();

        return getTerminal(id).orElseThrow();
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

        if (!pairingCode.equals(terminal.pairingCode())) {
            throw new IllegalArgumentException("Invalid pairing code");
        }

        db.sql("""
            UPDATE pos_terminal
            SET status = 'ACTIVE', hardware_fingerprint = :fp, paired_at = now()
            WHERE id = :id
            """)
            .param("fp", hardwareFingerprint)
            .param("id", terminal.id())
            .update();

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
