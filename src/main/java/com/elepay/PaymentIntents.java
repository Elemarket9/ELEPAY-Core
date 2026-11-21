package com.elepay;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Profile("sandbox")
@RestController
@RequestMapping("/sandbox/payments")
class PaymentIntents {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final SandboxLedger ledger;

    PaymentIntents(JdbcClient db, ObjectMapper json, SandboxLedger ledger) {
        this.db = db;
        this.json = json;
        this.ledger = ledger;
    }

    record Checkout(String customer, List<SandboxLedger.Allocation> allocations) {}
    record Payment(UUID id, String status) {}
    record StoredPayment(String status, String checkout) {}

    @PutMapping("/{id}")
    @Transactional
    public Payment create(@PathVariable UUID id, @RequestBody Checkout checkout) {
        validate(checkout);
        String body = json.writeValueAsString(checkout);
        db.sql("INSERT INTO payment_intent (id, checkout) VALUES (:id, CAST(:body AS jsonb)) ON CONFLICT DO NOTHING")
                .param("id", id).param("body", body).update();
        return db.sql("""
                SELECT status, checkout = CAST(:body AS jsonb) AS same_request
                FROM payment_intent WHERE id = :id
                """).param("id", id).param("body", body)
                .query((row, index) -> {
                    if (!row.getBoolean("same_request")) throw conflict();
                    return new Payment(id, row.getString("status"));
                }).single();
    }

    @GetMapping("/{id}")
    public Payment get(@PathVariable UUID id) {
        return db.sql("SELECT status FROM payment_intent WHERE id = :id")
                .param("id", id).query(String.class).optional()
                .map(status -> new Payment(id, status)).orElseThrow(PaymentIntents::missing);
    }

    @PutMapping("/{id}/confirm")
    @Transactional
    public Payment confirm(@PathVariable UUID id) {
        StoredPayment stored = lock(id);
        if (stored.status().equals("paid")) return new Payment(id, "paid");
        if (!stored.status().equals("pending")) throw conflict();
        Checkout checkout = json.readValue(stored.checkout(), Checkout.class);
        ledger.post(id, new SandboxLedger.Command("checkout", checkout.customer(), 0L,
                checkout.allocations(), null, null));
        return transition(id, "paid");
    }

    @PutMapping("/{id}/cancel")
    @Transactional
    public Payment cancel(@PathVariable UUID id) {
        StoredPayment stored = lock(id);
        if (stored.status().equals("cancelled")) return new Payment(id, "cancelled");
        if (!stored.status().equals("pending")) throw conflict();
        return transition(id, "cancelled");
    }

    private StoredPayment lock(UUID id) {
        return db.sql("SELECT status, checkout FROM payment_intent WHERE id = :id FOR UPDATE")
                .param("id", id).query((row, index) -> new StoredPayment(row.getString(1), row.getString(2)))
                .optional().orElseThrow(PaymentIntents::missing);
    }

    private Payment transition(UUID id, String status) {
        db.sql("UPDATE payment_intent SET status = :status WHERE id = :id")
                .param("id", id).param("status", status).update();
        db.sql("INSERT INTO payment_outbox (payment_id, event_type) VALUES (:id, :status)")
                .param("id", id).param("status", status).update();
        return new Payment(id, status);
    }

    private static void validate(Checkout checkout) {
        if (checkout.customer() == null || !checkout.customer().matches("customer:[A-Za-z0-9_-]{1,64}")
                || checkout.allocations() == null || checkout.allocations().isEmpty()
                || checkout.allocations().size() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer and 1 to 100 allocations are required");
        }
        for (SandboxLedger.Allocation allocation : checkout.allocations()) {
            if (allocation == null || allocation.pesewas() <= 0 || allocation.recipient() == null
                    || !allocation.recipient().matches("(merchant|delivery):[A-Za-z0-9_-]{1,64}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid checkout allocation");
            }
        }
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown payment");
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Payment reference or status conflicts with this request");
    }
}
