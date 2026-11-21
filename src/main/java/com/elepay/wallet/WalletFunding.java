package com.elepay.wallet;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.common.References;
import com.elepay.ledger.Ledger;
import com.elepay.outbox.Outbox;
import com.elepay.rails.RailEvents;
import com.elepay.rails.RailTransactions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * Wallet funding through a payment rail. The wallet is credited only when the
 * rail confirms the charge; a pending or unknown outcome stays pending and is
 * resolved by callback or status query, never by charging again.
 */
@RestController
@RequestMapping("/v1/wallet")
class WalletFunding {
    private static final Set<String> NETWORKS = Set.of("mtn", "telecel", "airteltigo");

    private final JdbcClient db;
    private final Wallets wallets;
    private final RailTransactions rails;
    private final Idempotency idempotency;
    private final Ledger ledger;
    private final Outbox outbox;
    private final TransactionTemplate tx;

    WalletFunding(JdbcClient db, Wallets wallets, RailTransactions rails, Idempotency idempotency, Ledger ledger,
                  Outbox outbox, PlatformTransactionManager tm) {
        this.db = db;
        this.wallets = wallets;
        this.rails = rails;
        this.idempotency = idempotency;
        this.ledger = ledger;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(tm);
    }

    record TopUp(@Positive @Max(1_000_000_00L) long amount, @NotBlank @Pattern(regexp = "momo|card") String method,
                 @Size(max = 40) String phone, @Size(max = 20) String network) {}

    record Operation(UUID id, String reference, String kind, long amount, String status, String counterparty,
                     String redirectUrl, java.time.OffsetDateTime createdAt) {}

    @PostMapping("/topups")
    public Operation topUp(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody TopUp request) {
        UUID customer = wallets.customer(com.elepay.identity.Actor.current());
        String msisdn = null;
        if (request.method().equals("momo")) {
            msisdn = References.msisdn(request.phone());
            if (!NETWORKS.contains(request.network())) throw ApiException.badRequest("invalid_network", "Choose mtn, telecel or airteltigo");
        }
        String phone = msisdn;
        Operation created = tx.execute(s -> idempotency.execute("wallet-topup:" + customer, key, request, Operation.class, () -> {
            UUID id = UUID.randomUUID();
            String reference = References.next("TOP");
            var railTx = rails.create(rails.defaultRail(), "charge", "wallet_topup", id, request.amount(),
                    new RailTransactions.Request(request.method(), phone, request.network(), null, null,
                            "ELEPAY wallet top-up " + reference, null, null, null, null, null, null));
            db.sql("""
                    INSERT INTO wallet_operation (id, reference, customer_id, kind, amount, status, rail_tx, counterparty)
                    VALUES (:id, :ref, :c, 'topup', :amount, 'pending', :tx, :cp)""")
                    .param("id", id).param("ref", reference).param("c", customer).param("amount", request.amount())
                    .param("tx", railTx.id()).param("cp", request.method() + (phone == null ? "" : " +" + phone)).update();
            return operation(id);
        }));
        UUID railTx = db.sql("SELECT rail_tx FROM wallet_operation WHERE id = :id").param("id", created.id())
                .query(UUID.class).single();
        rails.submit(railTx);
        return operation(created.id());
    }

    @GetMapping("/operations")
    public List<Operation> operations(@RequestParam(defaultValue = "50") int limit) {
        UUID customer = wallets.customer(com.elepay.identity.Actor.current());
        return db.sql("SELECT id FROM wallet_operation WHERE customer_id = :c ORDER BY created_at DESC LIMIT :l")
                .param("c", customer).param("l", Math.min(limit, 200)).query(UUID.class).list()
                .stream().map(this::operation).toList();
    }

    /** Receipt lookup by reference; only the owner of the operation can read it. */
    @GetMapping("/receipts/{reference}")
    public Operation receipt(@PathVariable String reference) {
        UUID customer = wallets.customer(com.elepay.identity.Actor.current());
        UUID id = db.sql("SELECT id FROM wallet_operation WHERE reference = :r AND customer_id = :c")
                .param("r", reference).param("c", customer).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Receipt not found"));
        return operation(id);
    }

    private Operation operation(UUID id) {
        return db.sql("""
                SELECT o.id, o.reference, o.kind, o.amount, o.status, o.counterparty,
                       CASE WHEN t.status = 'pending' THEN t.redirect_url END, o.created_at
                FROM wallet_operation o LEFT JOIN rail_transaction t ON t.id = o.rail_tx WHERE o.id = :id""")
                .param("id", id).query((rs, i) -> new Operation(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getObject(8, java.time.OffsetDateTime.class))).single();
    }

    @EventListener
    void onSucceeded(RailEvents.Succeeded event) {
        if (!event.purpose().equals("wallet_topup")) return;
        var op = db.sql("SELECT customer_id, reference FROM wallet_operation WHERE id = :id AND status = 'pending' FOR UPDATE")
                .param("id", event.purposeId()).query((rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)})
                .optional();
        if (op.isEmpty()) return;
        UUID customer = (UUID) op.get()[0];
        var posted = ledger.post(Ledger.Posting.of("wallet-topup", event.purposeId().toString(), "wallet_topup", List.of(
                Ledger.Leg.debit(Ledger.clearing(event.rail()), event.amount()),
                Ledger.Leg.credit(CustomerDirectory.available(customer), event.amount()))).describedAs((String) op.get()[1]));
        db.sql("UPDATE wallet_operation SET status = 'completed', journal_tx = :tx, updated_at = now() WHERE id = :id")
                .param("tx", posted.transactionId()).param("id", event.purposeId()).update();
        outbox.publish("wallet", op.get()[1], "wallet.topup.completed",
                Map.of("reference", op.get()[1], "amount", event.amount()), Set.of());
    }

    @EventListener
    void onFailed(RailEvents.Failed event) {
        if (!event.purpose().equals("wallet_topup")) return;
        db.sql("UPDATE wallet_operation SET status = 'failed', updated_at = now() WHERE id = :id AND status = 'pending'")
                .param("id", event.purposeId()).update();
    }
}
