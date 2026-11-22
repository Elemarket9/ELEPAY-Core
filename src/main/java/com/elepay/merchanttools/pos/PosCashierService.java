package com.elepay.merchanttools.pos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosCashierService {
    private final JdbcClient db;

    public PosCashierService(JdbcClient db) {
        this.db = db;
    }

    public record Cashier(
            UUID id,
            UUID merchantId,
            String name,
            String pinHash,
            String badgeNumber,
            boolean isActive,
            OffsetDateTime createdAt
    ) {}

    public record Shift(
            UUID id,
            UUID terminalId,
            UUID cashierId,
            UUID merchantId,
            long openingBalancePesewas,
            Long closingBalancePesewas,
            long expectedCashPesewas,
            Long cashDiscrepancyPesewas,
            String status,
            OffsetDateTime shiftStart,
            OffsetDateTime shiftEnd,
            String notes
    ) {}

    public static String hashPin(String pin, UUID merchantId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String salt = merchantId.toString() + "_elepay_pos_salt";
            byte[] hash = md.digest((salt + pin).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    @Transactional
    public Cashier registerCashier(UUID merchantId, String name, String pin, String badgeNumber) {
        UUID id = UUID.randomUUID();
        String pinHash = hashPin(pin, merchantId);

        db.sql("""
            INSERT INTO pos_cashier (id, merchant_id, name, pin_hash, badge_number, is_active, created_at)
            VALUES (:id, :merchantId, :name, :pinHash, :badgeNumber, true, now())
            """)
            .param("id", id)
            .param("merchantId", merchantId)
            .param("name", name)
            .param("pinHash", pinHash)
            .param("badgeNumber", badgeNumber)
            .update();

        return getCashier(id).orElseThrow();
    }

    public Optional<Cashier> getCashier(UUID id) {
        return db.sql("""
            SELECT id, merchant_id, name, pin_hash, badge_number, is_active, created_at
            FROM pos_cashier WHERE id = :id
            """)
            .param("id", id)
            .query(Cashier.class)
            .optional();
    }

    public Optional<Cashier> findByBadge(UUID merchantId, String badgeNumber) {
        return db.sql("""
            SELECT id, merchant_id, name, pin_hash, badge_number, is_active, created_at
            FROM pos_cashier WHERE merchant_id = :merchantId AND badge_number = :badgeNumber
            """)
            .param("merchantId", merchantId)
            .param("badgeNumber", badgeNumber)
            .query(Cashier.class)
            .optional();
    }

    public boolean authenticateCashier(UUID merchantId, String badgeNumber, String pin) {
        Optional<Cashier> cashierOpt = findByBadge(merchantId, badgeNumber);
        if (cashierOpt.isEmpty() || !cashierOpt.get().isActive()) {
            return false;
        }

        String expectedHash = cashierOpt.get().pinHash();
        String computedHash = hashPin(pin, merchantId);
        return MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.UTF_8), computedHash.getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public Shift openShift(UUID terminalId, UUID cashierId, UUID merchantId, long openingBalancePesewas, String notes) {
        // Close any existing open shift on this terminal
        Optional<Shift> active = getActiveShift(terminalId);
        if (active.isPresent()) {
            throw new IllegalStateException("An open shift already exists on terminal " + terminalId);
        }

        UUID shiftId = UUID.randomUUID();
        db.sql("""
            INSERT INTO pos_shift (
                id, terminal_id, cashier_id, merchant_id, opening_balance_pesewas,
                expected_cash_pesewas, status, shift_start, notes
            ) VALUES (
                :id, :terminalId, :cashierId, :merchantId, :openingBalance,
                :openingBalance, 'OPEN', now(), :notes
            )
            """)
            .param("id", shiftId)
            .param("terminalId", terminalId)
            .param("cashierId", cashierId)
            .param("merchantId", merchantId)
            .param("openingBalance", openingBalancePesewas)
            .param("notes", notes)
            .update();

        return getShift(shiftId).orElseThrow();
    }

    public Optional<Shift> getActiveShift(UUID terminalId) {
        return db.sql("""
            SELECT id, terminal_id, cashier_id, merchant_id, opening_balance_pesewas,
                   closing_balance_pesewas, expected_cash_pesewas, cash_discrepancy_pesewas,
                   status, shift_start, shift_end, notes
            FROM pos_shift
            WHERE terminal_id = :terminalId AND status = 'OPEN'
            ORDER BY shift_start DESC
            LIMIT 1
            """)
            .param("terminalId", terminalId)
            .query(Shift.class)
            .optional();
    }

    public Optional<Shift> getShift(UUID id) {
        return db.sql("""
            SELECT id, terminal_id, cashier_id, merchant_id, opening_balance_pesewas,
                   closing_balance_pesewas, expected_cash_pesewas, cash_discrepancy_pesewas,
                   status, shift_start, shift_end, notes
            FROM pos_shift WHERE id = :id
            """)
            .param("id", id)
            .query(Shift.class)
            .optional();
    }

    @Transactional
    public Shift recordCashTender(UUID shiftId, long amountPesewas) {
        db.sql("""
            UPDATE pos_shift
            SET expected_cash_pesewas = expected_cash_pesewas + :amt
            WHERE id = :id AND status = 'OPEN'
            """)
            .param("amt", amountPesewas)
            .param("id", shiftId)
            .update();

        return getShift(shiftId).orElseThrow();
    }

    @Transactional
    public Shift closeShift(UUID shiftId, long countedClosingCashPesewas, String notes) {
        Shift shift = getShift(shiftId).orElseThrow();
        if (!"OPEN".equals(shift.status())) {
            throw new IllegalStateException("Shift is not open: " + shift.status());
        }

        long discrepancy = countedClosingCashPesewas - shift.expectedCashPesewas();

        db.sql("""
            UPDATE pos_shift
            SET closing_balance_pesewas = :closing,
                cash_discrepancy_pesewas = :discrepancy,
                status = 'CLOSED',
                shift_end = now(),
                notes = CASE WHEN :notes IS NOT NULL THEN :notes ELSE notes END
            WHERE id = :id
            """)
            .param("closing", countedClosingCashPesewas)
            .param("discrepancy", discrepancy)
            .param("notes", notes)
            .param("id", shiftId)
            .update();

        return getShift(shiftId).orElseThrow();
    }
}
