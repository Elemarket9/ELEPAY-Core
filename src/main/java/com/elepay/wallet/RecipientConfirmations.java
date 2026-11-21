package com.elepay.wallet;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.Idempotency;
import com.elepay.common.OpsQueue;
import com.elepay.common.References;
import com.elepay.identity.Actor;
import com.elepay.ledger.Ledger;
import com.elepay.payout.Payouts;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.elepay.rails.NameVerification;
import com.elepay.rails.NameVerifications;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Send-by-phone with recipient name confirmation.
 *
 * <p>The sender looks up one complete destination and receives only the
 * verified name plus a short-lived confirmation id. The id is bound to the
 * sender, normalized destination, verified recipient account and rail. The
 * transfer request carries only the confirmation id and amount; the server
 * re-validates the binding at execution, so an edited number, reassigned phone,
 * disabled wallet or expired confirmation cannot authorize a payment.
 */
@RestController
@RequestMapping("/v1/wallet")
class RecipientConfirmations {
    private static final Set<String> RAILS = Set.of("wallet", "momo", "bank");
    private static final Set<String> MOMO_NETWORKS = Set.of("mtn", "telecel", "airteltigo");

    private final JdbcClient db;
    private final Wallets wallets;
    private final NameVerifications verifications;
    private final Idempotency idempotency;
    private final Audit audit;
    private final OpsQueue ops;
    private final Payouts payouts;
    private final TransactionTemplate tx;
    private final Duration ttl;
    private final int perMinute;
    private final int perDay;
    private final int misses;

    RecipientConfirmations(JdbcClient db, Wallets wallets, NameVerifications verifications, Idempotency idempotency,
                           Audit audit, OpsQueue ops, Payouts payouts, PlatformTransactionManager tm,
                           @Value("${elepay.recipient-lookup.ttl-seconds:300}") long ttlSeconds,
                           @Value("${elepay.recipient-lookup.per-minute:10}") int perMinute,
                           @Value("${elepay.recipient-lookup.per-day:100}") int perDay,
                           @Value("${elepay.recipient-lookup.misses-per-hour:10}") int misses) {
        this.db = db;
        this.wallets = wallets;
        this.verifications = verifications;
        this.idempotency = idempotency;
        this.audit = audit;
        this.ops = ops;
        this.payouts = payouts;
        this.tx = new TransactionTemplate(tm);
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.perMinute = perMinute;
        this.perDay = perDay;
        this.misses = misses;
    }

    record LookupRequest(@NotBlank @Size(max = 10) String rail, @Size(max = 40) String phone,
                         @Size(max = 20) String network, @Size(max = 20) String bankCode,
                         @Size(max = 34) String accountNumber) {}

    /**
     * {@code status}: verified, not_found, recipient_unavailable or verification_unavailable.
     * Only a verified result carries a name and confirmation id.
     */
    record LookupResult(String status, String rail, String destination, String verifiedName, UUID confirmationId,
                        OffsetDateTime expiresAt, String message) {}

    record ConfirmedTransfer(@NotNull UUID confirmationId, @Positive long amount) {}

    record Preview(UUID confirmationId, String rail, String destination, String verifiedName, long amount, long fee,
                   long total, String currency, OffsetDateTime expiresAt) {}

    /** {@code status}: completed for wallet transfers; submitted, succeeded or failed for MoMo and bank payouts. */
    record ConfirmedReceipt(UUID transactionId, String reference, long amount, long fee, long total, String currency,
                            String verifiedName, String destination, String status, UUID payoutBatch) {}

    private record Confirmation(UUID id, UUID sender, String rail, String destination, String network,
                                UUID recipient, String recipientAccount, String name, String status,
                                OffsetDateTime expiresAt) {}

    // ---- lookup ---------------------------------------------------------------------------

    @PostMapping("/recipients/lookup")
    @Transactional(noRollbackFor = ApiException.class)
    public LookupResult lookup(@Valid @RequestBody LookupRequest request) {
        Actor actor = Actor.current();
        UUID sender = wallets.customer(actor);
        String rail = request.rail().toLowerCase();
        if (!RAILS.contains(rail)) throw ApiException.badRequest("invalid_rail", "Choose wallet, momo or bank");
        String destination;
        String network = null;
        switch (rail) {
            case "wallet" -> destination = References.msisdn(request.phone());
            case "momo" -> {
                destination = References.msisdn(request.phone());
                network = request.network() == null ? null : request.network().toLowerCase();
                if (!MOMO_NETWORKS.contains(network)) {
                    throw ApiException.badRequest("invalid_network", "Choose mtn, telecel or airteltigo");
                }
            }
            default -> {
                if (request.bankCode() == null || request.accountNumber() == null
                        || !request.bankCode().matches("[A-Z0-9]{2,20}") || !request.accountNumber().matches("[0-9]{6,34}")) {
                    throw ApiException.badRequest("invalid_bank_account", "Select a bank and enter the full account number");
                }
                network = request.bankCode();
                destination = request.accountNumber();
            }
        }
        String hash = Idempotency.sha256(rail + ":" + network + ":" + destination);
        enforceLimits(actor, sender, rail, hash);

        return switch (rail) {
            case "wallet" -> lookupWallet(sender, destination, hash);
            default -> lookupExternal(sender, rail, network, destination, hash);
        };
    }

    private LookupResult lookupWallet(UUID sender, String msisdn, String hash) {
        record Match(UUID id, String name, String status) {}
        var match = db.sql("SELECT id, display_name, status FROM customer WHERE phone = :phone")
                .param("phone", msisdn).query((rs, i) -> new Match(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3))).optional();
        String shown = "+" + msisdn;
        if (match.isEmpty()) {
            log(sender, "wallet", hash, "not_found");
            return new LookupResult("not_found", "wallet", shown, null, null, null,
                    "No ELEPAY wallet is registered to this number. Check the number or send to mobile money.");
        }
        if (match.get().id().equals(sender)) {
            log(sender, "wallet", hash, "self");
            throw ApiException.badRequest("same_wallet", "This number belongs to your own wallet");
        }
        if (!match.get().status().equals("active")) {
            log(sender, "wallet", hash, "disabled");
            return new LookupResult("recipient_unavailable", "wallet", shown, null, null, null,
                    "This ELEPAY wallet cannot receive money right now.");
        }
        log(sender, "wallet", hash, "verified");
        return confirm(sender, "wallet", msisdn, null, match.get().id(),
                Ledger.code("customer", match.get().id(), "available", Ledger.GHS), match.get().name(), "elepay_wallet", shown);
    }

    private LookupResult lookupExternal(UUID sender, String rail, String network, String destination, String hash) {
        NameVerification.Result result = verifications.verify(rail, network, destination);
        String shown = rail.equals("momo") ? "+" + destination + " (" + network + ")" : network + " " + mask(destination);
        return switch (result.outcome()) {
            case VERIFIED -> {
                log(sender, rail, hash, "verified");
                yield confirm(sender, rail, destination, network, null, network + ":" + destination,
                        result.name(), result.source(), shown);
            }
            case NOT_FOUND -> {
                log(sender, rail, hash, "not_found");
                yield new LookupResult("not_found", rail, shown, null, null, null,
                        rail.equals("bank") ? "The bank did not find this account number at the selected bank."
                                : "The network did not find a mobile money wallet for this number.");
            }
            case UNAVAILABLE -> {
                log(sender, rail, hash, "unavailable");
                yield new LookupResult("verification_unavailable", rail, shown, null, null, null,
                        "We could not verify the recipient name right now, so this transfer cannot be sent. Try again later.");
            }
        };
    }

    private LookupResult confirm(UUID sender, String rail, String destination, String network, UUID recipient,
                                 String account, String name, String source, String shown) {
        UUID id = UUID.randomUUID();
        OffsetDateTime expires = OffsetDateTime.now().plus(ttl);
        db.sql("""
                INSERT INTO recipient_confirmation (id, sender_customer_id, rail, destination, network,
                    recipient_customer_id, recipient_account, verified_name, verification_source, expires_at)
                VALUES (:id, :sender, :rail, :dest, :network, :recipient, :account, :name, :source, :expires)""")
                .param("id", id).param("sender", sender).param("rail", rail).param("dest", destination)
                .param("network", network).param("recipient", recipient).param("account", account)
                .param("name", name).param("source", source).param("expires", expires).update();
        return new LookupResult("verified", rail, shown, name, id, expires, null);
    }

    private void enforceLimits(Actor actor, UUID sender, String rail, String hash) {
        record Counts(long minute, long day, long misses) {}
        Counts c = db.sql("""
                SELECT count(*) FILTER (WHERE created_at > now() - interval '1 minute' AND outcome <> 'rate_limited'),
                       count(*) FILTER (WHERE created_at > now() - interval '1 day' AND outcome <> 'rate_limited'),
                       count(*) FILTER (WHERE created_at > now() - interval '1 hour' AND outcome = 'not_found')
                FROM recipient_lookup WHERE sender_customer_id = :s AND created_at > now() - interval '1 day'""")
                .param("s", sender).query((rs, i) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3))).single();
        if (c.minute() >= perMinute || c.day() >= perDay || c.misses() >= misses) {
            log(sender, rail, hash, "rate_limited");
            audit.record(actor.subject(), "recipient_lookup.rate_limited", "customer:" + sender,
                    Map.of("minute", c.minute(), "day", c.day(), "misses", c.misses()));
            long blocked = db.sql("""
                    SELECT count(*) FROM recipient_lookup WHERE sender_customer_id = :s
                    AND outcome = 'rate_limited' AND created_at > now() - interval '1 hour'""")
                    .param("s", sender).query(Long.class).single();
            if (blocked >= 3) {
                ops.raise("recipient_lookup_abuse", "medium", "customer:" + sender,
                        Map.of("blockedLastHour", blocked, "missesLastHour", c.misses()));
            }
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "lookup_rate_limited",
                    "Too many recipient checks. Wait a few minutes and try again.");
        }
    }

    private void log(UUID sender, String rail, String hash, String outcome) {
        db.sql("INSERT INTO recipient_lookup (sender_customer_id, rail, destination_hash, outcome) VALUES (:s, :r, :h, :o)")
                .param("s", sender).param("r", rail).param("h", hash).param("o", outcome).update();
    }

    private static String mask(String account) {
        return "•••• " + account.substring(Math.max(0, account.length() - 4));
    }

    // ---- preview and execution ------------------------------------------------------------

    @PostMapping("/transfers/preview")
    @Transactional(readOnly = true)
    public Preview preview(@Valid @RequestBody ConfirmedTransfer request) {
        UUID sender = wallets.customer(Actor.current());
        Confirmation c = validate(sender, request.confirmationId(), false);
        long fee = fee(c.rail(), request.amount());
        return new Preview(c.id(), c.rail(), display(c), c.name(), request.amount(), fee,
                Math.addExact(request.amount(), fee), Ledger.GHS, c.expiresAt());
    }

    @PostMapping("/transfers/confirmed")
    public ConfirmedReceipt transfer(@RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody ConfirmedTransfer request) {
        UUID sender = wallets.customer(Actor.current());
        String scope = "confirmed-transfer:" + sender;
        ConfirmedReceipt receipt = tx.execute(s -> idempotency.execute(scope, key, request, ConfirmedReceipt.class, () -> {
            Confirmation c = validate(sender, request.confirmationId(), true);
            long fee = fee(c.rail(), request.amount());
            if (c.rail().equals("wallet")) {
                Wallets.Receipt r = wallets.send(sender, scope, key, c.recipient(), request.amount());
                markUsed(c.id(), r.transactionId());
                return new ConfirmedReceipt(r.transactionId(), r.reference(), r.amount(), fee, r.amount() + fee,
                        r.currency(), c.name(), display(c), "completed", null);
            }
            // MoMo and bank transfers leave ELEPAY as a payout to the confirmed account.
            var batch = payouts.create("customer", sender, java.util.List.of(new Payouts.Item("transfer-" + key,
                    request.amount(), new Payouts.Destination(c.rail(), c.network(), c.destination(), c.name()), null,
                    c.name())), "Transfer to " + c.name(), Actor.current().subject());
            var item = batch.items().getFirst();
            UUID reservation = db.sql("SELECT reservation_tx FROM payout WHERE id = :id").param("id", item.id())
                    .query(UUID.class).single();
            markUsed(c.id(), reservation);
            db.sql("""
                    INSERT INTO wallet_operation (id, reference, customer_id, kind, amount, status, counterparty, journal_tx, related_id)
                    VALUES (:id, :ref, :c, 'withdrawal', :amount, 'pending', :cp, :tx, :payout)""")
                    .param("id", UUID.randomUUID()).param("ref", item.reference()).param("c", sender)
                    .param("amount", request.amount()).param("cp", c.name() + " " + display(c))
                    .param("tx", reservation).param("payout", item.id()).update();
            return new ConfirmedReceipt(reservation, item.reference(), request.amount(), fee, request.amount() + fee,
                    Ledger.GHS, c.name(), display(c), "submitted", batch.id());
        }));
        if (receipt != null && receipt.payoutBatch() != null) {
            payouts.submit(receipt.payoutBatch());
            String status = payouts.batch(receipt.payoutBatch()).items().getFirst().status();
            return new ConfirmedReceipt(receipt.transactionId(), receipt.reference(), receipt.amount(), receipt.fee(),
                    receipt.total(), receipt.currency(), receipt.verifiedName(), receipt.destination(),
                    status.equals("reserved") ? "submitted" : status, receipt.payoutBatch());
        }
        return receipt;
    }

    private void markUsed(UUID confirmation, UUID tx) {
        db.sql("UPDATE recipient_confirmation SET status = 'used', used_at = now(), used_by_tx = :tx WHERE id = :id")
                .param("tx", tx).param("id", confirmation).update();
    }

    private Confirmation validate(UUID sender, UUID id, boolean lock) {
        Confirmation c = db.sql("""
                SELECT id, sender_customer_id, rail, destination, network, recipient_customer_id, recipient_account,
                       verified_name, status, expires_at
                FROM recipient_confirmation WHERE id = :id""" + (lock ? " FOR UPDATE" : ""))
                .param("id", id)
                .query((rs, i) -> new Confirmation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class),
                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getObject(10, OffsetDateTime.class)))
                .optional().orElse(null);
        // Another sender's confirmation is indistinguishable from a missing one.
        if (c == null || !c.sender().equals(sender)) throw ApiException.notFound("Recipient confirmation not found");
        if (!c.status().equals("active")) {
            throw ApiException.conflict("confirmation_used", "This recipient confirmation was already used. Check the recipient again.");
        }
        if (!c.expiresAt().isAfter(OffsetDateTime.now())) {
            throw ApiException.conflict("confirmation_expired", "The recipient confirmation expired. Check the recipient again.");
        }
        if (c.rail().equals("wallet")) {
            Boolean stillValid = db.sql("SELECT status = 'active' AND phone = :phone FROM customer WHERE id = :id")
                    .param("phone", c.destination()).param("id", c.recipient()).query(Boolean.class).single();
            if (!stillValid) {
                throw ApiException.conflict("confirmation_stale",
                        "The recipient's wallet changed since you checked it. Check the recipient again.");
            }
        }
        return c;
    }

    private static long fee(String rail, long amount) {
        return 0; // Wallet-to-wallet transfers are free; external rail fees are quoted by payouts.
    }

    private static String display(Confirmation c) {
        return switch (c.rail()) {
            case "wallet" -> "+" + c.destination();
            case "momo" -> "+" + c.destination() + " (" + c.network() + ")";
            default -> c.network() + " " + mask(c.destination());
        };
    }
}
