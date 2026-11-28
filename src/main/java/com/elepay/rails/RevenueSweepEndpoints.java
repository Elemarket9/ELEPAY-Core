package com.elepay.rails;

import com.elepay.common.ApiException;
import com.elepay.identity.Actor;
import com.elepay.security.DualControl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Finance/Ops endpoints for previewing, proposing (dual-controlled), and auditing ELEPAY
 * platform earned-revenue sweeps from the Custodian Trust Account to ELEPAY's operating bank account.
 */
@RestController
@RequestMapping("/v1/ops/revenue-sweeps")
public class RevenueSweepEndpoints {

    private final PlatformRevenueSweepService sweepService;
    private final DualControl dualControl;

    RevenueSweepEndpoints(PlatformRevenueSweepService sweepService, DualControl dualControl) {
        this.sweepService = sweepService;
        this.dualControl = dualControl;
    }

    public record RegisterOperatingAccountRequest(
            @NotBlank String bankCode,
            @NotBlank String accountNumber,
            @NotBlank String accountName,
            String currency
    ) {}

    public record SweepRequest(
            @NotNull UUID operatingAccountId,
            @Positive long amountPesewas,
            String currency,
            String reason
    ) {}

    @GetMapping("/preview")
    public PlatformRevenueSweepService.SweepPreview preview(
            @RequestParam(defaultValue = "GHS") String currency
    ) {
        Actor.current().requireOps();
        return sweepService.previewSweepable(currency);
    }

    @GetMapping
    public List<PlatformRevenueSweepService.RevenueSweep> list(
            @RequestParam(defaultValue = "50") int limit
    ) {
        Actor.current().requireOps();
        return sweepService.listSweeps(limit);
    }

    @GetMapping("/operating-accounts")
    public List<PlatformRevenueSweepService.OperatingAccount> listOperatingAccounts(
            @RequestParam(defaultValue = "GHS") String currency
    ) {
        Actor.current().requireOps();
        return sweepService.listOperatingAccounts(currency);
    }

    @PostMapping("/operating-accounts")
    public PlatformRevenueSweepService.OperatingAccount registerOperatingAccount(
            @Valid @RequestBody RegisterOperatingAccountRequest request
    ) {
        Actor actor = Actor.current();
        actor.requireFinance();
        return sweepService.registerOperatingAccount(
                request.bankCode(),
                request.accountNumber(),
                request.accountName(),
                request.currency(),
                actor.subject()
        );
    }

    @PostMapping
    public PlatformRevenueSweepService.RevenueSweep sweep(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody SweepRequest request
    ) {
        Actor actor = Actor.current();
        actor.requireFinance();
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("missing_idempotency_key", "Idempotency-Key header is required");
        }
        String currency = request.currency() != null ? request.currency() : "GHS";
        String reason = request.reason() != null && !request.reason().isBlank()
                ? request.reason()
                : "ELEPAY earned revenue sweep";
        dualControl.hold(
                "REVENUE_SWEEP",
                "platform_revenue",
                currency,
                null,
                Map.of(
                        "operatingAccountId", request.operatingAccountId().toString(),
                        "amountPesewas", request.amountPesewas(),
                        "currency", currency,
                        "idempotencyKey", key
                ),
                reason,
                actor
        );
        return sweepService.executeSweep(
                request.operatingAccountId(),
                request.amountPesewas(),
                currency,
                key,
                actor.subject(),
                actor.subject(),
                null
        );
    }
}
