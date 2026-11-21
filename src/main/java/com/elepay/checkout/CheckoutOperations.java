package com.elepay.checkout;

import com.elepay.identity.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Operations: disputes and settlement runs. */
@RestController
@RequestMapping("/v1/ops")
class CheckoutOperations {
    private final CheckoutService checkouts;

    CheckoutOperations(CheckoutService checkouts) {
        this.checkouts = checkouts;
    }

    record OpenDispute(@NotBlank String checkout, @Min(0) int line, @Positive long amount,
                       @NotBlank @Size(max = 500) String reason, @Size(max = 100) String caseReference) {}

    record Resolve(@NotBlank @Pattern(regexp = "merchant_won|customer_won") String outcome, @Size(max = 1000) String note) {}

    record SettlementRun(Boolean ignoreDelay, UUID merchantId) {}

    @PostMapping("/disputes")
    public CheckoutService.Dispute open(@Valid @RequestBody OpenDispute request) {
        Actor actor = Actor.current();
        actor.requireOps();
        return checkouts.openDispute(actor.subject(), request.checkout(), request.line(), request.amount(),
                request.reason(), request.caseReference());
    }

    @PostMapping("/disputes/{id}/resolve")
    public CheckoutService.Dispute resolve(@PathVariable UUID id, @Valid @RequestBody Resolve request) {
        Actor actor = Actor.current();
        actor.requireOps();
        return checkouts.resolveDispute(actor.subject(), id, request.outcome(), request.note());
    }

    @PostMapping("/settlements/run")
    public List<CheckoutService.SettlementBatch> settle(@RequestBody(required = false) SettlementRun request) {
        Actor.current().requireOps();
        boolean force = request != null && Boolean.TRUE.equals(request.ignoreDelay());
        return checkouts.settleDue(force, request == null ? null : request.merchantId());
    }
}
