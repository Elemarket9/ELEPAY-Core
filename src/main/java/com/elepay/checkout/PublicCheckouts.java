package com.elepay.checkout;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.common.References;
import com.elepay.common.qr.EmvcoQr;
import com.elepay.merchant.MerchantAccess;
import com.elepay.operations.EmtechCapture;
import com.elepay.rails.RailTransactions;
import com.elepay.wallet.CustomerDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.http.HttpServletRequest;
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
    private static final int MAX_PAY_ATTEMPT_ENTRIES = 10_000;

    private final CheckoutService checkouts;
    private final MerchantAccess merchants;
    private final RailTransactions rails;
    private final CustomerDirectory customers;
    private final Idempotency idempotency;
    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final com.elepay.common.FinancialTxRetry txRetry;
    private final Map<String, AtomicInteger> payAttempts = Collections.synchronizedMap(
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AtomicInteger> eldest) {
                    return size() > MAX_PAY_ATTEMPT_ENTRIES;
                }
            });
    private final String publicBaseUrl;
    private final com.elepay.identity.KillSwitchService killSwitchService;
    private final EmtechCapture regulatory;

    PublicCheckouts(CheckoutService checkouts, MerchantAccess merchants, RailTransactions rails,
                    CustomerDirectory customers, Idempotency idempotency, JdbcClient db, PlatformTransactionManager tm,
                    EmtechCapture regulatory,
                    @Value("${elepay.public-base-url}") String publicBaseUrl,
                    @org.springframework.beans.factory.annotation.Autowired(required = false) com.elepay.identity.KillSwitchService killSwitchService) {
        this.checkouts = checkouts;
        this.merchants = merchants;
        this.rails = rails;
        this.customers = customers;
        this.idempotency = idempotency;
        this.db = db;
        this.tx = new TransactionTemplate(tm);
        this.txRetry = new com.elepay.common.FinancialTxRetry(tm);
        this.publicBaseUrl = publicBaseUrl;
        this.killSwitchService = killSwitchService;
        this.regulatory = regulatory;
    }

    record PublicLine(String merchant, String kind, String description, long amount) {}

    record MethodTotal(String method, long fee, long total) {}

    record PublicCheckout(String reference, String collector, String description, String status, String currency,
                          List<PublicLine> lines, long subtotal, List<MethodTotal> methods, OffsetDateTime expiresAt,
                          OffsetDateTime paidAt, String supportReference) {}

    record RailPay(@NotBlank @Pattern(regexp = "momo|card|ghqr") String method, @Size(max = 40) String phone,
                   @Size(max = 20) String network, @Email @Size(max = 200) String email) {}

    record AmountRequest(@Positive Long amount, @Size(max = 200) String description) {}

    @GetMapping("/v1/public/checkouts/{reference}")
    public PublicCheckout view(@PathVariable String reference) {
        var c = checkouts.load(reference, false);
        boolean walletEnabled = killSwitchService == null || killSwitchService.isConsumerWalletEnabled();
        List<MethodTotal> methods = List.of("wallet", "momo", "card", "ghqr").stream()
                .filter(m -> switch (m) {
                    case "wallet" -> walletEnabled;
                    case "card" -> rails.available("charge", "card", "card");
                    case "ghqr" -> rails.available("charge", "ghqr", null);
                    default -> List.of("mtn", "telecel", "airteltigo").stream()
                            .anyMatch(n -> rails.available("charge", "momo", n));
                })
                .map(m -> {
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
                                                     @RequestHeader(value = "X-Elepay-Channel", required = false) String channel,
                                                     @Valid @RequestBody RailPay request,
                                                     HttpServletRequest httpRequest) {
        String msisdn = null;
        if (request.method().equals("momo")) {
            msisdn = References.msisdn(request.phone());
            if (request.network() == null || !NETWORKS.contains(request.network())) {
                throw ApiException.badRequest("invalid_network", "Choose mtn, telecel or airteltigo");
            }
        } else if (request.phone() != null && !request.phone().isBlank()) {
            msisdn = References.msisdn(request.phone());
        }
        String phone = msisdn;
        int attempts = payAttempts.computeIfAbsent(reference, r -> new AtomicInteger()).incrementAndGet();
        if (attempts > 8) {
            throw new com.elepay.common.ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "checkout_rate_limited", "Too many payment attempts for this checkout");
        }
        String caller = httpRequest.getRemoteAddr() == null ? "unknown" : httpRequest.getRemoteAddr();
        String payChannel = payChannel(channel);
        String coarseIp = "unknown".equals(caller) ? null : caller;
        var started = tx.execute(s -> idempotency.execute("checkout-pay:" + reference, key, request,
                CheckoutService.PaymentResult.class, () -> {
                    checkouts.noteChannel(reference, payChannel);
                    regulatory.rememberCheckout(checkouts.load(reference, false).id(), coarseIp);
                    return checkouts.startRailPayment(reference, request.method(), phone,
                        request.network(), request.email(), publicBaseUrl + "/pay/" + reference);
                }));
        if (started != null && started.attemptId() != null) {
            UUID railTx = checkouts.railTxOf(started.attemptId());
            if (railTx != null) rails.submit(railTx); // no-op unless still 'created'
        }
        return checkouts.status(reference);
    }

    @PostMapping("/v1/checkouts/{reference}/pay/wallet")
    public CheckoutService.PaymentResult payWithWallet(@PathVariable String reference,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       @RequestHeader(value = "X-Elepay-Channel", required = false) String channel,
                                                       HttpServletRequest http) {
        UUID customer = customers.currentCustomer();
        String payChannel = payChannel(channel);
        String coarseIp = http.getRemoteAddr();
        return txRetry.execute(() -> idempotency.execute("checkout-wallet:" + customer, key, Map.of("reference", reference),
                CheckoutService.PaymentResult.class, () -> {
                    checkouts.noteChannel(reference, payChannel);
                    regulatory.rememberCheckout(checkouts.load(reference, false).id(), coarseIp);
                    return checkouts.payWithWallet(reference, customer);
                }));
    }

    /** App clients send MOBILE_APP. Anything else on this HTTP API is the web channel, not a GPS fix. */
    private static String payChannel(String header) {
        return "MOBILE_APP".equals(header) ? "MOBILE_APP" : "WEB";
    }

    // ---- links and merchant codes -----------------------------------------------------------

    @GetMapping("/v1/public/links/{slug}")
    public Map<String, Object> link(@PathVariable String slug) {
        record LinkRow(String title, Long amount, UUID merchant) {}
        var link = db.sql("SELECT title, amount, merchant_id FROM payment_link WHERE slug = :s AND active").param("s", slug)
                .query((rs, i) -> new LinkRow(rs.getString(1), (Long) rs.getObject(2), rs.getObject(3, UUID.class)))
                .optional().orElseThrow(() -> ApiException.notFound("Payment link not found"));
        var merchant = merchants.find(link.merchant()).filter(m -> m.status().equals("active"))
                .orElseThrow(() -> ApiException.notFound("Payment link not found"));
        return Map.of("title", link.title(), "amount", link.amount() == null ? "open" : link.amount(),
                "merchant", merchant.tradingName());
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

    record QrDecodeRequest(String payload, String qr, String qrPayload) {
        public String resolvePayload() {
            if (payload != null && !payload.isBlank()) return payload;
            if (qrPayload != null && !qrPayload.isBlank()) return qrPayload;
            if (qr != null && !qr.isBlank()) return qr;
            return "";
        }
    }

    @PostMapping("/v1/public/qr/decode")
    public EmvcoQr.Decoded decodeQr(@RequestBody(required = false) QrDecodeRequest request) {
        String raw = request != null ? request.resolvePayload() : "";
        if (raw.isBlank()) {
            throw ApiException.badRequest("missing_payload", "QR payload string is required");
        }
        return EmvcoQr.decode(raw);
    }

    private static long require(Long amount) {
        if (amount == null || amount <= 0) throw ApiException.badRequest("amount_required", "Enter an amount");
        return amount;
    }
}
