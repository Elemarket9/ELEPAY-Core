package com.elepay.wallet;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.identity.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wallet operations for channels that authenticate a customer by phone and
 * PIN (USSD). Uses the same lookup limits, name confirmation and transfer
 * rules as the web and mobile entry points.
 */
@Service
@RestController
public class WalletChannel {
    private static final int MAX_PIN_FAILURES = 3;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final JdbcClient db;
    private final RecipientConfirmations confirmations;
    private final Wallets wallets;
    private final Audit audit;

    private final com.elepay.ledger.Ledger ledger;

    WalletChannel(JdbcClient db, RecipientConfirmations confirmations, Wallets wallets, Audit audit,
                  com.elepay.ledger.Ledger ledger) {
        this.ledger = ledger;
        this.db = db;
        this.confirmations = confirmations;
        this.wallets = wallets;
        this.audit = audit;
    }

    public record Customer(UUID id, String subject, String displayName, boolean hasPin) {}

    public record Recipient(String status, String verifiedName, String destination, UUID confirmationId, String message) {}

    public record Sent(String reference, String status, long amount, long fee, long total) {}

    public enum PinCheck { OK, WRONG, LOCKED, NOT_SET }

    record PinRequest(@Pattern(regexp = "[0-9]{4,6}") String pin, @Pattern(regexp = "[0-9]{4,6}") String currentPin) {}

    /** Sets or changes the USSD PIN from a signed-in web or app session. */
    @PutMapping("/v1/wallet/ussd-pin")
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, String> setPin(@Valid @RequestBody PinRequest request) {
        if (request.pin() == null || !request.pin().matches("[0-9]{4,6}")) {
            throw ApiException.badRequest("invalid_pin", "Use 4 to 6 digits");
        }
        if (isWeakPin(request.pin())) {
            throw ApiException.badRequest("weak_pin", "Choose a PIN that is not repeated or sequential digits");
        }
        Actor actor = Actor.current();
        UUID customer = wallets.customer(actor);
        var existing = db.sql("SELECT ussd_pin_hash, ussd_locked_until, ussd_pin_hard_locked FROM customer WHERE id = :id FOR UPDATE")
                .param("id", customer)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getObject(2, OffsetDateTime.class), rs.getBoolean(3)}).single();
        if (Boolean.TRUE.equals(existing[2])) {
            throw ApiException.forbidden("The USSD PIN is locked. Contact support to reset it.");
        }
        if (existing[1] != null && ((OffsetDateTime) existing[1]).isAfter(OffsetDateTime.now())) {
            throw ApiException.forbidden("The USSD PIN is temporarily locked. Try again later.");
        }
        if (existing[0] != null) {
            PinCheck check = checkPin(customer, request.currentPin());
            if (check == PinCheck.LOCKED) {
                throw ApiException.forbidden("The USSD PIN is locked due to too many failed attempts.");
            }
            if (check != PinCheck.OK) {
                throw ApiException.forbidden("Enter the current PIN to change it");
            }
        }
        db.sql("UPDATE customer SET ussd_pin_hash = :h, ussd_pin_failures = 0, ussd_pin_total_failures = 0, ussd_locked_until = NULL WHERE id = :id")
                .param("h", encoder.encode(request.pin())).param("id", customer).update();
        audit.record(actor.subject(), "customer.ussd_pin_set", "customer:" + customer, Map.of());
        return Map.of("status", "set");
    }

    /**
     * Validates PIN strength (Algorithm 074).
     * Rejects weak PINs including:
     * - Null, non-digit, or length < 4
     * - Single-digit repetition (e.g. "1111", "9999")
     * - 2-digit repetitions (e.g. "9898", "1212")
     * - 3-digit repetitions (e.g. "123123")
     * - Consecutive step-1 sequences (e.g. "1234", "4321")
     * - Consecutive step-2 sequences (e.g. "1357", "2468", "7531", "8642")
     */
    public static boolean isWeakPin(String pin) {
        if (pin == null || pin.length() < 4) {
            return true;
        }
        if (pin.chars().anyMatch(c -> c < '0' || c > '9')) {
            return true;
        }
        if (pin.chars().distinct().count() == 1) {
            return true;
        }
        // 3-digit repeated patterns (e.g. "123123")
        for (int i = 0; i <= pin.length() - 6; i++) {
            if (pin.substring(i, i + 3).equals(pin.substring(i + 3, i + 6))) {
                return true;
            }
        }
        for (int i = 0; i <= pin.length() - 4; i++) {
            char c0 = pin.charAt(i);
            char c1 = pin.charAt(i + 1);
            char c2 = pin.charAt(i + 2);
            char c3 = pin.charAt(i + 3);

            // Repeated digits in window: e.g. "1111", "9999"
            if (c0 == c1 && c1 == c2 && c2 == c3) {
                return true;
            }

            // 2-digit repeated pattern: e.g. "9898", "1212"
            if (c0 == c2 && c1 == c3) {
                return true;
            }

            // Consecutive ascending digits in window (step 1): e.g. "1234", "2345", "6789"
            if (c1 - c0 == 1 && c2 - c1 == 1 && c3 - c2 == 1) {
                return true;
            }

            // Consecutive descending digits in window (step 1): e.g. "4321", "6543", "9876"
            if (c0 - c1 == 1 && c1 - c2 == 1 && c2 - c3 == 1) {
                return true;
            }

            // Step-2 ascending sequence: e.g. "1357", "2468", "3579"
            if (c1 - c0 == 2 && c2 - c1 == 2 && c3 - c2 == 2) {
                return true;
            }

            // Step-2 descending sequence: e.g. "7531", "8642", "9753"
            if (c0 - c1 == 2 && c1 - c2 == 2 && c2 - c3 == 2) {
                return true;
            }
        }
        return false;
    }

    public Optional<Customer> activeCustomerByPhone(String msisdn) {
        return db.sql("SELECT id, subject, display_name, ussd_pin_hash IS NOT NULL FROM customer WHERE phone = :p AND status = 'active'")
                .param("p", msisdn).query((rs, i) -> new Customer(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getBoolean(4))).optional();
    }

    /** Checks a PIN. Three failures lock the channel; ten failures require a support reset. */
    @Transactional
    public PinCheck checkPin(UUID customer, String pin) {
        var row = db.sql("""
                SELECT ussd_pin_hash, ussd_locked_until, ussd_pin_hard_locked FROM customer WHERE id = :id FOR UPDATE""")
                .param("id", customer).query((rs, i) -> new Object[]{rs.getString(1), rs.getObject(2, OffsetDateTime.class), rs.getBoolean(3)})
                .single();
        if (row[0] == null) return PinCheck.NOT_SET;
        if (Boolean.TRUE.equals(row[2])) return PinCheck.LOCKED;
        if (row[1] != null && ((OffsetDateTime) row[1]).isAfter(OffsetDateTime.now())) return PinCheck.LOCKED;
        if (pin != null && pin.matches("[0-9]{4,6}") && encoder.matches(pin, (String) row[0])) {
            db.sql("UPDATE customer SET ussd_pin_failures = 0, ussd_locked_until = NULL WHERE id = :id").param("id", customer).update();
            return PinCheck.OK;
        }
        boolean expiredTempLock = row[1] != null;
        int failures = db.sql("""
                UPDATE customer SET ussd_pin_failures = CASE WHEN :expiredLock THEN 1 ELSE ussd_pin_failures + 1 END,
                       ussd_pin_total_failures = ussd_pin_total_failures + 1,
                       ussd_pin_hard_locked = ussd_pin_total_failures + 1 >= 10,
                       ussd_locked_until = CASE
                           WHEN ussd_pin_total_failures + 1 >= 10 THEN now() + interval '100 years'
                           WHEN (CASE WHEN :expiredLock THEN 1 ELSE ussd_pin_failures + 1 END) >= :max THEN now() + interval '30 minutes'
                           ELSE NULL
                       END
                WHERE id = :id RETURNING ussd_pin_failures""")
                .param("expiredLock", expiredTempLock)
                .param("max", MAX_PIN_FAILURES)
                .param("id", customer)
                .query(Integer.class).single();
        audit.record("ussd", "customer.ussd_pin_failed", "customer:" + customer, Map.of("failures", failures));
        if (failures >= MAX_PIN_FAILURES) return PinCheck.LOCKED;
        return PinCheck.WRONG;
    }

    public long availableBalance(UUID customer) {
        return ledger.balance(CustomerDirectory.available(customer));
    }

    public Recipient lookupWallet(Customer sender, String phone) {
        var r = confirmations.lookupFor("ussd:" + sender.subject(), sender.id(),
                new RecipientConfirmations.LookupRequest("wallet", phone, null, null, null));
        return new Recipient(r.status(), r.verifiedName(), r.destination(), r.confirmationId(), r.message());
    }

    public Sent send(Customer sender, UUID confirmationId, long amount, String idempotencyKey) {
        var r = confirmations.transferFor("ussd:" + sender.subject(), sender.id(), idempotencyKey,
                new RecipientConfirmations.ConfirmedTransfer(confirmationId, amount));
        return new Sent(r.reference(), r.status(), r.amount(), r.fee(), r.total());
    }
}
