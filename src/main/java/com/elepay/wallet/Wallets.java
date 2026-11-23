package com.elepay.wallet;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.Idempotency;
import com.elepay.common.References;
import com.elepay.identity.Actor;
import com.elepay.operations.EmtechCapture;
import com.elepay.ledger.Ledger;
import com.elepay.outbox.Outbox;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class Wallets {
    private static final List<String> BUCKETS = List.of("available", "pending", "reserved");
    private final JdbcClient db;
    private final Ledger ledger;
    private final Idempotency idempotency;
    private final Audit audit;
    private final TransferLimits limits;
    private final Outbox outbox;
    private final EmtechCapture regulatory;

    Wallets(JdbcClient db, Ledger ledger, Idempotency idempotency, Audit audit, TransferLimits limits, Outbox outbox,
            EmtechCapture regulatory) {
        this.db = db;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.limits = limits;
        this.outbox = outbox;
        this.regulatory = regulatory;
    }

    public record Enrollment(@NotBlank @Size(max = 255) String subject,
                             @NotBlank @Size(max = 40) String phone,
                             @NotBlank @Size(max = 150) String displayName) {}

    public record Locality(@NotBlank @Size(max = 80) String city,
                           @Size(max = 10) String region,
                           @NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-Z]{2}") String country,
                           @NotBlank @jakarta.validation.constraints.Pattern(regexp = "PROFILE|GHANA_CARD") String source) {}
    public record Wallet(UUID customerId, List<Ledger.Account> accounts) {}
    public record Transfer(@NotNull UUID recipient, @Positive long amount, UUID confirmationId) {
        public Transfer(UUID recipient, long amount) {
            this(recipient, amount, null);
        }
    }
    public record Receipt(UUID transactionId, String reference, long amount, String currency,
                          Long senderBalance, Long recipientBalance) {
        public Receipt(UUID transactionId, String reference, long amount, String currency) {
            this(transactionId, reference, amount, currency, null, null);
        }
    }

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
    public List<Ledger.Entry> statement(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        UUID id = customer(Actor.current());
        Instant toVal = to != null ? to : Instant.now();
        Instant fromVal = from != null ? from : toVal.minus(Duration.ofDays(30));
        if (!fromVal.isBefore(toVal) || Duration.between(fromVal, toVal).compareTo(Duration.ofDays(31)) > 0) {
            throw ApiException.badRequest("statement_range", "Select a statement range of up to 31 days");
        }
        return ledger.statement(available(id), fromVal, toVal);
    }

    @PutMapping("/wallet/locality")
    @Transactional
    public Map<String, String> saveLocality(@Valid @RequestBody Locality request) {
        UUID id = customer(Actor.current());
        db.sql("""
                UPDATE customer SET city = :city, region = :region, country = :country, locality_source = :source
                WHERE id = :id""")
                .param("city", request.city()).param("region", request.region()).param("country", request.country())
                .param("source", request.source()).param("id", id).update();
        return Map.of("status", "saved");
    }

    public record MnoLocality(@NotBlank @Size(max = 80) String city, @Size(max = 10) String region) {}

    /** Telco-provided coarse city. This is not a handset GPS reading. */
    @PutMapping("/ops/customers/{id}/mno-locality")
    @Transactional
    public Map<String, String> saveMnoLocality(@PathVariable UUID id, @Valid @RequestBody MnoLocality request) {
        Actor.current().requireOps();
        int updated = db.sql("UPDATE customer SET mno_city = :city, mno_region = :region WHERE id = :id")
                .param("city", request.city()).param("region", request.region()).param("id", id).update();
        if (updated == 0) throw ApiException.notFound("Customer not found");
        return Map.of("status", "saved");
    }

    @PostMapping("/wallet/transfers")
    @Transactional
    public Receipt transfer(@RequestHeader("Idempotency-Key") String key,
                            @RequestHeader(value = "X-Elepay-Channel", required = false) String channel,
                            HttpServletRequest http,
                            @Valid @RequestBody Transfer request) {
        UUID sender = customer(Actor.current());
        String scope = "wallet-transfer:" + sender;
        String payChannel = "MOBILE_USSD".equals(channel) ? "MOBILE_USSD" : "MOBILE_APP".equals(channel) ? "MOBILE_APP" : "WEB";
        String coarseIp = "MOBILE_USSD".equals(payChannel) ? null : http.getRemoteAddr();
        return idempotency.execute(scope, key, request, Receipt.class,
                () -> send(sender, scope, key, request.recipient(), request.amount(), request.confirmationId(),
                        payChannel, coarseIp));
    }

    /** Posts a wallet-to-wallet transfer; callers own idempotency and authorization. */
    public Receipt send(UUID sender, String scope, String key, UUID recipient, long amount) {
        return send(sender, scope, key, recipient, amount, null);
    }

    /** Full 12-step hardened transfer posting. */
    public Receipt send(UUID sender, String scope, String key, UUID recipient, long amount, UUID confirmationId) {
        return send(sender, scope, key, recipient, amount, confirmationId, null, null);
    }

    public Receipt send(UUID sender, String scope, String key, UUID recipient, long amount, UUID confirmationId,
                        String channel, String ephemeralIp) {
        if (sender.equals(recipient)) throw ApiException.badRequest("same_wallet", "Choose a different wallet");
        // Lock both customer rows in id order (Algorithm 002)
        List<UUID> active = db.sql("""
                SELECT id FROM customer
                WHERE id IN (:sender, :recipient) AND status = 'active'
                ORDER BY id FOR UPDATE
                """).param("sender", sender).param("recipient", recipient).query(UUID.class).list();
        if (active.size() != 2) throw ApiException.conflict("wallet_unavailable", "A wallet is unavailable");

        // Enforce KYC tier limits for both sender and recipient (Algorithm 039)
        limits.check(sender, recipient, amount);

        // Verify single-use confirmation token if supplied (Algorithm 038)
        if (confirmationId != null) {
            record ConfRow(UUID id, UUID sender, UUID recipient, String status, OffsetDateTime expiresAt) {}
            ConfRow conf = db.sql("""
                    SELECT id, sender_customer_id, recipient_customer_id, status, expires_at
                    FROM recipient_confirmation WHERE id = :id FOR UPDATE""")
                    .param("id", confirmationId)
                    .query((rs, i) -> new ConfRow(
                            rs.getObject(1, UUID.class),
                            rs.getObject(2, UUID.class),
                            rs.getObject(3, UUID.class),
                            rs.getString(4),
                            rs.getObject(5, OffsetDateTime.class)
                    )).optional().orElseThrow(() -> ApiException.notFound("Recipient confirmation not found"));

            if (!conf.sender().equals(sender) || !conf.recipient().equals(recipient)) {
                throw ApiException.conflict("confirmation_mismatch", "Confirmation does not match sender and recipient");
            }
            if (!"active".equals(conf.status())) {
                throw ApiException.conflict("confirmation_used", "This recipient confirmation was already used. Check the recipient again.");
            }
            if (!conf.expiresAt().isAfter(OffsetDateTime.now())) {
                throw ApiException.conflict("confirmation_expired", "The recipient confirmation expired. Check the recipient again.");
            }
        }

        String reference = References.next("WLT");

        // Atomic double-entry posting with account row locks and funds check (Algorithm 001)
        UUID transaction = ledger.post(Ledger.Posting.of(scope, key, "wallet_transfer", List.of(
                Ledger.Leg.debit(available(sender), amount),
                Ledger.Leg.credit(available(recipient), amount)
        )).describedAs(reference)).transactionId();

        // Mark single-use confirmation token as used
        if (confirmationId != null) {
            db.sql("UPDATE recipient_confirmation SET status = 'used', used_at = now(), used_by_tx = :tx WHERE id = :id")
                    .param("tx", transaction).param("id", confirmationId).update();
        }

        UUID outgoing = UUID.randomUUID();
        UUID incoming = UUID.randomUUID();
        recordTransfer(outgoing, reference, sender, "transfer_out", amount, transaction, incoming);
        recordTransfer(incoming, References.next("WLT"), recipient, "transfer_in", amount, transaction, outgoing);

        // Record hash-chained audit record (Algorithm 080)
        audit.record(sender.toString(), "wallet.transfer.completed", reference, Map.of(
                "sender", sender.toString(),
                "recipient", recipient.toString(),
                "amount", amount,
                "currency", Ledger.GHS,
                "transactionId", transaction.toString()
        ));

        // Dispatch domain event to Transactional Outbox (Algorithm 085)
        outbox.publish("wallet", sender, "wallet.transfer.completed", Map.of(
                "reference", reference,
                "sender", sender.toString(),
                "recipient", recipient.toString(),
                "amount", amount,
                "currency", Ledger.GHS,
                "transactionId", transaction.toString()
        ), List.of());
        String payChannel = channel == null ? "WEB" : channel;
        var origin = regulatory.customerOrigin(sender, payChannel, ephemeralIp);
        db.sql("""
                UPDATE wallet_operation
                SET channel = :channel, origin_city = :city, origin_region = :region,
                    origin_country = :country, origin_source = :source
                WHERE id = :id""")
                .param("channel", payChannel).param("city", origin.city()).param("region", origin.region())
                .param("country", origin.country()).param("source", origin.source()).param("id", outgoing).update();
        regulatory.walletTransfer(outgoing, null);

        long senderBalance = ledger.balance(available(sender));
        long recipientBalance = ledger.balance(available(recipient));

        return new Receipt(transaction, reference, amount, Ledger.GHS, senderBalance, recipientBalance);
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

    public UUID customer(Actor actor) {
        actor.requireUser();
        return db.sql("SELECT id FROM customer WHERE subject = :subject AND status = 'active'")
                .param("subject", actor.subject()).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Active customer wallet not found"));
    }

    private Wallet wallet(UUID id) {
        return new Wallet(id, ledger.accountsOf("customer", id));
    }

    public static String available(UUID id) {
        return Ledger.code("customer", id, "available", Ledger.GHS);
    }
}
