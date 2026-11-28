package com.elepay.rails;

import com.elepay.common.ApiException;
import com.elepay.identity.Actor;
import com.elepay.security.DualControl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
            String currency,
            String reason
    ) {
        public RegisterOperatingAccountRequest(String bankCode, String accountNumber, String accountName, String currency) {
            this(bankCode, accountNumber, accountName, currency, null);
        }
    }

    public record SweepRequest(
            @NotNull UUID operatingAccountId,
            @Positive long amountPesewas,
            String currency,
            String reason
    ) {}

    public record ReconcileMismatchRequest(
            @NotBlank String resolution,
            @NotBlank String reason
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
        String currency = request.currency() != null && !request.currency().isBlank()
                ? request.currency().trim().toUpperCase(Locale.ROOT) : "GHS";
        PlatformRevenueSweepService.validateOperatingAccountInput(
                request.bankCode(), request.accountNumber(), request.accountName(), currency);

        String emailClaim = PlatformRevenueSweepService.currentJwtClaim("email");
        String uidClaim = PlatformRevenueSweepService.currentJwtClaim("uid");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", "REGISTER_OPERATING_ACCOUNT");
        payload.put("bankCode", request.bankCode().trim());
        payload.put("accountNumber", request.accountNumber().trim());
        payload.put("accountName", request.accountName().trim());
        payload.put("currency", currency);
        payload.put("verifiedBy", actor.subject());
        if (emailClaim != null) payload.put("verifiedByEmail", emailClaim);
        if (uidClaim != null) payload.put("verifiedByUid", uidClaim);

        String reason = request.reason() != null && !request.reason().isBlank()
                ? request.reason()
                : "Register ELEPAY corporate operating bank account";

        dualControl.hold(
                "CUSTODIAN_CONFIG",
                "platform_operating_account",
                request.bankCode().trim() + ":" + request.accountNumber().trim(),
                null,
                payload,
                reason,
                actor
        );
        return sweepService.registerOperatingAccount(
                request.bankCode(),
                request.accountNumber(),
                request.accountName(),
                currency,
                actor.subject(),
                emailClaim,
                null
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
        String currency = request.currency() != null ? request.currency().trim().toUpperCase(Locale.ROOT) : "GHS";
        var dest = sweepService.findOperatingAccount(request.operatingAccountId())
                .filter(a -> "active".equals(a.status()) && currency.equals(a.currency().trim()))
                .orElseThrow(() -> ApiException.notFound("Active operating account not found for currency " + currency));
        String reason = request.reason() != null && !request.reason().isBlank()
                ? request.reason()
                : "ELEPAY earned revenue sweep";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operatingAccountId", dest.id().toString());
        payload.put("bankCode", dest.bankCode());
        payload.put("accountNumber", dest.accountNumber());
        payload.put("accountName", dest.accountName());
        payload.put("amountPesewas", request.amountPesewas());
        payload.put("currency", currency);
        payload.put("idempotencyKey", key);
        String emailClaim = PlatformRevenueSweepService.currentJwtClaim("email");
        String uidClaim = PlatformRevenueSweepService.currentJwtClaim("uid");
        if (emailClaim != null) payload.put("proposedByEmail", emailClaim);
        if (uidClaim != null) payload.put("proposedByUid", uidClaim);

        dualControl.hold(
                "REVENUE_SWEEP",
                "platform_revenue",
                currency,
                null,
                payload,
                reason,
                actor
        );
        return sweepService.executeSweep(
                dest.id(),
                request.amountPesewas(),
                currency,
                key,
                actor.subject(),
                actor.subject(),
                null
        );
    }

    @PostMapping("/{id}/reconcile-mismatch")
    public PlatformRevenueSweepService.RevenueSweep reconcileMismatch(
            @PathVariable UUID id,
            @Valid @RequestBody ReconcileMismatchRequest request
    ) {
        Actor actor = Actor.current();
        actor.requireOpsAdmin();
        return sweepService.reconcileMismatch(id, request.resolution(), actor.subject(), request.reason());
    }
}
