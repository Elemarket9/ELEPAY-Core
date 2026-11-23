package com.elepay.wallet;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.References;
import com.elepay.identity.Actor;
import com.elepay.security.DualControl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Operations controls over customer wallets: phone reassignment and status. All changes are audited. */
@RestController
@RequestMapping("/v1/ops/customers/{id}")
class CustomerAdmin {
    private final JdbcClient db;
    private final Audit audit;
    private final DualControl dualControl;

    CustomerAdmin(JdbcClient db, Audit audit, DualControl dualControl) {
        this.db = db;
        this.audit = audit;
        this.dualControl = dualControl;
    }

    record PhoneChange(@NotBlank @Size(max = 40) String phone, @NotBlank @Size(max = 500) String reason) {}
    record StatusChange(@NotBlank String status, @NotBlank @Size(max = 500) String reason) {}

    @PutMapping("/phone")
    @Transactional
    public Map<String, String> changePhone(@PathVariable UUID id, @Valid @RequestBody PhoneChange request) {
        Actor actor = Actor.current();
        actor.requireOps();
        dualControl.hold("PHONE_CHANGE", "customer", id.toString(), null,
                Map.of("customerId", id.toString(), "phone", request.phone(), "reason", request.reason()),
                request.reason(), actor);
        String phone = References.msisdn(request.phone());
        String old = db.sql("SELECT phone FROM customer WHERE id = :id FOR UPDATE").param("id", id)
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("Unknown customer"));
        db.sql("INSERT INTO customer_phone_history (customer_id, phone, changed_by) VALUES (:id, :phone, :by)")
                .param("id", id).param("phone", old).param("by", actor.subject()).update();
        int updated = db.sql("UPDATE customer SET phone = :phone WHERE id = :id AND NOT EXISTS "
                        + "(SELECT 1 FROM customer WHERE phone = :phone AND id <> :id)")
                .param("phone", phone).param("id", id).update();
        if (updated == 0) throw ApiException.conflict("phone_in_use", "This number belongs to another wallet");
        revokeConfirmations(id);
        audit.record(actor.subject(), "customer.phone_changed", "customer:" + id, Map.of("reason", request.reason()));
        return Map.of("status", "updated");
    }

    @PostMapping("/ussd-pin/reset")
    @Transactional
    public Map<String, String> resetUssdPin(@PathVariable UUID id) {
        Actor actor = Actor.current();
        actor.requireOps();
        dualControl.hold("PHONE_CHANGE", "customer", id.toString(), null,
                Map.of("customerId", id.toString(), "resetUssdPin", true), "reset ussd pin", actor);
        int updated = db.sql("""
                UPDATE customer SET ussd_pin_hash = NULL, ussd_pin_failures = 0, ussd_pin_total_failures = 0,
                       ussd_pin_hard_locked = false, ussd_locked_until = NULL WHERE id = :id""")
                .param("id", id).update();
        if (updated == 0) throw ApiException.notFound("Unknown customer");
        audit.record(actor.subject(), "customer.ussd_pin_reset", "customer:" + id, Map.of());
        return Map.of("status", "reset");
    }

    @PutMapping("/status")
    @Transactional
    public Map<String, String> changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusChange request) {
        Actor actor = Actor.current();
        actor.requireOps();
        if (!Set.of("active", "frozen", "closed").contains(request.status())) {
            throw ApiException.badRequest("invalid_status", "Use active, frozen or closed");
        }
        int updated = db.sql("UPDATE customer SET status = :s WHERE id = :id").param("s", request.status())
                .param("id", id).update();
        if (updated == 0) throw ApiException.notFound("Unknown customer");
        if (!request.status().equals("active")) revokeConfirmations(id);
        audit.record(actor.subject(), "customer.status_changed", "customer:" + id,
                Map.of("status", request.status(), "reason", request.reason()));
        return Map.of("status", request.status());
    }

    private void revokeConfirmations(UUID customer) {
        db.sql("UPDATE recipient_confirmation SET status = 'revoked' WHERE recipient_customer_id = :id AND status = 'active'")
                .param("id", customer).update();
    }
}
