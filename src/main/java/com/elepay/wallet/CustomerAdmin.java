package com.elepay.wallet;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.References;
import com.elepay.identity.Actor;
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

    CustomerAdmin(JdbcClient db, Audit audit) {
        this.db = db;
        this.audit = audit;
    }

    record PhoneChange(@NotBlank @Size(max = 40) String phone, @NotBlank @Size(max = 500) String reason) {}
    record StatusChange(@NotBlank String status, @NotBlank @Size(max = 500) String reason) {}

    @PutMapping("/phone")
    @Transactional
    public Map<String, String> changePhone(@PathVariable UUID id, @Valid @RequestBody PhoneChange request) {
        Actor actor = Actor.current();
        actor.requireOps();
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
