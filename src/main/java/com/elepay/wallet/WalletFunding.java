package com.elepay.wallet;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.common.References;
import com.elepay.common.OpsQueue;
import com.elepay.ledger.Ledger;
import com.elepay.operations.EmtechCapture;
import com.elepay.outbox.Outbox;
import com.elepay.rails.RailEvents;
import com.elepay.rails.RailTransactions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
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
    private final EmtechCapture regulatory;
    private final TransferLimits limits;
    private final OpsQueue ops;

    @org.springframework.beans.factory.annotation.Value("${elepay.wallet.topup-own-msisdn-only:true}")
    private boolean ownMsisdnOnly = true;

    WalletFunding(JdbcClient db, Wallets wallets, RailTransactions rails, Idempotency idempotency, Ledger ledger,
                  Outbox outbox, PlatformTransactionManager tm, EmtechCapture regulatory, TransferLimits limits,
                  OpsQueue ops) {
        this.db = db;
        this.wallets = wallets;
        this.rails = rails;
        this.idempotency = idempotency;
        this.ledger = ledger;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(tm);
        this.regulatory = regulatory;
        this.limits = limits;
        this.ops = ops;
    }

    record TopUp(@Positive @Max(1_000_000_00L) long amount, @NotBlank @Pattern(regexp = "momo|card") String method,
                 @Size(max = 40) String phone, @Size(max = 20) String network) {}

    record Operation(UUID id, String reference, String kind, long amount, String status, String counterparty,
                     String redirectUrl, java.time.OffsetDateTime createdAt) {}

    @PostMapping("/topups")
    public Operation topUp(@RequestHeader("Idempotency-Key") String key,
                           @RequestHeader(value = "X-Elepay-Channel", required = false) String channel,
                           HttpServletRequest http,
                           @Valid @RequestBody TopUp request) {
        UUID customer = wallets.customer(com.elepay.identity.Actor.current());
        String msisdn = null;
        if (request.method().equals("momo")) {
            msisdn = References.msisdn(request.phone());
            if (!NETWORKS.contains(request.network())) throw ApiException.badRequest("invalid_network", "Choose mtn, telecel or airteltigo");
        }
        String phone = msisdn;
        if (ownMsisdnOnly && msisdn != null) {
            String own = db.sql("SELECT phone FROM customer WHERE id = :id").param("id", customer).query(String.class).single();
            if (!msisdn.equals(own)) {
                throw ApiException.badRequest("phone_not_on_file", "Top up only from the wallet's verified phone number");
            }
        }
        String payChannel = "MOBILE_APP".equals(channel) ? "MOBILE_APP" : "WEB";
        String coarseIp = http.getRemoteAddr();
        Operation created = tx.execute(s -> idempotency.execute("wallet-topup:" + customer, key, request, Operation.class, () -> {
            limits.checkCredit(customer, request.amount());
            UUID id = UUID.randomUUID();
            String reference = References.next("TOP");
            var origin = regulatory.customerOrigin(customer, payChannel, coarseIp);
            var railTx = rails.create(rails.defaultRail(), "charge", "wallet_topup", id, request.amount(),
                    new RailTransactions.Request(request.method(), phone, request.network(), null, null,
                            "ELEPAY wallet top-up " + reference, null, null, null, null, null, null));
            db.sql("""
                    INSERT INTO wallet_operation (id, reference, customer_id, kind, amount, status, rail_tx, counterparty,
                        channel, origin_city, origin_region, origin_country, origin_source)
                    VALUES (:id, :ref, :c, 'topup', :amount, 'pending', :tx, :cp, :channel, :city, :region, :country, :source)""")
                    .param("id", id).param("ref", reference).param("c", customer).param("amount", request.amount())
                    .param("tx", railTx.id()).param("cp", request.method() + (phone == null ? "" : " +" + phone))
                    .param("channel", payChannel).param("city", origin.city()).param("region", origin.region())
                    .param("country", origin.country()).param("source", origin.source()).update();
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
                .param("c", customer).param("l", Math.max(1, Math.min(limit, 200))).query(UUID.class).list()
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
        record Row(UUID id, String reference, String kind, long amount, String status, String counterparty,
                   UUID railTx, java.time.OffsetDateTime createdAt) {}
        Row o = db.sql("""
                SELECT id, reference, kind, amount, status, counterparty, rail_tx, created_at
                FROM wallet_operation WHERE id = :id""")
                .param("id", id).query((rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class),
                        rs.getObject(8, java.time.OffsetDateTime.class))).single();
        // A pending top-up shows where to finish paying (card 3-D Secure page).
        String redirect = o.railTx() == null ? null : rails.find(o.railTx())
                .filter(t -> t.status().equals("pending")).map(RailTransactions.RailTx::redirectUrl).orElse(null);
        return new Operation(o.id(), o.reference(), o.kind(), o.amount(), o.status(), o.counterparty(), redirect,
                o.createdAt());
    }

    /** Keeps wallet history in step with the payout module (withdrawals and transfers out of ELEPAY). */
    @EventListener
    void onPayoutStatus(com.elepay.payout.PayoutEvents.StatusChanged event) {
        if (!event.ownerType().equals("customer")) return;
        String status = switch (event.status()) {
            case "succeeded" -> "completed";
            case "failed", "rejected" -> "failed";
            case "returned" -> "returned";
            default -> null;
        };
        if (status == null) return;
        db.sql("""
                UPDATE wallet_operation SET status = :s, updated_at = now()
                WHERE related_id = :p AND customer_id = :c AND kind IN ('withdrawal', 'transfer_out')""")
                .param("s", status).param("p", event.payoutId()).param("c", event.ownerId()).update();
    }

    @EventListener
    @Transactional
    void onSucceeded(RailEvents.Succeeded event) {
        if (!event.purpose().equals("wallet_topup")) return;
        var op = db.sql("SELECT customer_id, reference FROM wallet_operation WHERE id = :id AND status = 'pending' FOR UPDATE")
                .param("id", event.purposeId()).query((rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)})
                .optional();
        if (op.isEmpty()) return;
        UUID customer = (UUID) op.get()[0];
        String reference = (String) op.get()[1];
        try {
            limits.checkCredit(customer, event.amount());
        } catch (ApiException e) {
            var suspensePosted = ledger.post(Ledger.Posting.of("wallet-topup-suspense", event.purposeId().toString(),
                    "suspense", List.of(
                            Ledger.Leg.debit(Ledger.clearing(event.rail()), event.amount()),
                            Ledger.Leg.credit(Ledger.SUSPENSE, event.amount())))
                    .describedAs(reference + " tier limit exceeded"));
            db.sql("UPDATE wallet_operation SET status = 'failed', journal_tx = :tx, updated_at = now() WHERE id = :id")
                    .param("tx", suspensePosted.transactionId()).param("id", event.purposeId()).update();
            ops.raise("topup_exceeded_tier_limit", "high", "wallet_operation:" + reference, Map.of(
                    "customerId", customer.toString(),
                    "amount", event.amount(),
                    "rail", event.rail(),
                    "reason", e.getMessage(),
                    "journalTx", suspensePosted.transactionId().toString()));
            return;
        }
        var posted = ledger.post(Ledger.Posting.of("wallet-topup", event.purposeId().toString(), "wallet_topup", List.of(
                Ledger.Leg.debit(Ledger.clearing(event.rail()), event.amount()),
                Ledger.Leg.credit(CustomerDirectory.available(customer), event.amount()))).describedAs(reference));
        db.sql("UPDATE wallet_operation SET status = 'completed', journal_tx = :tx, updated_at = now() WHERE id = :id")
                .param("tx", posted.transactionId()).param("id", event.purposeId()).update();
        regulatory.walletTopup(event.purposeId(), null);
        outbox.publish("wallet", reference, "wallet.topup.completed",
                Map.of("reference", reference, "amount", event.amount()), Set.of());
    }

    @EventListener
    void onFailed(RailEvents.Failed event) {
        if (!event.purpose().equals("wallet_topup")) return;
        int failed = db.sql("UPDATE wallet_operation SET status = 'failed', updated_at = now() WHERE id = :id AND status = 'pending'")
                .param("id", event.purposeId()).update();
        if (failed == 1) regulatory.walletTopup(event.purposeId(), null);
    }
}
