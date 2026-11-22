package com.elepay.checkout;

import static com.elepay.merchant.MerchantAccess.Permission.*;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.common.References;
import com.elepay.identity.Actor;
import com.elepay.merchant.MerchantAccess;
import com.elepay.rails.RailTransactions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/** Merchant-facing checkout, payment link, refund and report APIs. Every route enforces merchant scope. */
@RestController
@RequestMapping("/v1/merchants/{merchantId}")
class Checkouts {
    private final CheckoutService checkouts;
    private final MerchantAccess access;
    private final Idempotency idempotency;
    private final RailTransactions rails;
    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final String publicBaseUrl;

    Checkouts(CheckoutService checkouts, MerchantAccess access, Idempotency idempotency, RailTransactions rails,
              JdbcClient db, PlatformTransactionManager tm, @Value("${elepay.public-base-url}") String publicBaseUrl) {
        this.checkouts = checkouts;
        this.access = access;
        this.idempotency = idempotency;
        this.rails = rails;
        this.db = db;
        this.tx = new TransactionTemplate(tm);
        this.publicBaseUrl = publicBaseUrl;
    }

    record AllocationInput(@NotNull UUID merchantId, @Pattern(regexp = "sale|delivery") String kind,
                           @Positive long amount, @Size(max = 200) String description) {}

    record CreateCheckout(@Positive Long amount, @Size(max = MAX) List<@Valid AllocationInput> allocations,
                          @Size(max = 500) String description, UUID branchId,
                          @Min(1) @Max(10080) Integer expiresInMinutes,
                          @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._:-]+") String orderReference) {
        static final int MAX = CheckoutService.MAX_ALLOCATIONS;
    }

    record CheckoutCreated(String reference, String orderReference, String status, long subtotal, String payUrl,
                           OffsetDateTime expiresAt) {}

    record RefundRequest(@Positive long amount, @NotBlank @Size(max = 500) String reason) {}

    record LinkRequest(@NotBlank @Size(max = 100) String title, @Positive Long amount, UUID branchId) {}

    record Link(UUID id, String slug, String title, Long amount, UUID branchId, boolean active, String url) {}

    record Line(String reference, int line, String kind, String description, long amount, long fee, long net,
                long refunded, long disputed, long pending, long settled, String status, String method,
                OffsetDateTime paidAt, String collector) {}

    record CheckoutSummary(String reference, String status, long subtotal, String method, Long totalPaid,
                           UUID branchId, OffsetDateTime createdAt, OffsetDateTime paidAt, List<Line> lines) {}

    // ---- checkouts --------------------------------------------------------------------------

    @PostMapping("/checkouts")
    @Transactional
    public CheckoutCreated create(@PathVariable UUID merchantId, @RequestHeader("Idempotency-Key") String key,
                                  @Valid @RequestBody CreateCheckout request) {
        var a = access.require(merchantId, COLLECT);
        return idempotency.execute("checkout-create:" + merchantId, key, request, CheckoutCreated.class, () -> {
            List<CheckoutService.AllocationRequest> allocations;
            if (request.allocations() == null || request.allocations().isEmpty()) {
                if (request.amount() == null) throw ApiException.badRequest("amount_required", "Provide an amount or allocations");
                allocations = List.of(new CheckoutService.AllocationRequest(merchantId, "sale", request.amount(), null));
            } else {
                if (request.amount() != null) throw ApiException.badRequest("ambiguous_amount", "Provide either amount or allocations");
                allocations = request.allocations().stream().map(x -> new CheckoutService.AllocationRequest(
                        x.merchantId(), x.kind(), x.amount(), x.description())).toList();
            }
            UUID branch = branchFor(a, request.branchId());
            var c = checkouts.create(merchantId, branch, null, allocations, request.description(),
                    request.expiresInMinutes() == null ? 60 : request.expiresInMinutes(), a.actor().subject(),
                    request.orderReference());
            return new CheckoutCreated(c.reference(), request.orderReference(), c.status(), c.subtotal(),
                    publicBaseUrl + "/pay/" + c.reference(), c.expiresAt());
        });
    }

    /** Platform order lookup by the platform's own order reference. */
    @GetMapping("/orders/{orderReference}")
    public CheckoutSummary byOrder(@PathVariable UUID merchantId, @PathVariable String orderReference) {
        var a = access.require(merchantId, VIEW);
        UUID id = db.sql("SELECT id FROM checkout WHERE collector_id = :m AND order_reference = :o")
                .param("m", merchantId).param("o", orderReference).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        var c = checkouts.load(id, false);
        if (a.branchScoped() && !a.branchId().equals(c.branchId())) throw ApiException.notFound("Order not found");
        return summary(c, null);
    }

    /** Cancels an unpaid order. A payment in flight must finish or fail first. */
    @PostMapping("/checkouts/{reference}/cancel")
    public CheckoutSummary cancel(@PathVariable UUID merchantId, @PathVariable String reference) {
        var a = access.require(merchantId, COLLECT);
        return summary(checkouts.cancel(merchantId, a.branchId(), reference, a.actor().subject()), null);
    }

    private UUID branchFor(MerchantAccess.Access a, UUID requested) {
        if (a.branchScoped()) {
            if (requested != null && !requested.equals(a.branchId())) throw ApiException.forbidden("Cashiers collect for their own branch");
            return a.branchId();
        }
        if (requested != null && !access.branchBelongs(a.merchantId(), requested)) {
            throw ApiException.badRequest("unknown_branch", "Branch not found");
        }
        return requested;
    }

    /** Checkouts this merchant collected. Cashiers see their branch only. */
    @GetMapping("/checkouts")
    public List<CheckoutSummary> collected(@PathVariable UUID merchantId, @RequestParam(defaultValue = "50") int limit) {
        var a = access.require(merchantId, VIEW);
        List<UUID> ids = db.sql("""
                SELECT id FROM checkout WHERE collector_id = :m AND (CAST(:branch AS uuid) IS NULL OR branch_id = CAST(:branch AS uuid))
                ORDER BY created_at DESC LIMIT :limit""")
                .param("m", merchantId).param("branch", a.branchId()).param("limit", Math.min(limit, 200))
                .query(UUID.class).list();
        return ids.stream().map(id -> summary(checkouts.load(id, false), null)).toList();
    }

    @GetMapping("/checkouts/{reference}")
    public CheckoutSummary one(@PathVariable UUID merchantId, @PathVariable String reference) {
        var a = access.require(merchantId, VIEW);
        var c = checkouts.load(reference, false);
        if (c.collectorId().equals(merchantId) && (!a.branchScoped() || a.branchId().equals(c.branchId()))) {
            return summary(c, null);
        }
        if (!a.branchScoped() && c.allocations().stream().anyMatch(x -> x.merchantId().equals(merchantId))) {
            return summary(c, merchantId); // A seller sees only its own allocations.
        }
        throw ApiException.notFound("Checkout not found");
    }

    private CheckoutSummary summary(CheckoutService.Checkout c, UUID onlyMerchant) {
        List<Line> lines = c.allocations().stream()
                .filter(x -> onlyMerchant == null || x.merchantId().equals(onlyMerchant))
                .map(x -> line(c, x)).toList();
        boolean full = onlyMerchant == null;
        return new CheckoutSummary(c.reference(), c.status(), full ? c.subtotal() : lines.stream().mapToLong(Line::amount).sum(),
                c.paymentMethod(), full ? c.totalPaid() : null, full ? c.branchId() : null, c.createdAt(), c.paidAt(), lines);
    }

    private static Line line(CheckoutService.Checkout c, CheckoutService.Allocation x) {
        return new Line(c.reference(), x.line(), x.kind(), x.description(), x.amount(), x.merchantFee(), x.net(),
                x.refunded(), x.disputed(), x.pendingAmount(), x.settledAmount(), x.status(c.status()),
                c.paymentMethod(), c.paidAt(), c.collectorName());
    }

    /** Allocations paid to this merchant across all collectors (its own sales ledger). */
    @GetMapping("/transactions")
    public List<Line> transactions(@PathVariable UUID merchantId, @RequestParam(defaultValue = "100") int limit) {
        var a = access.require(merchantId, VIEW);
        record Ref(UUID checkout, int line) {}
        List<Ref> refs = db.sql("""
                SELECT a.checkout_id, a.line FROM checkout_allocation a JOIN checkout c ON c.id = a.checkout_id
                WHERE a.merchant_id = :m AND c.status IN ('paid', 'processing')
                  AND (CAST(:branch AS uuid) IS NULL OR (c.collector_id = :m AND c.branch_id = CAST(:branch AS uuid)))
                ORDER BY c.created_at DESC, a.line LIMIT :limit""")
                .param("m", merchantId).param("branch", a.branchId()).param("limit", Math.min(limit, 500))
                .query((rs, i) -> new Ref(rs.getObject(1, UUID.class), rs.getInt(2))).list();
        return refs.stream().map(r -> {
            var c = checkouts.load(r.checkout(), false);
            return line(c, c.allocations().stream().filter(x -> x.line() == r.line()).findFirst().orElseThrow());
        }).toList();
    }

    // ---- refunds ----------------------------------------------------------------------------

    @PostMapping("/checkouts/{reference}/allocations/{line}/refunds")
    public CheckoutService.Refund refund(@PathVariable UUID merchantId, @PathVariable String reference,
                                         @PathVariable int line, @RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody RefundRequest request) {
        var a = access.require(merchantId, REFUND);
        var refund = tx.execute(s -> idempotency.execute("refund:" + merchantId, key,
                Map.of("reference", reference, "line", line, "request", request), CheckoutService.Refund.class,
                () -> checkouts.refund(a, reference, line, request.amount(), request.reason())));
        if (refund != null && refund.status().equals("pending")) {
            UUID railTx = checkouts.pendingRefundRailTx(refund.id());
            if (railTx != null) rails.submit(railTx);
        }
        return refund;
    }

    @GetMapping("/refunds")
    public List<Map<String, Object>> refunds(@PathVariable UUID merchantId) {
        access.require(merchantId, FINANCE_VIEW);
        return db.sql("""
                SELECT r.reference, c.reference AS checkout, r.line, r.amount, r.fee_returned, r.destination, r.status,
                       r.reason, r.created_at
                FROM refund r JOIN checkout c ON c.id = r.checkout_id
                JOIN checkout_allocation a ON a.checkout_id = r.checkout_id AND a.line = r.line
                WHERE a.merchant_id = :m OR c.collector_id = :m ORDER BY r.created_at DESC LIMIT 200""")
                .param("m", merchantId).query().listOfRows();
    }

    @GetMapping("/settlements")
    public List<CheckoutService.SettlementBatch> settlements(@PathVariable UUID merchantId) {
        access.require(merchantId, FINANCE_VIEW);
        return db.sql("SELECT id FROM settlement_batch WHERE merchant_id = :m ORDER BY created_at DESC LIMIT 200")
                .param("m", merchantId).query(UUID.class).list().stream().map(checkouts::settlementBatch).toList();
    }

    @GetMapping("/disputes")
    public List<CheckoutService.Dispute> disputes(@PathVariable UUID merchantId) {
        access.require(merchantId, FINANCE_VIEW);
        return db.sql("""
                SELECT d.id FROM dispute d JOIN checkout_allocation a ON a.checkout_id = d.checkout_id AND a.line = d.line
                WHERE a.merchant_id = :m ORDER BY d.created_at DESC""").param("m", merchantId)
                .query(UUID.class).list().stream().map(checkouts::dispute).toList();
    }

    // ---- payment links and QR ----------------------------------------------------------------

    @PostMapping("/payment-links")
    @Transactional
    public Link createLink(@PathVariable UUID merchantId, @Valid @RequestBody LinkRequest request) {
        var a = access.require(merchantId, COLLECT);
        UUID branch = branchFor(a, request.branchId());
        UUID id = UUID.randomUUID();
        String slug = References.next("L").substring(2).toLowerCase();
        db.sql("INSERT INTO payment_link (id, merchant_id, branch_id, slug, title, amount) VALUES (:id, :m, :b, :s, :t, :a)")
                .param("id", id).param("m", merchantId).param("b", branch).param("s", slug).param("t", request.title())
                .param("a", request.amount()).update();
        return new Link(id, slug, request.title(), request.amount(), branch, true, publicBaseUrl + "/pay/l/" + slug);
    }

    @GetMapping("/payment-links")
    public List<Link> links(@PathVariable UUID merchantId) {
        var a = access.require(merchantId, VIEW);
        return db.sql("""
                SELECT id, slug, title, amount, branch_id, active FROM payment_link
                WHERE merchant_id = :m AND (CAST(:branch AS uuid) IS NULL OR branch_id = CAST(:branch AS uuid))
                ORDER BY created_at DESC""")
                .param("m", merchantId).param("branch", a.branchId())
                .query((rs, i) -> new Link(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        (Long) rs.getObject(4), rs.getObject(5, UUID.class), rs.getBoolean(6),
                        publicBaseUrl + "/pay/l/" + rs.getString(2))).list();
    }

    /** QR payload for a payment link or the merchant code; clients render it as a QR image. */
    @GetMapping("/qr")
    public Map<String, String> qr(@PathVariable UUID merchantId, @RequestParam(required = false) String link) {
        access.require(merchantId, VIEW);
        var merchant = access.find(merchantId).orElseThrow();
        String url = link != null ? publicBaseUrl + "/pay/l/" + link : publicBaseUrl + "/pay/m/" + merchant.merchantCode();
        String qrPayload = EmvcoQr.builder()
                .pointOfInitiation(link != null ? EmvcoQr.POINT_DYNAMIC : EmvcoQr.POINT_STATIC)
                .merchantId(merchant.id().toString())
                .merchantCode(merchant.merchantCode())
                .merchantName(merchant.tradingName())
                .merchantCity("Accra")
                .countryCode("GH")
                .currency(EmvcoQr.DEFAULT_CURRENCY_GHS)
                .reference(link)
                .build();
        return Map.of(
                "payload", url,
                "qrPayload", qrPayload,
                "merchantCode", merchant.merchantCode(),
                "merchantName", merchant.tradingName()
        );
    }
}
