package com.elepay.payout;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.OpsQueue;
import com.elepay.common.References;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import com.elepay.outbox.Outbox;
import com.elepay.rails.NameVerifications;
import com.elepay.rails.RailEvents;
import com.elepay.rails.RailTransactions;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Payouts from merchant or customer balances to MoMo or bank destinations.
 *
 * <p>Each item reserves its amount (available to reserved) before anything is
 * sent and carries its own rail reference, so items in a batch succeed, fail
 * or come back independently. Completion moves reserved funds to the rail;
 * failure releases them; a return credits them back to available. A retry is
 * a new item linked to one definitively failed or returned item and can only
 * be created once, so a recipient is never paid twice for the same item.
 */
@Service
public class Payouts {
    private static final Set<String> MOMO = Set.of("mtn", "telecel", "airteltigo");
    public static final int MAX_BATCH = 500;

    private final JdbcClient db;
    private final Ledger ledger;
    private final RailTransactions rails;
    private final NameVerifications verifications;
    private final Outbox outbox;
    private final Audit audit;
    private final OpsQueue ops;
    private final ObjectMapper json;

    Payouts(JdbcClient db, Ledger ledger, RailTransactions rails, NameVerifications verifications, Outbox outbox,
            Audit audit, OpsQueue ops, ObjectMapper json) {
        this.db = db;
        this.ledger = ledger;
        this.rails = rails;
        this.verifications = verifications;
        this.outbox = outbox;
        this.audit = audit;
        this.ops = ops;
        this.json = json;
    }

    public record Destination(String type, String network, String accountNumber, String accountName) {}

    public record Item(String clientReference, long amount, Destination destination, String platform,
                       String confirmedName) {}

    public record Payout(UUID id, String reference, String clientReference, String ownerType, UUID ownerId,
                         long amount, String status, String verification, String verifiedName, String failureReason,
                         Destination destination, String platform, UUID retryOf, UUID batchId,
                         OffsetDateTime createdAt, OffsetDateTime completedAt) {}

    public record Batch(UUID id, String reference, int itemCount, long total, Map<String, Long> statusCounts,
                        List<Payout> items) {}

    static String bucket(String ownerType, UUID owner, String bucket) {
        return Ledger.code(ownerType, owner, bucket, Ledger.GHS);
    }

    Destination normalize(Destination d) {
        if (d == null || d.type() == null) throw ApiException.badRequest("invalid_destination", "A destination is required");
        if (d.accountName() == null || d.accountName().isBlank()) throw ApiException.badRequest("invalid_destination", "Account name is required");
        return switch (d.type()) {
            case "momo" -> {
                if (!MOMO.contains(d.network())) throw ApiException.badRequest("invalid_network", "Choose mtn, telecel or airteltigo");
                yield new Destination("momo", d.network(), References.msisdn(d.accountNumber()), d.accountName());
            }
            case "bank" -> {
                if (d.network() == null || !d.network().matches("[A-Z0-9]{2,20}") || d.accountNumber() == null
                        || !d.accountNumber().matches("[0-9]{6,34}")) {
                    throw ApiException.badRequest("invalid_bank_account", "Select a bank and enter the full account number");
                }
                yield d;
            }
            default -> throw ApiException.badRequest("invalid_destination", "Use momo or bank");
        };
    }

    /**
     * Creates a batch (or a single payout when {@code items} has one entry and
     * {@code batchDescription} is null). Must run inside the caller's
     * idempotent transaction; returns rail transactions to submit after commit.
     */
    @Transactional
    public Batch create(String ownerType, UUID owner, List<Item> items, String batchDescription, String actor) {
        if (items == null || items.isEmpty() || items.size() > MAX_BATCH) {
            throw ApiException.badRequest("invalid_batch", "A batch needs 1 to " + MAX_BATCH + " items");
        }
        Set<String> refs = new HashSet<>();
        long total = 0;
        for (Item item : items) {
            if (item.amount() <= 0) throw ApiException.badRequest("invalid_amount", "Payout amounts must be positive");
            if (item.clientReference() == null || item.clientReference().isBlank() || !refs.add(item.clientReference())) {
                throw ApiException.badRequest("invalid_reference", "Each item needs a unique reference");
            }
            total = Math.addExact(total, item.amount());
        }
        UUID batchId = UUID.randomUUID();
        db.sql("""
                INSERT INTO payout_batch (id, reference, owner_type, owner_id, item_count, total, description, created_by)
                VALUES (:id, :ref, :ot, :o, :n, :t, :d, :by)""")
                .param("id", batchId).param("ref", References.next("PB")).param("ot", ownerType).param("o", owner)
                .param("n", items.size()).param("t", total).param("d", batchDescription).param("by", actor).update();
        for (Item item : items) createItem(ownerType, owner, batchId, item, null, actor);
        audit.record(actor, "payout.batch_created", ownerType + ":" + owner, Map.of("batch", batchId.toString(),
                "items", items.size(), "total", total));
        return batch(batchId);
    }

    private UUID createItem(String ownerType, UUID owner, UUID batchId, Item item, UUID retryOf, String actor) {
        Destination d = normalize(item.destination());
        String verification;
        String verifiedName = null;
        if (item.confirmedName() != null) {
            verification = "confirmed";
            verifiedName = item.confirmedName();
        } else {
            var v = verifications.verify(d.type(), d.network(), d.accountNumber());
            verification = switch (v.outcome()) {
                case VERIFIED -> "verified";
                case NOT_FOUND -> "not_found";
                case UNAVAILABLE -> "unverified";
            };
            verifiedName = v.name();
        }
        UUID id = UUID.randomUUID();
        String reference = References.next("PO");
        String rail = rails.defaultRail();
        String destination = json.writeValueAsString(d);
        if (verification.equals("not_found")) {
            db.sql("""
                    INSERT INTO payout (id, reference, owner_type, owner_id, destination, amount, rail, status, requested_by,
                        batch_id, client_reference, platform, verification, failure_reason, retry_of)
                    VALUES (:id, :ref, :ot, :o, CAST(:dest AS jsonb), :amount, :rail, 'rejected', :by, :batch, :cref,
                        :platform, 'not_found', :reason, :retry)""")
                    .param("id", id).param("ref", reference).param("ot", ownerType).param("o", owner).param("dest", destination)
                    .param("amount", item.amount()).param("rail", rail).param("by", actor).param("batch", batchId)
                    .param("cref", item.clientReference()).param("platform", item.platform())
                    .param("reason", "The receiving network did not find this account").param("retry", retryOf).update();
            return id;
        }
        var reserved = ledger.post(Ledger.Posting.of("payout-reserve", id.toString(), "payout_reservation", List.of(
                Leg.debit(bucket(ownerType, owner, "available"), item.amount()),
                Leg.credit(bucket(ownerType, owner, "reserved"), item.amount()))).describedAs(reference));
        var railTx = rails.create(rail, "payout", "payout", id, item.amount(), new RailTransactions.Request(
                d.type(), d.type().equals("momo") ? d.accountNumber() : null, d.network(), null, null,
                "ELEPAY payout " + reference, null, d.accountName(), d.accountNumber(), d.type(), null, null));
        db.sql("""
                INSERT INTO payout (id, reference, owner_type, owner_id, destination, amount, rail, status, reservation_tx,
                    rail_tx, requested_by, batch_id, client_reference, platform, verification, verified_name, retry_of)
                VALUES (:id, :ref, :ot, :o, CAST(:dest AS jsonb), :amount, :rail, 'reserved', :rtx, :railtx, :by, :batch,
                    :cref, :platform, :verification, :vname, :retry)""")
                .param("id", id).param("ref", reference).param("ot", ownerType).param("o", owner).param("dest", destination)
                .param("amount", item.amount()).param("rail", rail).param("rtx", reserved.transactionId())
                .param("railtx", railTx.id()).param("by", actor).param("batch", batchId).param("cref", item.clientReference())
                .param("platform", item.platform()).param("verification", verification).param("vname", verifiedName)
                .param("retry", retryOf).update();
        return id;
    }

    /** Submits every reserved item of a batch. Call after the creating transaction commits. */
    public void submit(UUID batchId) {
        List<UUID> pending = db.sql("""
                SELECT p.rail_tx FROM payout p JOIN rail_transaction t ON t.id = p.rail_tx
                WHERE p.batch_id = :b AND t.status = 'created'""").param("b", batchId).query(UUID.class).list();
        for (UUID railTx : pending) {
            rails.submit(railTx);
        }
        db.sql("""
                UPDATE payout SET status = 'submitted' WHERE batch_id = :b AND status = 'reserved'
                AND rail_tx IN (SELECT id FROM rail_transaction WHERE status <> 'created')""").param("b", batchId).update();
    }

    /**
     * Retries a definitively failed or returned item as a new item with a new
     * rail reference. Rejected while the original is pending, unknown or paid.
     */
    @Transactional
    public Batch retry(String ownerType, UUID owner, String reference, String actor) {
        var original = db.sql("""
                SELECT id, status, amount, destination::text, client_reference, platform, batch_id, owner_type, owner_id
                FROM payout WHERE reference = :r FOR UPDATE""").param("r", reference)
                .query((rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8),
                        rs.getObject(9, UUID.class)})
                .optional().orElseThrow(() -> ApiException.notFound("Payout not found"));
        if (!original[7].equals(ownerType) || !original[8].equals(owner)) throw ApiException.notFound("Payout not found");
        String status = (String) original[1];
        if (!Set.of("failed", "returned").contains(status)) {
            throw ApiException.conflict("payout_not_retryable", status.equals("succeeded")
                    ? "This payout was already paid" : "This payout is " + status + "; wait for its final result");
        }
        long already = db.sql("SELECT count(*) FROM payout WHERE retry_of = :id").param("id", original[0])
                .query(Long.class).single();
        if (already > 0) throw ApiException.conflict("already_retried", "This payout was already retried");
        Destination d = json.readValue((String) original[3], Destination.class);
        UUID batchId = UUID.randomUUID();
        db.sql("""
                INSERT INTO payout_batch (id, reference, owner_type, owner_id, item_count, total, description, created_by)
                VALUES (:id, :ref, :ot, :o, 1, :t, :d, :by)""")
                .param("id", batchId).param("ref", References.next("PB")).param("ot", ownerType).param("o", owner)
                .param("t", original[2]).param("d", "Retry of " + reference).param("by", actor).update();
        createItem(ownerType, owner, batchId, new Item((String) original[4], (long) original[2], d,
                (String) original[5], null), (UUID) original[0], actor);
        audit.record(actor, "payout.retried", ownerType + ":" + owner, Map.of("original", reference));
        return batch(batchId);
    }

    // ---- rail outcomes ----------------------------------------------------------------------

    private record Locked(UUID id, String reference, String ownerType, UUID owner, long amount, String status,
                          UUID batch) {}

    private Optional<Locked> lock(UUID id) {
        return db.sql("SELECT id, reference, owner_type, owner_id, amount, status, batch_id FROM payout WHERE id = :id FOR UPDATE")
                .param("id", id).query((rs, i) -> new Locked(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getLong(5), rs.getString(6), rs.getObject(7, UUID.class))).optional();
    }

    @EventListener
    void onSucceeded(RailEvents.Succeeded event) {
        if (!event.purpose().equals("payout")) return;
        var p = lock(event.purposeId()).orElseThrow();
        if (!Set.of("reserved", "submitted").contains(p.status())) return;
        var posted = ledger.post(Ledger.Posting.of("payout-complete", p.id().toString(), "payout_completion", List.of(
                Leg.debit(bucket(p.ownerType(), p.owner(), "reserved"), p.amount()),
                Leg.credit(Ledger.clearing(event.rail()), p.amount()))).describedAs(p.reference()));
        db.sql("UPDATE payout SET status = 'succeeded', completion_tx = :tx, completed_at = now() WHERE id = :id")
                .param("tx", posted.transactionId()).param("id", p.id()).update();
        publish(p, "payout.succeeded", null);
    }

    @EventListener
    void onFailed(RailEvents.Failed event) {
        if (!event.purpose().equals("payout")) return;
        var p = lock(event.purposeId()).orElseThrow();
        if (!Set.of("reserved", "submitted").contains(p.status())) return;
        var posted = ledger.post(Ledger.Posting.of("payout-release", p.id().toString(), "payout_release", List.of(
                Leg.debit(bucket(p.ownerType(), p.owner(), "reserved"), p.amount()),
                Leg.credit(bucket(p.ownerType(), p.owner(), "available"), p.amount()))).describedAs(p.reference()));
        db.sql("UPDATE payout SET status = 'failed', completion_tx = :tx, completed_at = now(), failure_reason = :r WHERE id = :id")
                .param("tx", posted.transactionId()).param("r", event.reason()).param("id", p.id()).update();
        publish(p, "payout.failed", event.reason());
    }

    @EventListener
    void onReturned(RailEvents.Returned event) {
        if (!event.purpose().equals("payout")) return;
        var p = lock(event.purposeId()).orElseThrow();
        if (!p.status().equals("succeeded")) {
            ops.raise("payout_return_unexpected", "high", "payout:" + p.reference(), Map.of("status", p.status()));
            return;
        }
        var posted = ledger.post(Ledger.Posting.of("payout-return", p.id().toString(), "payout_return", List.of(
                Leg.debit(Ledger.clearing(event.rail()), p.amount()),
                Leg.credit(bucket(p.ownerType(), p.owner(), "available"), p.amount()))).describedAs(p.reference()));
        db.sql("UPDATE payout SET status = 'returned', return_tx = :tx, failure_reason = :r WHERE id = :id")
                .param("tx", posted.transactionId()).param("r", event.reason()).param("id", p.id()).update();
        ops.raise("payout_returned", "medium", "payout:" + p.reference(), Map.of("amount", p.amount(),
                "reason", String.valueOf(event.reason()), "note", "Funds are back in the owner's available balance"));
        publish(p, "payout.returned", event.reason());
    }

    private void publish(Locked p, String type, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payout", p.reference());
        data.put("amount", p.amount());
        if (reason != null) data.put("reason", reason);
        outbox.publish("payout", p.reference(), type, data,
                p.ownerType().equals("merchant") ? Set.of(p.owner()) : Set.of());
    }

    // ---- reads ------------------------------------------------------------------------------

    public Batch batch(UUID id) {
        var head = db.sql("SELECT id, reference, item_count, total FROM payout_batch WHERE id = :id").param("id", id)
                .query((rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getLong(4)})
                .single();
        List<Payout> items = list("batch_id = :x", id, 1000);
        Map<String, Long> counts = new TreeMap<>();
        items.forEach(p -> counts.merge(p.status(), 1L, Long::sum));
        return new Batch((UUID) head[0], (String) head[1], (int) head[2], (long) head[3], counts, items);
    }

    public Optional<UUID> batchId(String ownerType, UUID owner, String reference) {
        return db.sql("SELECT id FROM payout_batch WHERE reference = :r AND owner_type = :t AND owner_id = :o")
                .param("r", reference).param("t", ownerType).param("o", owner).query(UUID.class).optional();
    }

    public List<Payout> forOwner(String ownerType, UUID owner, int limit) {
        return db.sql(SELECT + " WHERE owner_type = :t AND owner_id = :o ORDER BY created_at DESC LIMIT :l")
                .param("t", ownerType).param("o", owner).param("l", Math.min(limit, 500)).query(this::map).list();
    }

    private List<Payout> list(String where, Object value, int limit) {
        return db.sql(SELECT + " WHERE " + where + " ORDER BY created_at, reference LIMIT :l")
                .param("x", value).param("l", limit).query(this::map).list();
    }

    private static final String SELECT = """
            SELECT id, reference, client_reference, owner_type, owner_id, amount, status, verification, verified_name,
                   failure_reason, destination::text, platform, retry_of, batch_id, created_at, completed_at FROM payout""";

    private Payout map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Payout(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getLong(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), json.readValue(rs.getString(11), Destination.class), rs.getString(12),
                rs.getObject(13, UUID.class), rs.getObject(14, UUID.class), rs.getObject(15, OffsetDateTime.class),
                rs.getObject(16, OffsetDateTime.class));
    }
}
