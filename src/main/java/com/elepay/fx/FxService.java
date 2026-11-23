package com.elepay.fx;

import com.elepay.common.ApiException;
import com.elepay.common.Audit;
import com.elepay.common.OpsQueue;
import com.elepay.ledger.Ledger;
import com.elepay.ledger.Ledger.Leg;
import com.elepay.outbox.Outbox;
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
 * FX rates, pricing, exposure and historical transaction reconciliation.
 *
 * <p>Every journal transaction balances within each currency. Conversion is
 * expressed with linked FX clearing entries: the source currency leg ends in
 * {@code system:fx_clearing:SRC} and the destination leg starts from
 * {@code system:fx_clearing:DST} inside the same journal transaction.
 * Historical destination funds wait in {@code fx_in_flight} until the partner
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
    private final FxRateService rateService;
    private final FxPricingService pricingService;
    private final FxRoutingService routingService;
    private final FxDeliveryEstimator deliveryEstimator;
    private final FxExposureService exposureService;
    private final FxDualReconciliationService dualReconciliationService;

    FxService(JdbcClient db, Ledger ledger, List<FxPartner> partners, Outbox outbox, Audit audit, OpsQueue ops,
              ObjectMapper json, PlatformTransactionManager tm, FxRateService rateService,
              FxPricingService pricingService, FxRoutingService routingService,
              FxDeliveryEstimator deliveryEstimator, FxExposureService exposureService,
              FxDualReconciliationService dualReconciliationService) {
        this.db = db;
        this.ledger = ledger;
        this.partners = partners.stream().collect(Collectors.toMap(FxPartner::name, Function.identity()));
        this.outbox = outbox;
        this.audit = audit;
        this.ops = ops;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
        this.rateService = rateService;
        this.pricingService = pricingService;
        this.routingService = routingService;
        this.deliveryEstimator = deliveryEstimator;
        this.exposureService = exposureService;
        this.dualReconciliationService = dualReconciliationService;
    }

    record Corridor(String code, String direction, String sendingCountry, String receivingCountry,
                    String sourceCurrency, String destinationCurrency, String fundingMethod, String deliveryMethod,
                    String partner, long feeFixed, int feeBps, long minAmount, long maxAmount, int quoteTtlSeconds,
                    int estDeliveryMinutes, boolean active) {}

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
        return ledger.openSystem(code, "clearing", currency, true);
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

    // ---- Advanced FX Capabilities (Algorithms 059 - 064) -----------------------------------

    public FxRateService.RateSource ingestRate(String source, String sourceCurrency, String destinationCurrency,
                                               BigDecimal bidRate, BigDecimal askRate, double weight) {
        return rateService.ingestRate(source, sourceCurrency, destinationCurrency, bidRate, askRate, weight);
    }

    public FxRateService.BlendedRate synthesizeRate(String sourceCurrency, String destinationCurrency, java.time.Duration maxAge) {
        return rateService.synthesizeRate(sourceCurrency, destinationCurrency, maxAge);
    }

    public FxRateService.BlendedRate latestRate(String sourceCurrency, String destinationCurrency) {
        return rateService.latestBlendedRate(sourceCurrency, destinationCurrency);
    }

    public FxPricingService.PricingResult price(String corridorCode, String customerTier, long sourceAmount, BigDecimal baseRate) {
        Corridor c = corridor(corridorCode);
        return pricingService.calculatePricing(corridorCode, customerTier, c.sourceCurrency(), c.destinationCurrency(),
                sourceAmount, baseRate, c.feeFixed(), c.feeBps());
    }

    public List<FxRoutingService.PartnerRoute> rankRoutes(String corridorCode, long sourceAmount) {
        return routingService.rankRoutes(corridorCode, sourceAmount);
    }

    public FxDeliveryEstimator.DeliveryEstimate estimateDelivery(String corridorCode, String deliveryMethod, String network, OffsetDateTime requestTime) {
        return deliveryEstimator.estimate(corridorCode, deliveryMethod, network, requestTime);
    }

    public FxExposureService.ExposureStatus checkExposure(String currency) {
        return exposureService.checkExposure(currency);
    }

    public FxDualReconciliationService.DualReconReport reconcileDual(String partnerName, String period, String sourceCurrency,
                                                                     String destinationCurrency, long statedClosingBalance,
                                                                     List<FxDualReconciliationService.DualStatementLine> lines,
                                                                     String actor) {
        return dualReconciliationService.reconcileDual(partnerName, period, sourceCurrency, destinationCurrency,
                statedClosingBalance, lines, actor);
    }
}
