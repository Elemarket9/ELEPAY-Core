package com.elepay.fx;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.OpsQueue;
import com.elepay.common.References;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import com.elepay.outbox.Outbox;
import com.elepay.wallet.CustomerDirectory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cross-border transfers bound to partner-confirmed quotes.
 *
 * <p>Every journal transaction balances within each currency. Conversion is
 * expressed with linked FX clearing entries: the source currency leg ends in
 * {@code system:fx_clearing:SRC} and the destination leg starts from
 * {@code system:fx_clearing:DST} inside the same journal transaction.
 * Outbound destination funds wait in {@code fx_in_flight} until the partner
 * confirms delivery; a failure or return reverses the original transaction.
 */
@Service
class FxService {
    private final JdbcClient db;
    private final Ledger ledger;
    private final Map<String, FxPartner> partners;
    private final Outbox outbox;
    private final Audit audit;
    private final OpsQueue ops;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    FxService(JdbcClient db, Ledger ledger, List<FxPartner> partners, Outbox outbox, Audit audit, OpsQueue ops,
              ObjectMapper json, PlatformTransactionManager tm) {
        this.db = db;
        this.ledger = ledger;
        this.partners = partners.stream().collect(Collectors.toMap(FxPartner::name, Function.identity()));
        this.outbox = outbox;
        this.audit = audit;
        this.ops = ops;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
    }

    record Corridor(String code, String direction, String sendingCountry, String receivingCountry,
                    String sourceCurrency, String destinationCurrency, String fundingMethod, String deliveryMethod,
                    String partner, long feeFixed, int feeBps, long minAmount, long maxAmount, int quoteTtlSeconds,
                    int estDeliveryMinutes, boolean active) {}

    record Quote(UUID id, String corridor, String sourceCurrency, long sourceAmount, long fee, long totalDebit,
                 BigDecimal rate, String destinationCurrency, long destinationAmount, OffsetDateTime expiresAt,
                 int estimatedDeliveryMinutes, String status) {}

    record Transfer(UUID id, String reference, String direction, String corridor, String sourceCurrency,
                    long sourceAmount, long fee, BigDecimal rate, String destinationCurrency, long destinationAmount,
                    String status, String failureReason, JsonNode recipient, OffsetDateTime createdAt) {}

    FxPartner partner(String name) {
        FxPartner p = partners.get(name);
        if (p == null) throw ApiException.unprocessable("partner_unavailable", "FX partner " + name + " is not connected");
        return p;
    }

    static String fxClearing(String currency) { return "system:fx_clearing:" + currency; }
    static String inFlight(String currency) { return "system:fx_in_flight:" + currency; }
    static String revenue(String currency) { return "system:revenue:" + currency; }

    String partnerClearing(String partner, String currency) {
        String code = "system:clearing:fx-" + partner + ":" + currency;
        db.sql("""
                INSERT INTO ledger_account (code, owner_type, bucket, currency, allow_negative)
                VALUES (:c, 'system', 'clearing', :cur, true) ON CONFLICT (code) DO NOTHING""")
                .param("c", code).param("cur", currency).update();
        return code;
    }

    int exponent(String currency) {
        return db.sql("SELECT exponent FROM currency WHERE code = :c").param("c", currency).query(Integer.class)
                .optional().orElseThrow(() -> ApiException.badRequest("unknown_currency", "Unsupported currency " + currency));
    }

    /** Converts minor units with the quoted rate, rounding down in the customer's disfavour by at most one minor unit. */
    long convert(long sourceMinor, String from, BigDecimal rate, String to) {
        return new BigDecimal(sourceMinor).multiply(rate).movePointRight(exponent(to) - exponent(from))
                .setScale(0, RoundingMode.DOWN).longValueExact();
    }

    // ---- corridors --------------------------------------------------------------------------

    List<Corridor> corridors(boolean activeOnly) {
        return db.sql("SELECT * FROM fx_corridor" + (activeOnly ? " WHERE active" : "") + " ORDER BY code")
                .query(FxService::corridor).list();
    }

    Corridor corridor(String code) {
        return db.sql("SELECT * FROM fx_corridor WHERE code = :c").param("c", code).query(FxService::corridor)
                .optional().orElseThrow(() -> ApiException.notFound("Unknown corridor"));
    }

    private static Corridor corridor(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Corridor(rs.getString("code"), rs.getString("direction"), rs.getString("sending_country"),
                rs.getString("receiving_country"), rs.getString("source_currency"), rs.getString("destination_currency"),
                rs.getString("funding_method"), rs.getString("delivery_method"), rs.getString("partner"),
                rs.getLong("fee_fixed"), rs.getInt("fee_bps"), rs.getLong("min_amount"), rs.getLong("max_amount"),
                rs.getInt("quote_ttl_seconds"), rs.getInt("est_delivery_minutes"), rs.getBoolean("active"));
    }

    void upsertCorridor(Corridor c, String actor) {
        exponent(c.sourceCurrency());
        exponent(c.destinationCurrency());
        db.sql("""
                INSERT INTO fx_corridor (code, direction, sending_country, receiving_country, source_currency,
                    destination_currency, funding_method, delivery_method, partner, fee_fixed, fee_bps, min_amount,
                    max_amount, quote_ttl_seconds, est_delivery_minutes, active)
                VALUES (:code, :dir, :sc, :rc, :src, :dst, :fund, :del, :p, :ff, :fb, :min, :max, :ttl, :eta, :active)
                ON CONFLICT (code) DO UPDATE SET fee_fixed = EXCLUDED.fee_fixed, fee_bps = EXCLUDED.fee_bps,
                    min_amount = EXCLUDED.min_amount, max_amount = EXCLUDED.max_amount, partner = EXCLUDED.partner,
                    quote_ttl_seconds = EXCLUDED.quote_ttl_seconds, est_delivery_minutes = EXCLUDED.est_delivery_minutes,
                    active = EXCLUDED.active""")
                .param("code", c.code()).param("dir", c.direction()).param("sc", c.sendingCountry())
                .param("rc", c.receivingCountry()).param("src", c.sourceCurrency()).param("dst", c.destinationCurrency())
                .param("fund", c.fundingMethod()).param("del", c.deliveryMethod()).param("p", c.partner())
                .param("ff", c.feeFixed()).param("fb", c.feeBps()).param("min", c.minAmount()).param("max", c.maxAmount())
                .param("ttl", c.quoteTtlSeconds()).param("eta", c.estDeliveryMinutes()).param("active", c.active()).update();
        audit.record(actor, "fx.corridor_upserted", "corridor:" + c.code(), Map.of("active", c.active()));
    }

    // ---- quotes and outbound transfers ----------------------------------------------------

    Quote quote(UUID customer, String corridorCode, long sourceAmount) {
        Corridor c = corridor(corridorCode);
        if (!c.active() || !c.direction().equals("outbound") || !c.fundingMethod().equals("wallet")) {
            throw ApiException.unprocessable("corridor_unavailable", "This corridor cannot be quoted from a wallet");
        }
        if (sourceAmount < c.minAmount() || sourceAmount > c.maxAmount()) {
            throw ApiException.badRequest("amount_out_of_range", "Amount must be between " + c.minAmount() + " and " + c.maxAmount());
        }
        FxPartner.Quote pq = partner(c.partner()).quote(c.sourceCurrency(), c.destinationCurrency(), sourceAmount);
        long fee = Math.addExact(c.feeFixed(), Math.multiplyExact(sourceAmount, c.feeBps()) / 10_000);
        long destination = convert(sourceAmount, c.sourceCurrency(), pq.rate(), c.destinationCurrency());
        if (destination <= 0) throw ApiException.badRequest("amount_too_small", "Amount is too small to convert");
        OffsetDateTime own = OffsetDateTime.now().plusSeconds(c.quoteTtlSeconds());
        OffsetDateTime expires = pq.expiresAt().isBefore(own) ? pq.expiresAt() : own;
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO fx_quote (id, corridor, customer_id, partner, partner_quote_ref, source_currency, source_amount,
                    fee, total_debit, rate, destination_currency, destination_amount, expires_at, est_delivery_minutes)
                VALUES (:id, :c, :cust, :p, :pref, :src, :amount, :fee, :total, :rate, :dst, :dest, :exp, :eta)""")
                .param("id", id).param("c", c.code()).param("cust", customer).param("p", c.partner())
                .param("pref", pq.reference()).param("src", c.sourceCurrency()).param("amount", sourceAmount)
                .param("fee", fee).param("total", sourceAmount + fee).param("rate", pq.rate())
                .param("dst", c.destinationCurrency()).param("dest", destination).param("exp", expires)
                .param("eta", c.estDeliveryMinutes()).update();
        return quote(id);
    }

    Quote quote(UUID id) {
        return db.sql("""
                SELECT id, corridor, source_currency, source_amount, fee, total_debit, rate, destination_currency,
                       destination_amount, expires_at, est_delivery_minutes, status FROM fx_quote WHERE id = :id""")
                .param("id", id).query((rs, i) -> new Quote(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getBigDecimal(7), rs.getString(8), rs.getLong(9),
                        rs.getObject(10, OffsetDateTime.class), rs.getInt(11), rs.getString(12)))
                .optional().orElseThrow(() -> ApiException.notFound("Quote not found"));
    }

    /** Executes exactly the accepted quote. Runs inside the caller's idempotent transaction. */
    Transfer execute(UUID customer, UUID quoteId, Map<String, String> recipient) {
        var q = db.sql("""
                SELECT customer_id, status, expires_at, partner, partner_quote_ref, corridor FROM fx_quote
                WHERE id = :id FOR UPDATE""").param("id", quoteId)
                .query((rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class), rs.getString(4), rs.getString(5), rs.getString(6)})
                .optional().orElse(null);
        if (q == null || !customer.equals(q[0])) throw ApiException.notFound("Quote not found");
        if (!q[1].equals("open")) throw ApiException.conflict("quote_" + q[1], "This quote is " + q[1] + "; request a new quote");
        if (!((OffsetDateTime) q[2]).isAfter(OffsetDateTime.now())) {
            db.sql("UPDATE fx_quote SET status = 'expired' WHERE id = :id").param("id", quoteId).update();
            throw ApiException.conflict("quote_expired", "The quote expired. Review a new quote before sending.");
        }
        FxPartner partner = partner((String) q[3]);
        if (!partner.honours((String) q[4])) {
            throw ApiException.conflict("quote_changed", "The partner no longer honours this quote. Review a new quote before sending.");
        }
        Corridor c = corridor((String) q[5]);
        if (!c.active()) throw ApiException.unprocessable("corridor_unavailable", "This corridor is not available");
        Quote quote = quote(quoteId);
        validateRecipient(c, recipient);
        UUID id = UUID.randomUUID();
        String reference = References.next("FX");
        List<Leg> legs = new ArrayList<>();
        legs.add(Leg.debit(CustomerDirectory.available(customer), quote.totalDebit()));
        if (quote.fee() > 0) legs.add(Leg.credit(revenue(quote.sourceCurrency()), quote.fee()));
        legs.add(Leg.credit(fxClearing(quote.sourceCurrency()), quote.sourceAmount()));
        legs.add(Leg.debit(fxClearing(quote.destinationCurrency()), quote.destinationAmount()));
        legs.add(Leg.credit(inFlight(quote.destinationCurrency()), quote.destinationAmount()));
        var posted = ledger.post(Ledger.Posting.of("fx-transfer", id.toString(), "fx_outbound", legs).describedAs(reference)
                .withMetadata(Map.of("quote", quoteId.toString(), "rate", quote.rate().toPlainString())));
        db.sql("""
                INSERT INTO fx_transfer (id, reference, direction, corridor, quote_id, customer_id, partner, source_currency,
                    source_amount, fee, rate, destination_currency, destination_amount, recipient, status, journal_tx)
                VALUES (:id, :ref, 'outbound', :c, :q, :cust, :p, :src, :sa, :fee, :rate, :dst, :da,
                    CAST(:recipient AS jsonb), 'pending', :tx)""")
                .param("id", id).param("ref", reference).param("c", c.code()).param("q", quoteId).param("cust", customer)
                .param("p", c.partner()).param("src", quote.sourceCurrency()).param("sa", quote.sourceAmount())
                .param("fee", quote.fee()).param("rate", quote.rate()).param("dst", quote.destinationCurrency())
                .param("da", quote.destinationAmount()).param("recipient", json.writeValueAsString(recipient))
                .param("tx", posted.transactionId()).update();
        db.sql("UPDATE fx_quote SET status = 'accepted' WHERE id = :id").param("id", quoteId).update();
        audit.record("customer:" + customer, "fx.transfer_created", "fx:" + reference, Map.of("quote", quoteId.toString()));
        return transfer(id);
    }

    private static void validateRecipient(Corridor c, Map<String, String> r) {
        if (r == null || r.get("name") == null || r.get("name").isBlank()) {
            throw ApiException.badRequest("invalid_recipient", "Recipient name is required");
        }
        switch (c.deliveryMethod()) {
            case "bank", "momo", "wallet" -> {
                if (r.get("accountNumber") == null || r.get("accountNumber").isBlank()) {
                    throw ApiException.badRequest("invalid_recipient", "Recipient account is required");
                }
            }
            default -> { }
        }
    }

    /** Sends a pending outbound transfer to its partner. Call after the creating transaction commits. */
    void submit(UUID id) {
        Transfer t = transfer(id);
        if (!t.status().equals("pending") || db.sql("SELECT partner_reference IS NOT NULL FROM fx_transfer WHERE id = :id")
                .param("id", id).query(Boolean.class).single()) return;
        String quoteRef = db.sql("SELECT q.partner_quote_ref FROM fx_quote q JOIN fx_transfer t ON t.quote_id = q.id WHERE t.id = :id")
                .param("id", id).query(String.class).single();
        String partnerName = db.sql("SELECT partner FROM fx_transfer WHERE id = :id").param("id", id).query(String.class).single();
        Map<String, String> recipient = json.convertValue(t.recipient(), json.getTypeFactory()
                .constructMapType(Map.class, String.class, String.class));
        FxPartner.Execution e;
        try {
            e = partner(partnerName).execute(quoteRef, t.reference(), t.sourceAmount(), t.destinationAmount(),
                    t.destinationCurrency(), recipient);
        } catch (RuntimeException ex) {
            e = new FxPartner.Execution(FxPartner.Status.UNKNOWN, null, ex.getClass().getSimpleName());
        }
        apply(t.reference(), e.status(), e.partnerReference(), e.message());
    }

    /** Applies a partner status under a row lock; safe to repeat and out of order. */
    void apply(String reference, FxPartner.Status status, String partnerReference, String message) {
        tx.executeWithoutResult(s -> {
            var row = db.sql("""
                    SELECT id, status, destination_currency, destination_amount, partner, journal_tx, customer_id
                    FROM fx_transfer WHERE reference = :r FOR UPDATE""").param("r", reference)
                    .query((rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                            rs.getLong(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class)})
                    .optional().orElseThrow(() -> ApiException.notFound("Unknown FX transfer"));
            UUID id = (UUID) row[0];
            String current = (String) row[1];
            String currency = (String) row[2];
            long amount = (long) row[3];
            if (partnerReference != null) {
                db.sql("UPDATE fx_transfer SET partner_reference = coalesce(partner_reference, :p) WHERE id = :id")
                        .param("p", partnerReference).param("id", id).update();
            }
            switch (status) {
                case PAID -> {
                    if (current.equals("paid")) return;
                    if (!Set.of("pending", "unknown").contains(current)) {
                        ops.raise("fx_status_conflict", "high", "fx:" + reference, Map.of("recorded", current, "reported", "PAID"));
                        return;
                    }
                    var posted = ledger.post(Ledger.Posting.of("fx-delivery", id.toString(), "fx_delivery", List.of(
                            Leg.debit(inFlight(currency), amount),
                            Leg.credit(partnerClearing((String) row[4], currency), amount))));
                    db.sql("UPDATE fx_transfer SET status = 'paid', delivery_tx = :tx, updated_at = now() WHERE id = :id")
                            .param("tx", posted.transactionId()).param("id", id).update();
                    outbox.publish("fx", reference, "fx.transfer.paid", Map.of("reference", reference), Set.of());
                }
                case FAILED -> {
                    if (current.equals("failed")) return;
                    if (!Set.of("pending", "unknown").contains(current)) {
                        ops.raise("fx_status_conflict", "high", "fx:" + reference, Map.of("recorded", current, "reported", "FAILED"));
                        return;
                    }
                    var reversal = ledger.reverse((UUID) row[5], "fx-reversal", id.toString(), "Partner rejected " + reference);
                    db.sql("""
                            UPDATE fx_transfer SET status = 'failed', reversal_tx = :tx, failure_reason = :m, updated_at = now()
                            WHERE id = :id""").param("tx", reversal.transactionId()).param("m", message).param("id", id).update();
                    outbox.publish("fx", reference, "fx.transfer.failed", Map.of("reference", reference), Set.of());
                }
                case RETURNED -> {
                    if (current.equals("returned")) return;
                    if (!current.equals("paid")) {
                        ops.raise("fx_status_conflict", "high", "fx:" + reference, Map.of("recorded", current, "reported", "RETURNED"));
                        return;
                    }
                    var back = ledger.post(Ledger.Posting.of("fx-return", id.toString(), "fx_return", List.of(
                            Leg.debit(partnerClearing((String) row[4], currency), amount),
                            Leg.credit(inFlight(currency), amount))));
                    var reversal = ledger.reverse((UUID) row[5], "fx-reversal", id.toString(), "Returned " + reference);
                    db.sql("""
                            UPDATE fx_transfer SET status = 'returned', return_tx = :b, reversal_tx = :r, failure_reason = :m,
                                   updated_at = now() WHERE id = :id""")
                            .param("b", back.transactionId()).param("r", reversal.transactionId()).param("m", message)
                            .param("id", id).update();
                    ops.raise("fx_returned", "medium", "fx:" + reference, Map.of("note", "Customer refunded in source currency"));
                    outbox.publish("fx", reference, "fx.transfer.returned", Map.of("reference", reference), Set.of());
                }
                case UNKNOWN -> db.sql("UPDATE fx_transfer SET status = 'unknown', updated_at = now() WHERE id = :id AND status = 'pending'")
                        .param("id", id).update();
                case PENDING, NOT_FOUND -> { }
            }
        });
    }

    int recover() {
        List<String> open = db.sql("SELECT reference FROM fx_transfer WHERE status IN ('pending', 'unknown') AND direction = 'outbound'")
                .query(String.class).list();
        int resolved = 0;
        for (String reference : open) {
            String partnerName = db.sql("SELECT partner FROM fx_transfer WHERE reference = :r").param("r", reference)
                    .query(String.class).single();
            boolean submitted = db.sql("SELECT partner_reference IS NOT NULL FROM fx_transfer WHERE reference = :r")
                    .param("r", reference).query(Boolean.class).single();
            FxPartner.Execution e = partner(partnerName).query(reference);
            if (e.status() == FxPartner.Status.NOT_FOUND && !submitted) {
                submit(db.sql("SELECT id FROM fx_transfer WHERE reference = :r").param("r", reference).query(UUID.class).single());
                continue;
            }
            apply(reference, e.status(), e.partnerReference(), e.message());
            resolved++;
        }
        return resolved;
    }

    Transfer transfer(UUID id) {
        return db.sql("""
                SELECT id, reference, direction, corridor, source_currency, source_amount, fee, rate, destination_currency,
                       destination_amount, status, failure_reason, recipient::text, created_at FROM fx_transfer WHERE id = :id""")
                .param("id", id).query((rs, i) -> new Transfer(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getBigDecimal(8), rs.getString(9),
                        rs.getLong(10), rs.getString(11), rs.getString(12), json.readTree(rs.getString(13)),
                        rs.getObject(14, OffsetDateTime.class))).single();
    }

    Optional<UUID> transferId(String reference, UUID customer) {
        return db.sql("SELECT id FROM fx_transfer WHERE reference = :r AND customer_id = :c")
                .param("r", reference).param("c", customer).query(UUID.class).optional();
    }

    // ---- inbound remittances --------------------------------------------------------------

    record Inbound(String partnerReference, String corridor, long sourceAmount, String sourceCurrency,
                   BigDecimal rate, long destinationAmount, String destinationCurrency, String recipientPhone,
                   String senderName) {}

    Transfer inbound(String partnerName, Inbound in) {
        return tx.execute(s -> {
            var existing = db.sql("SELECT id FROM fx_transfer WHERE partner = :p AND partner_reference = :r")
                    .param("p", partnerName).param("r", in.partnerReference()).query(UUID.class).optional();
            if (existing.isPresent()) return transfer(existing.get());
            Corridor c = corridor(in.corridor());
            if (!c.active() || !c.direction().equals("inbound") || !c.partner().equals(partnerName)
                    || !c.sourceCurrency().equals(in.sourceCurrency()) || !c.destinationCurrency().equals(in.destinationCurrency())) {
                throw ApiException.unprocessable("corridor_mismatch", "Instruction does not match an active inbound corridor");
            }
            long expected = convert(in.sourceAmount(), in.sourceCurrency(), in.rate(), in.destinationCurrency());
            if (expected != in.destinationAmount()) {
                ops.raise("fx_inbound_amount_mismatch", "high", "fx-inbound:" + partnerName + ":" + in.partnerReference(),
                        Map.of("expected", expected, "reported", in.destinationAmount()));
                throw ApiException.unprocessable("amount_mismatch", "Destination amount does not match the rate");
            }
            if (!c.deliveryMethod().equals("wallet")) {
                throw ApiException.unprocessable("delivery_unsupported", "Only wallet delivery is automated for inbound remittances");
            }
            UUID customer = db.sql("SELECT id FROM customer WHERE phone = :p AND status = 'active'")
                    .param("p", References.msisdn(in.recipientPhone())).query(UUID.class).optional()
                    .orElseThrow(() -> ApiException.unprocessable("recipient_not_found", "No active ELEPAY wallet for this number"));
            UUID id = UUID.randomUUID();
            String reference = References.next("FX");
            var posted = ledger.post(Ledger.Posting.of("fx-inbound", partnerName + ":" + in.partnerReference(), "fx_inbound",
                    List.of(Leg.debit(partnerClearing(partnerName, in.sourceCurrency()), in.sourceAmount()),
                            Leg.credit(fxClearing(in.sourceCurrency()), in.sourceAmount()),
                            Leg.debit(fxClearing(in.destinationCurrency()), in.destinationAmount()),
                            Leg.credit(CustomerDirectory.available(customer), in.destinationAmount())))
                    .describedAs(reference).withMetadata(Map.of("rate", in.rate().toPlainString())));
            db.sql("""
                    INSERT INTO fx_transfer (id, reference, direction, corridor, customer_id, partner, partner_reference,
                        source_currency, source_amount, fee, rate, destination_currency, destination_amount, recipient,
                        status, journal_tx)
                    VALUES (:id, :ref, 'inbound', :c, :cust, :p, :pref, :src, :sa, 0, :rate, :dst, :da,
                        CAST(:recipient AS jsonb), 'paid', :tx)""")
                    .param("id", id).param("ref", reference).param("c", c.code()).param("cust", customer)
                    .param("p", partnerName).param("pref", in.partnerReference()).param("src", in.sourceCurrency())
                    .param("sa", in.sourceAmount()).param("rate", in.rate()).param("dst", in.destinationCurrency())
                    .param("da", in.destinationAmount())
                    .param("recipient", json.writeValueAsString(Map.of("phone", in.recipientPhone(),
                            "sender", String.valueOf(in.senderName()))))
                    .param("tx", posted.transactionId()).update();
            db.sql("""
                    INSERT INTO wallet_operation (id, reference, customer_id, kind, amount, status, counterparty, journal_tx)
                    VALUES (:id, :ref, :c, 'topup', :amount, 'completed', :cp, :tx)""")
                    .param("id", UUID.randomUUID()).param("ref", reference).param("c", customer)
                    .param("amount", in.destinationAmount()).param("cp", "Remittance from " + in.senderName())
                    .param("tx", posted.transactionId()).update();
            return transfer(id);
        });
    }

    // ---- reconciliation ---------------------------------------------------------------------

    record StatementLine(String reference, String sourceCurrency, long sourceAmount, String destinationCurrency,
                         long destinationAmount, String status) {}

    record ReconLine(String reference, String result, String detail) {}

    List<ReconLine> reconcile(String partnerName, String period, List<StatementLine> lines, String actor) {
        return tx.execute(s -> {
            UUID run = UUID.randomUUID();
            List<ReconLine> results = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int n = 0;
            for (StatementLine line : lines) {
                var t = db.sql("""
                        SELECT id, reference, source_currency, source_amount, destination_currency, destination_amount, status
                        FROM fx_transfer WHERE partner = :p AND (reference = :r OR partner_reference = :r)""")
                        .param("p", partnerName).param("r", line.reference())
                        .query((rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                                rs.getLong(4), rs.getString(5), rs.getLong(6), rs.getString(7)}).optional();
                String result;
                String detail = null;
                if (t.isEmpty()) {
                    result = "missing_internal";
                } else {
                    seen.add((String) t.get()[1]);
                    boolean amounts = t.get()[2].equals(line.sourceCurrency()) && (long) t.get()[3] == line.sourceAmount()
                            && t.get()[4].equals(line.destinationCurrency()) && (long) t.get()[5] == line.destinationAmount();
                    if (!amounts) {
                        result = "amount_mismatch";
                        detail = "ELEPAY " + t.get()[3] + " " + t.get()[2] + " / " + t.get()[5] + " " + t.get()[4];
                    } else if (!t.get()[6].equals(line.status())) {
                        result = "status_mismatch";
                        detail = "ELEPAY status " + t.get()[6];
                    } else {
                        result = "matched";
                    }
                }
                if (!result.equals("matched")) {
                    ops.raise("fx_recon_" + result, "high", "fx-recon:" + partnerName + ":" + line.reference(),
                            Map.of("period", period, "detail", String.valueOf(detail)));
                }
                results.add(new ReconLine(line.reference(), result, detail));
                n++;
            }
            List<String> missing = db.sql("""
                    SELECT reference FROM fx_transfer WHERE partner = :p AND status IN ('paid', 'returned')
                    AND to_char(created_at, 'YYYY-MM-DD') LIKE :period || '%'""")
                    .param("p", partnerName).param("period", period).query(String.class).list();
            for (String ref : missing) {
                if (!seen.contains(ref)) {
                    results.add(new ReconLine(ref, "missing_partner", "Not on partner statement"));
                    ops.raise("fx_recon_missing_partner", "high", "fx-recon:" + partnerName + ":" + ref, Map.of("period", period));
                }
            }
            Map<String, Long> summary = results.stream().collect(Collectors.groupingBy(ReconLine::result, TreeMap::new,
                    Collectors.counting()));
            db.sql("INSERT INTO reconciliation_run (id, rail, period, imported_by, summary) VALUES (:id, :p, :per, :by, CAST(:s AS jsonb))")
                    .param("id", run).param("p", "fx:" + partnerName).param("per", period).param("by", actor)
                    .param("s", json.writeValueAsString(summary)).update();
            audit.record(actor, "fx.reconciled", "fx-recon:" + partnerName, Map.of("period", period, "lines", n));
            return results;
        });
    }
}
