package com.elepay.checkout;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.common.References;
import com.elepay.merchant.MerchantAccess;
import com.elepay.rails.RailTransactions;
import com.elepay.wallet.CustomerDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * Customer checkout without an app. A checkout is identified by its short
 * receipt reference, so a customer who loses connectivity reopens the same
 * link and resumes: an in-flight rail payment is returned instead of charging
 * again.
 */
@RestController
class PublicCheckouts {
    private static final Set<String> NETWORKS = Set.of("mtn", "telecel", "airteltigo");

    private final CheckoutService checkouts;
    private final MerchantAccess merchants;
    private final RailTransactions rails;
    private final CustomerDirectory customers;
    private final Idempotency idempotency;
    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final String publicBaseUrl;

    PublicCheckouts(CheckoutService checkouts, MerchantAccess merchants, RailTransactions rails,
                    CustomerDirectory customers, Idempotency idempotency, JdbcClient db, PlatformTransactionManager tm,
                    @Value("${elepay.public-base-url}") String publicBaseUrl) {
        this.checkouts = checkouts;
        this.merchants = merchants;
        this.rails = rails;
        this.customers = customers;
        this.idempotency = idempotency;
        this.db = db;
        this.tx = new TransactionTemplate(tm);
        this.publicBaseUrl = publicBaseUrl;
    }

    record PublicLine(String merchant, String kind, String description, long amount) {}

    record MethodTotal(String method, long fee, long total) {}

    record PublicCheckout(String reference, String collector, String description, String status, String currency,
                          List<PublicLine> lines, long subtotal, List<MethodTotal> methods, OffsetDateTime expiresAt,
                          OffsetDateTime paidAt, String supportReference) {}

    record RailPay(@NotBlank @Pattern(regexp = "momo|card") String method, @Size(max = 40) String phone,
                   @Size(max = 20) String network, @Email @Size(max = 200) String email) {}

    record AmountRequest(@Positive Long amount, @Size(max = 200) String description) {}

    @GetMapping("/v1/public/checkouts/{reference}")
    public PublicCheckout view(@PathVariable String reference) {
        var c = checkouts.load(reference, false);
        List<MethodTotal> methods = List.of("wallet", "momo", "card").stream().map(m -> {
            long fee = checkouts.customerFee(m, c.subtotal());
            return new MethodTotal(m, fee, c.subtotal() + fee);
        }).toList();
        return new PublicCheckout(c.reference(), c.collectorName(), c.description(), c.status(), "GHS",
                c.allocations().stream().map(a -> new PublicLine(a.merchantName(), a.kind(), a.description(), a.amount())).toList(),
                c.subtotal(), methods, c.expiresAt(), c.paidAt(), c.reference());
    }

    @GetMapping("/v1/public/checkouts/{reference}/status")
    public CheckoutService.PaymentResult status(@PathVariable String reference) {
        return checkouts.status(reference);
    }

    /** Fee quote for any amount and method, e.g. GH₵5 or GH₵10 purchases. */
    @GetMapping("/v1/public/fees/quote")
    public MethodTotal quote(@RequestParam long amount, @RequestParam String method) {
        if (amount <= 0) throw ApiException.badRequest("invalid_amount", "Amount must be positive");
        long fee = checkouts.customerFee(method, amount);
        return new MethodTotal(method, fee, amount + fee);
    }

    @PostMapping("/v1/public/checkouts/{reference}/pay")
    public CheckoutService.PaymentResult payWithRail(@PathVariable String reference,
                                                     @RequestHeader("Idempotency-Key") String key,
                                                     @Valid @RequestBody RailPay request) {
        String msisdn = null;
        if (request.method().equals("momo")) {
            msisdn = References.msisdn(request.phone());
            if (request.network() == null || !NETWORKS.contains(request.network())) {
                throw ApiException.badRequest("invalid_network", "Choose mtn, telecel or airteltigo");
            }
        }
        String phone = msisdn;
        var started = tx.execute(s -> idempotency.execute("checkout-pay:" + reference, key, request,
                CheckoutService.PaymentResult.class, () -> checkouts.startRailPayment(reference, request.method(), phone,
                        request.network(), request.email(), publicBaseUrl + "/pay/" + reference)));
        if (started != null && started.attemptId() != null) {
            UUID railTx = checkouts.railTxOf(started.attemptId());
            if (railTx != null) rails.submit(railTx); // no-op unless still 'created'
        }
        return checkouts.status(reference);
    }

    @PostMapping("/v1/checkouts/{reference}/pay/wallet")
    public CheckoutService.PaymentResult payWithWallet(@PathVariable String reference,
                                                       @RequestHeader("Idempotency-Key") String key) {
        UUID customer = customers.currentCustomer();
        return tx.execute(s -> idempotency.execute("checkout-wallet:" + customer, key, Map.of("reference", reference),
                CheckoutService.PaymentResult.class, () -> checkouts.payWithWallet(reference, customer)));
    }

    // ---- links and merchant codes -----------------------------------------------------------

    @GetMapping("/v1/public/links/{slug}")
    public Map<String, Object> link(@PathVariable String slug) {
        return db.sql("""
                SELECT l.title, l.amount, m.trading_name FROM payment_link l JOIN merchant m ON m.id = l.merchant_id
                WHERE l.slug = :s AND l.active AND m.status = 'active'""").param("s", slug)
                .query((rs, i) -> Map.<String, Object>of("title", rs.getString(1),
                        "amount", rs.getObject(2) == null ? "open" : rs.getLong(2), "merchant", rs.getString(3)))
                .optional().orElseThrow(() -> ApiException.notFound("Payment link not found"));
    }

    @PostMapping("/v1/public/links/{slug}/checkouts")
    public Map<String, String> checkoutFromLink(@PathVariable String slug, @Valid @RequestBody AmountRequest request) {
        record L(UUID id, UUID merchant, UUID branch, Long amount, String title) {}
        L link = db.sql("SELECT id, merchant_id, branch_id, amount, title FROM payment_link WHERE slug = :s AND active")
                .param("s", slug).query((rs, i) -> new L(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), (Long) rs.getObject(4), rs.getString(5)))
                .optional().orElseThrow(() -> ApiException.notFound("Payment link not found"));
        long amount = link.amount() != null ? link.amount() : require(request.amount());
        var c = checkouts.create(link.merchant(), link.branch(), link.id(),
                List.of(new CheckoutService.AllocationRequest(link.merchant(), "sale", amount, link.title())),
                link.title(), 60, "public:link:" + slug);
        return Map.of("reference", c.reference(), "payUrl", publicBaseUrl + "/pay/" + c.reference());
    }

    @PostMapping("/v1/public/merchant-codes/{code}/checkouts")
    public Map<String, String> checkoutFromCode(@PathVariable String code, @Valid @RequestBody AmountRequest request) {
        var merchant = merchants.findByCode(code).filter(m -> m.status().equals("active"))
                .orElseThrow(() -> ApiException.notFound("No active merchant uses this code"));
        long amount = require(request.amount());
        var c = checkouts.create(merchant.id(), null, null,
                List.of(new CheckoutService.AllocationRequest(merchant.id(), "sale", amount, request.description())),
                request.description(), 30, "public:code:" + code);
        return Map.of("reference", c.reference(), "merchant", merchant.tradingName(),
                "payUrl", publicBaseUrl + "/pay/" + c.reference());
    }

    private static long require(Long amount) {
        if (amount == null || amount <= 0) throw ApiException.badRequest("amount_required", "Enter an amount");
        return amount;
    }
}
