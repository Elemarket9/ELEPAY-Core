package com.elepay.wallet;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.Idempotency;
import com.elepay.common.References;
import com.elepay.identity.Actor;
import com.elepay.ledger.Ledger;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
class Wallets {
    private static final List<String> BUCKETS = List.of("available", "pending", "reserved");
    private final JdbcClient db;
    private final Ledger ledger;
    private final Idempotency idempotency;
    private final Audit audit;

    Wallets(JdbcClient db, Ledger ledger, Idempotency idempotency, Audit audit) {
        this.db = db;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    record Enrollment(@NotBlank @Size(max = 255) String subject,
                      @NotBlank @Size(max = 40) String phone,
                      @NotBlank @Size(max = 150) String displayName) {}
    record Wallet(UUID customerId, List<Ledger.Account> accounts) {}
    record Transfer(@NotNull UUID recipient, @Positive long amount) {}
    record Receipt(UUID transactionId, String reference, long amount, String currency) {}

    @PostMapping("/ops/customers")
    @Transactional
    public Wallet enroll(@Valid @RequestBody Enrollment request) {
        Actor actor = Actor.current();
        actor.requireOps();
        UUID id = UUID.randomUUID();
        int inserted = db.sql("""
                INSERT INTO customer (id, subject, phone, display_name)
                VALUES (:id, :subject, :phone, :name) ON CONFLICT DO NOTHING
                """).param("id", id).param("subject", request.subject())
                .param("phone", References.msisdn(request.phone())).param("name", request.displayName()).update();
        if (inserted == 0) throw ApiException.conflict("customer_exists", "Customer subject or phone is already registered");
        for (String bucket : BUCKETS) ledger.open("customer", id, bucket, Ledger.GHS);
        audit.record(actor.subject(), "customer.enrolled", id.toString(), Map.of());
        return wallet(id);
    }

    @GetMapping("/wallet")
    public Wallet read() {
        return wallet(customer(Actor.current()));
    }

    @GetMapping("/wallet/statement")
    public List<Ledger.Entry> statement(@RequestParam Instant from, @RequestParam Instant to) {
        UUID id = customer(Actor.current());
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
            throw ApiException.badRequest("statement_range", "Select a statement range of up to 31 days");
        }
        return ledger.statement(available(id), from, to);
    }

    @PostMapping("/wallet/transfers")
    @Transactional
    public Receipt transfer(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Transfer request) {
        UUID sender = customer(Actor.current());
        String scope = "wallet-transfer:" + sender;
        return idempotency.execute(scope, key, request, Receipt.class,
                () -> send(sender, scope, key, request.recipient(), request.amount()));
    }

    /** Posts a wallet-to-wallet transfer; callers own idempotency and authorization. */
    Receipt send(UUID sender, String scope, String key, UUID recipient, long amount) {
        if (sender.equals(recipient)) throw ApiException.badRequest("same_wallet", "Choose a different wallet");
        // Lock both customer rows in the same order before checking their status.
        List<UUID> active = db.sql("""
                SELECT id FROM customer
                WHERE id IN (:sender, :recipient) AND status = 'active'
                ORDER BY id FOR UPDATE
                """).param("sender", sender).param("recipient", recipient).query(UUID.class).list();
        if (active.size() != 2) throw ApiException.conflict("wallet_unavailable", "A wallet is unavailable");
        String reference = References.next("WLT");
        UUID transaction = ledger.post(Ledger.Posting.of(scope, key, "wallet_transfer", List.of(
                Ledger.Leg.debit(available(sender), amount),
                Ledger.Leg.credit(available(recipient), amount)
        )).describedAs(reference)).transactionId();
        UUID outgoing = UUID.randomUUID();
        UUID incoming = UUID.randomUUID();
        recordTransfer(outgoing, reference, sender, "transfer_out", amount, transaction, incoming);
        recordTransfer(incoming, References.next("WLT"), recipient, "transfer_in", amount, transaction, outgoing);
        return new Receipt(transaction, reference, amount, Ledger.GHS);
    }

    private void recordTransfer(UUID id, String reference, UUID customer, String kind, long amount,
                                UUID transaction, UUID related) {
        db.sql("""
                INSERT INTO wallet_operation (id, reference, customer_id, kind, amount, status, journal_tx, related_id)
                VALUES (:id, :reference, :customer, :kind, :amount, 'completed', :transaction, :related)
                """).param("id", id).param("reference", reference).param("customer", customer)
                .param("kind", kind).param("amount", amount).param("transaction", transaction)
                .param("related", related).update();
    }

    UUID customer(Actor actor) {
        actor.requireUser();
        return db.sql("SELECT id FROM customer WHERE subject = :subject AND status = 'active'")
                .param("subject", actor.subject()).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Active customer wallet not found"));
    }

    private Wallet wallet(UUID id) {
        return new Wallet(id, ledger.accountsOf("customer", id));
    }

    private static String available(UUID id) {
        return Ledger.code("customer", id, "available", Ledger.GHS);
    }
}
