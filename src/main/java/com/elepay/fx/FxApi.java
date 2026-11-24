package com.elepay.fx;

import com.elepay.common.ApiException;
import com.elepay.identity.Actor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
class FxApi {
    private final FxService fx;
    private final TransactionTemplate tx;

    private final com.elepay.rails.RailTransactions rails;

    FxApi(FxService fx,
          PlatformTransactionManager tm, com.elepay.rails.RailTransactions rails) {
        this.rails = rails;
        this.fx = fx;
        this.tx = new TransactionTemplate(tm);
    }

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

    // ---- partner entry points (signed) --------------------------------------------------

    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        return headers;
    }

    @PostMapping("/v1/fx/partners/{partner}/callbacks")
    public Map<String, String> callback(@PathVariable String partner, @RequestBody String body, HttpServletRequest request) {
        String ts = request.getHeader("X-Callback-Timestamp");
        String nonce = request.getHeader("X-Callback-Nonce");
        if (ts == null || nonce == null || ts.isBlank() || nonce.isBlank()) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid_callback",
                    "Partner callbacks must include a timestamp and nonce");
        }
        try {
            long epochSec = ts.trim().matches("^\\d+$")
                    ? Long.parseLong(ts.trim())
                    : java.time.Instant.parse(ts.trim()).getEpochSecond();
            long skew = Math.abs(java.time.Instant.now().getEpochSecond() - epochSec);
            if (skew > 300) {
                throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "stale_callback",
                        "Partner callback timestamp is outside the 5-minute replay window");
            }
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid_callback",
                    "Partner callback timestamp must be Unix epoch seconds or ISO-8601 instant");
        }
        FxPartner.Notification n;
        try {
            n = fx.partner(partner).verify(headers(request), body);
        } catch (SecurityException e) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid_callback", e.getMessage());
        }
        Boolean fresh = tx.execute(s -> rails.recordCallbackOnce("fx:" + partner, n.dedupeKey(), n.reference(), "fx", body));
        if (!Boolean.TRUE.equals(fresh)) return Map.of("status", "duplicate");
        fx.apply(n.reference(), n.status(), null, n.message());
        return Map.of("status", "accepted");
    }

    // ---- operations -----------------------------------------------------------------------

    @PutMapping("/v1/ops/fx/corridors/{code}")
    public FxService.Corridor upsert(@PathVariable String code, @Valid @RequestBody CorridorInput c) {
        Actor actor = Actor.current();
        actor.requireFinance();
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
        actor.requireFinance();
        return fx.reconcile(partner, request.period(), request.lines(), actor.subject());
    }

    @PostMapping("/v1/ops/fx/recover")
    public Map<String, Integer> recover() {
        Actor.current().requireFinance();
        return Map.of("checked", fx.recover());
    }

    // ---- Algorithms 059 - 064 Endpoints ----------------------------------------------------

    @PostMapping("/v1/ops/fx/rates/sources")
    public FxRateService.RateSource ingestRate(@Valid @RequestBody IngestRateRequest req) {
        Actor.current().requireFinance();
        return fx.ingestRate(req.source(), req.sourceCurrency(), req.destinationCurrency(),
                req.bidRate(), req.askRate(), req.weight());
    }

    @PostMapping("/v1/ops/fx/rates/synthesize")
    public FxRateService.BlendedRate synthesizeRate(@Valid @RequestBody SynthesizeRateRequest req) {
        Actor.current().requireFinance();
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
        actor.requireFinance();
        return fx.reconcileDual(partner, req.period(), req.sourceCurrency(), req.destinationCurrency(),
                req.statedClosingBalance(), req.lines(), actor.subject());
    }
}
