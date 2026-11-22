package com.elepay.fx;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.identity.Actor;
import com.elepay.wallet.CustomerDirectory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
class FxApi {
    private final FxService fx;
    private final CustomerDirectory customers;
    private final Idempotency idempotency;
    private final JdbcClient db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    FxApi(FxService fx, CustomerDirectory customers, Idempotency idempotency, JdbcClient db, ObjectMapper json,
          PlatformTransactionManager tm) {
        this.fx = fx;
        this.customers = customers;
        this.idempotency = idempotency;
        this.db = db;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
    }

    record QuoteRequest(@NotBlank String corridor, @Positive long sourceAmount) {}

    record TransferRequest(@NotNull UUID quoteId, @NotNull Map<String, String> recipient) {}

    record CorridorInput(@NotBlank @Pattern(regexp = "inbound|outbound") String direction,
                         @NotBlank @Size(min = 2, max = 2) String sendingCountry,
                         @NotBlank @Size(min = 2, max = 2) String receivingCountry,
                         @NotBlank @Size(min = 3, max = 3) String sourceCurrency,
                         @NotBlank @Size(min = 3, max = 3) String destinationCurrency,
                         @NotBlank String fundingMethod, @NotBlank String deliveryMethod, @NotBlank String partner,
                         @PositiveOrZero long feeFixed, @Min(0) @Max(10000) int feeBps, @Positive long minAmount,
                         @Positive long maxAmount, @Min(30) @Max(3600) int quoteTtlSeconds,
                         @Positive int estDeliveryMinutes, boolean active) {}

    record ReconRequest(@NotBlank String period, @NotNull List<FxService.StatementLine> lines) {}

    record IngestRateRequest(@NotBlank String source, @NotBlank String sourceCurrency,
                             @NotBlank String destinationCurrency, @NotNull @Positive BigDecimal bidRate,
                             @NotNull @Positive BigDecimal askRate, @Positive double weight) {}

    record SynthesizeRateRequest(@NotBlank String sourceCurrency, @NotBlank String destinationCurrency,
                                Integer maxAgeMinutes) {}

    record PricingRequest(@NotBlank String corridor, String customerTier, @Positive long sourceAmount,
                          @NotNull @Positive BigDecimal baseRate) {}

    record DualReconRequest(@NotBlank String period, @NotBlank String sourceCurrency,
                            @NotBlank String destinationCurrency, long statedClosingBalance,
                            @NotNull List<FxDualReconciliationService.DualStatementLine> lines) {}

    @GetMapping("/v1/fx/corridors")
    public List<FxService.Corridor> corridors() {
        Actor.current();
        return fx.corridors(true);
    }

    /** Shows source amount, fee, rate, expiry, estimated delivery and recipient amount before approval. */
    @PostMapping("/v1/fx/quotes")
    public FxService.Quote quote(@Valid @RequestBody QuoteRequest request) {
        UUID customer = customers.currentCustomer();
        return fx.quote(customer, request.corridor(), request.sourceAmount());
    }

    @PostMapping("/v1/fx/transfers")
    public FxService.Transfer transfer(@RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody TransferRequest request) {
        UUID customer = customers.currentCustomer();
        FxService.Transfer created = tx.execute(s -> idempotency.execute("fx-transfer:" + customer, key, request,
                FxService.Transfer.class, () -> fx.execute(customer, request.quoteId(), request.recipient())));
        fx.submit(created.id());
        return fx.transfer(created.id());
    }

    @GetMapping("/v1/fx/transfers/{reference}")
    public FxService.Transfer get(@PathVariable String reference) {
        UUID customer = customers.currentCustomer();
        return fx.transfer(fx.transferId(reference, customer).orElseThrow(() -> ApiException.notFound("Transfer not found")));
    }

    // ---- partner entry points (signed) --------------------------------------------------

    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        return headers;
    }

    @PostMapping("/v1/fx/partners/{partner}/callbacks")
    public Map<String, String> callback(@PathVariable String partner, @RequestBody String body, HttpServletRequest request) {
        FxPartner.Notification n;
        try {
            n = fx.partner(partner).verify(headers(request), body);
        } catch (SecurityException e) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid_callback", e.getMessage());
        }
        Boolean fresh = tx.execute(s -> db.sql("""
                INSERT INTO rail_callback (id, rail, dedupe_key, reference, event_type, payload)
                VALUES (:id, :rail, :key, :ref, 'fx', CAST(:payload AS jsonb)) ON CONFLICT (rail, dedupe_key) DO NOTHING""")
                .param("id", UUID.randomUUID()).param("rail", "fx:" + partner).param("key", n.dedupeKey())
                .param("ref", n.reference()).param("payload", body).update() == 1);
        if (!Boolean.TRUE.equals(fresh)) return Map.of("status", "duplicate");
        fx.apply(n.reference(), n.status(), null, n.message());
        return Map.of("status", "accepted");
    }

    @PostMapping("/v1/fx/partners/{partner}/inbound")
    public FxService.Transfer inbound(@PathVariable String partner, @RequestBody String body, HttpServletRequest request) {
        String verified;
        try {
            verified = fx.partner(partner).verifyInbound(headers(request), body);
        } catch (SecurityException e) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid_instruction", e.getMessage());
        }
        var node = json.readTree(verified);
        return fx.inbound(partner, new FxService.Inbound(node.path("partnerReference").asString(),
                node.path("corridor").asString(), node.path("sourceAmount").asLong(), node.path("sourceCurrency").asString(),
                new BigDecimal(node.path("rate").asString()), node.path("destinationAmount").asLong(),
                node.path("destinationCurrency").asString(), node.path("recipientPhone").asString(),
                node.path("senderName").asString(null)));
    }

    // ---- operations -----------------------------------------------------------------------

    @PutMapping("/v1/ops/fx/corridors/{code}")
    public FxService.Corridor upsert(@PathVariable String code, @Valid @RequestBody CorridorInput c) {
        Actor actor = Actor.current();
        actor.requireOps();
        fx.upsertCorridor(new FxService.Corridor(code, c.direction(), c.sendingCountry(), c.receivingCountry(),
                c.sourceCurrency(), c.destinationCurrency(), c.fundingMethod(), c.deliveryMethod(), c.partner(),
                c.feeFixed(), c.feeBps(), c.minAmount(), c.maxAmount(), c.quoteTtlSeconds(), c.estDeliveryMinutes(),
                c.active()), actor.subject());
        return fx.corridor(code);
    }

    @GetMapping("/v1/ops/fx/corridors")
    public List<FxService.Corridor> allCorridors() {
        Actor.current().requireOps();
        return fx.corridors(false);
    }

    @PostMapping("/v1/ops/fx/reconcile/{partner}")
    public List<FxService.ReconLine> reconcile(@PathVariable String partner, @Valid @RequestBody ReconRequest request) {
        Actor actor = Actor.current();
        actor.requireOps();
        return fx.reconcile(partner, request.period(), request.lines(), actor.subject());
    }

    @PostMapping("/v1/ops/fx/recover")
    public Map<String, Integer> recover() {
        Actor.current().requireOps();
        return Map.of("checked", fx.recover());
    }

    // ---- Algorithms 059 - 064 Endpoints ----------------------------------------------------

    @PostMapping("/v1/ops/fx/rates/sources")
    public FxRateService.RateSource ingestRate(@Valid @RequestBody IngestRateRequest req) {
        Actor.current().requireOps();
        return fx.ingestRate(req.source(), req.sourceCurrency(), req.destinationCurrency(),
                req.bidRate(), req.askRate(), req.weight());
    }

    @PostMapping("/v1/ops/fx/rates/synthesize")
    public FxRateService.BlendedRate synthesizeRate(@Valid @RequestBody SynthesizeRateRequest req) {
        Actor.current().requireOps();
        java.time.Duration age = req.maxAgeMinutes() != null ? java.time.Duration.ofMinutes(req.maxAgeMinutes()) : null;
        return fx.synthesizeRate(req.sourceCurrency(), req.destinationCurrency(), age);
    }

    @GetMapping("/v1/fx/rates/latest")
    public FxRateService.BlendedRate latestRate(@RequestParam String from, @RequestParam String to) {
        Actor.current();
        return fx.latestRate(from, to);
    }

    @PostMapping("/v1/fx/pricing")
    public FxPricingService.PricingResult price(@Valid @RequestBody PricingRequest req) {
        Actor.current();
        return fx.price(req.corridor(), req.customerTier(), req.sourceAmount(), req.baseRate());
    }

    @GetMapping("/v1/fx/corridors/{code}/routes")
    public List<FxRoutingService.PartnerRoute> routes(@PathVariable String code,
                                                      @RequestParam(defaultValue = "10000") long amount) {
        Actor.current();
        return fx.rankRoutes(code, amount);
    }

    @GetMapping("/v1/fx/corridors/{code}/delivery-estimate")
    public FxDeliveryEstimator.DeliveryEstimate deliveryEstimate(@PathVariable String code,
                                                                 @RequestParam String deliveryMethod,
                                                                 @RequestParam(required = false) String network) {
        Actor.current();
        return fx.estimateDelivery(code, deliveryMethod, network, java.time.OffsetDateTime.now());
    }

    @GetMapping("/v1/ops/fx/exposure/{currency}")
    public FxExposureService.ExposureStatus exposure(@PathVariable String currency) {
        Actor.current().requireOps();
        return fx.checkExposure(currency);
    }

    @PostMapping("/v1/ops/fx/reconcile-dual/{partner}")
    public FxDualReconciliationService.DualReconReport reconcileDual(@PathVariable String partner,
                                                                     @Valid @RequestBody DualReconRequest req) {
        Actor actor = Actor.current();
        actor.requireOps();
        return fx.reconcileDual(partner, req.period(), req.sourceCurrency(), req.destinationCurrency(),
                req.statedClosingBalance(), req.lines(), actor.subject());
    }
}
