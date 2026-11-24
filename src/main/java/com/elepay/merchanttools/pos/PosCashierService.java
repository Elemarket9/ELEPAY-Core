package com.elepay.merchanttools.pos;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosCashierService {
    private static final int MAX_ATTEMPTS = 5;
    private final JdbcClient db;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public PosCashierService(JdbcClient db) {
        this.db = db;
    }

    public record Cashier(
            UUID id,
            UUID merchantId,
            String name,
            @com.fasterxml.jackson.annotation.JsonIgnore String pinHash,
            String badgeNumber,
            boolean isActive,
            OffsetDateTime createdAt
    ) {
        /** The hash is never part of an API response. */
        public Cashier withoutSecret() {
            return new Cashier(id, merchantId, name, null, badgeNumber, isActive, createdAt);
        }
    }

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

    @Transactional
    public Cashier registerCashier(UUID merchantId, String name, String pin, String badgeNumber) {
        if (pin == null || !pin.matches("\\d{4,8}")) {
            throw com.elepay.common.ApiException.badRequest("invalid_pin", "Cashier PIN must be 4 to 8 digits");
        }
        UUID id = UUID.randomUUID();
        String pinHash = encoder.encode(pin);

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

        return getCashier(id).orElseThrow().withoutSecret();
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

    @Transactional
    public boolean authenticateCashier(UUID merchantId, String badgeNumber, String pin) {
        Optional<Cashier> cashierOpt = findByBadge(merchantId, badgeNumber);
        if (cashierOpt.isEmpty() || !cashierOpt.get().isActive() || cashierOpt.get().pinHash() == null) {
            return false;
        }
        Cashier cashier = cashierOpt.get();
        var lock = db.sql("SELECT pin_failures, pin_locked_until FROM pos_cashier WHERE id = :id FOR UPDATE")
                .param("id", cashier.id())
                .query((rs, i) -> new Object[]{rs.getInt(1), rs.getObject(2, OffsetDateTime.class)})
                .single();
        OffsetDateTime lockedUntil = (OffsetDateTime) lock[1];
        if (lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now())) return false;
        if (encoder.matches(pin, cashier.pinHash())) {
            db.sql("UPDATE pos_cashier SET pin_failures = 0, pin_locked_until = NULL WHERE id = :id")
                    .param("id", cashier.id()).update();
            return true;
        }
        int failures = (Integer) lock[0] + 1;
        db.sql("""
                UPDATE pos_cashier SET pin_failures = :f,
                       pin_locked_until = CASE WHEN :f >= :max THEN now() + interval '15 minutes' END
                WHERE id = :id""")
                .param("f", failures).param("max", MAX_ATTEMPTS).param("id", cashier.id()).update();
        return false;
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
