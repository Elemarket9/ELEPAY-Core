package com.elepay.merchanttools.pos;

import com.elepay.common.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosCashierService {
    private static final int MAX_ATTEMPTS = 5;
    private static final Set<String> VALID_ROLES = Set.of("CASHIER", "SUPERVISOR", "MANAGER");
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
            OffsetDateTime createdAt,
            String role,
            boolean isManager
    ) {
        public Cashier(
                UUID id,
                UUID merchantId,
                String name,
                String pinHash,
                String badgeNumber,
                boolean isActive,
                OffsetDateTime createdAt
        ) {
            this(id, merchantId, name, pinHash, badgeNumber, isActive, createdAt, "CASHIER", false);
        }

        /** The hash is never part of an API response. */
        public Cashier withoutSecret() {
            return new Cashier(id, merchantId, name, null, badgeNumber, isActive, createdAt, role, isManager);
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

    private static String normalizeRole(String role, boolean isManager) {
        if (role == null || role.isBlank()) {
            return isManager ? "MANAGER" : "CASHIER";
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if (!VALID_ROLES.contains(normalized)) {
            throw ApiException.badRequest("invalid_role", "Cashier role must be CASHIER, SUPERVISOR, or MANAGER");
        }
        return normalized;
    }

    @Transactional
    public Cashier registerCashier(UUID merchantId, String name, String pin, String badgeNumber) {
        return registerCashier(merchantId, name, pin, badgeNumber, "CASHIER", false);
    }

    @Transactional
    public Cashier registerCashier(UUID merchantId, String name, String pin, String badgeNumber,
                                   String role, boolean isManager) {
        if (pin == null || !pin.matches("\\d{4,8}")) {
            throw ApiException.badRequest("invalid_pin", "Cashier PIN must be 4 to 8 digits");
        }
        String normalizedRole = normalizeRole(role, isManager);
        boolean effectiveManager = isManager || "MANAGER".equals(normalizedRole);
        UUID id = UUID.randomUUID();
        String pinHash = encoder.encode(pin);

        db.sql("""
            INSERT INTO pos_cashier (id, merchant_id, name, pin_hash, badge_number, is_active, created_at, role, is_manager)
            VALUES (:id, :merchantId, :name, :pinHash, :badgeNumber, true, now(), :role, :isManager)
            """)
            .param("id", id)
            .param("merchantId", merchantId)
            .param("name", name)
            .param("pinHash", pinHash)
            .param("badgeNumber", badgeNumber)
            .param("role", normalizedRole)
            .param("isManager", effectiveManager)
            .update();

        return getCashier(id).orElseThrow().withoutSecret();
    }

    @Transactional
    public Cashier updateCashierRole(UUID merchantId, UUID cashierId, String role, boolean isManager) {
        String normalizedRole = normalizeRole(role, isManager);
        boolean effectiveManager = isManager || "MANAGER".equals(normalizedRole);
        int updated = db.sql("""
            UPDATE pos_cashier
            SET role = :role, is_manager = :isManager
            WHERE id = :id AND merchant_id = :merchantId
            """)
            .param("role", normalizedRole)
            .param("isManager", effectiveManager)
            .param("id", cashierId)
            .param("merchantId", merchantId)
            .update();
        if (updated != 1) {
            throw ApiException.notFound("Cashier not found");
        }
        return getCashier(cashierId).orElseThrow().withoutSecret();
    }

    public Optional<Cashier> getCashier(UUID id) {
        return db.sql("""
            SELECT id, merchant_id, name, pin_hash, badge_number, is_active, created_at, role, is_manager
            FROM pos_cashier WHERE id = :id
            """)
            .param("id", id)
            .query(Cashier.class)
            .optional();
    }

    public Optional<Cashier> findByBadge(UUID merchantId, String badgeNumber) {
        return db.sql("""
            SELECT id, merchant_id, name, pin_hash, badge_number, is_active, created_at, role, is_manager
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

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public Cashier verifyManagerPin(UUID merchantId, String managerBadgeNumber, String managerPin) {
        if (managerPin == null || managerPin.isBlank()) {
            throw ApiException.forbidden("manager_pin_required", "Manager PIN is required");
        }
        if (managerBadgeNumber != null && !managerBadgeNumber.isBlank()) {
            Cashier cashier = findByBadge(merchantId, managerBadgeNumber.trim())
                    .orElseThrow(() -> ApiException.forbidden("manager_pin_invalid", "Invalid manager credentials"));
            if (!cashier.isActive() || !(cashier.isManager() || "MANAGER".equals(cashier.role())) || cashier.pinHash() == null) {
                throw ApiException.forbidden("manager_pin_invalid", "Cashier is not authorized as a manager");
            }
            var lock = db.sql("SELECT pin_failures, pin_locked_until FROM pos_cashier WHERE id = :id FOR UPDATE")
                    .param("id", cashier.id())
                    .query((rs, i) -> new Object[]{rs.getInt(1), rs.getObject(2, OffsetDateTime.class)})
                    .single();
            OffsetDateTime lockedUntil = (OffsetDateTime) lock[1];
            if (lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now())) {
                throw ApiException.forbidden("manager_pin_locked", "Manager PIN is temporarily locked");
            }
            if (encoder.matches(managerPin, cashier.pinHash())) {
                db.sql("UPDATE pos_cashier SET pin_failures = 0, pin_locked_until = NULL WHERE id = :id")
                        .param("id", cashier.id()).update();
                return cashier.withoutSecret();
            }
            int failures = (Integer) lock[0] + 1;
            db.sql("""
                    UPDATE pos_cashier SET pin_failures = :f,
                           pin_locked_until = CASE WHEN :f >= :max THEN now() + interval '15 minutes' END
                    WHERE id = :id""")
                    .param("f", failures).param("max", MAX_ATTEMPTS).param("id", cashier.id()).update();
            throw ApiException.forbidden("manager_pin_invalid", "Invalid manager PIN");
        }

        List<Cashier> managers = db.sql("""
                SELECT id, merchant_id, name, pin_hash, badge_number, is_active, created_at, role, is_manager
                FROM pos_cashier
                WHERE merchant_id = :m
                  AND is_active = true
                  AND (is_manager = true OR role = 'MANAGER')
                ORDER BY id ASC
                FOR UPDATE
                """)
                .param("m", merchantId)
                .query(Cashier.class)
                .list();
        if (managers.size() > 1) {
            throw ApiException.badRequest("manager_badge_required",
                    "Manager badge number is required when multiple managers are active");
        }
        if (managers.isEmpty()) {
            throw ApiException.forbidden("manager_pin_invalid", "Invalid manager PIN");
        }
        Cashier soleManager = managers.getFirst();
        Object[] lock = db.sql("SELECT pin_failures, pin_locked_until FROM pos_cashier WHERE id = :id")
                .param("id", soleManager.id())
                .query((rs, i) -> new Object[]{rs.getInt(1), rs.getObject(2, OffsetDateTime.class)})
                .single();
        OffsetDateTime lockedUntil = (OffsetDateTime) lock[1];
        if (lockedUntil != null && lockedUntil.isAfter(OffsetDateTime.now())) {
            throw ApiException.forbidden("manager_pin_locked", "Manager PIN is temporarily locked");
        }
        if (soleManager.pinHash() != null && encoder.matches(managerPin, soleManager.pinHash())) {
            db.sql("UPDATE pos_cashier SET pin_failures = 0, pin_locked_until = NULL WHERE id = :id")
                    .param("id", soleManager.id()).update();
            return soleManager.withoutSecret();
        }
        if (soleManager.pinHash() != null) {
            int failures = (Integer) lock[0] + 1;
            db.sql("""
                    UPDATE pos_cashier
                    SET pin_failures = :f,
                        pin_locked_until = CASE WHEN :f >= :max THEN now() + interval '15 minutes' ELSE pin_locked_until END
                    WHERE id = :id
                    """)
                    .param("f", failures)
                    .param("max", MAX_ATTEMPTS)
                    .param("id", soleManager.id())
                    .update();
        }
        throw ApiException.forbidden("manager_pin_invalid", "Invalid manager PIN");
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
