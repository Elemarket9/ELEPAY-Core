package com.elepay.checkout;

import com.elepay.rails.RailTransactions;
import com.elepay.security.DualControl;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.elepay.identity.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Operations: disputes and settlement runs. */
@RestController
@RequestMapping("/v1/ops")
class CheckoutOperations {
    private final CheckoutService checkouts;
    private final RailTransactions rails;
    private final DualControl dualControl;

    CheckoutOperations(CheckoutService checkouts, RailTransactions rails, DualControl dualControl) {
        this.checkouts = checkouts;
        this.rails = rails;
        this.dualControl = dualControl;
    }

    record OpenDispute(@NotBlank String checkout, @JsonAlias("allocationLine") @Min(0) int line, @Positive long amount,
                       @NotBlank @Size(max = 500) String reason, @Size(max = 100) String caseReference) {}

    record Resolve(@NotBlank @Pattern(regexp = "merchant_won|customer_won") String outcome, @Size(max = 1000) String note) {}

    record SettlementRun(Boolean ignoreDelay, UUID merchantId) {}

    @PostMapping("/disputes")
    public CheckoutService.Dispute open(@Valid @RequestBody OpenDispute request) {
        Actor actor = Actor.current();
        actor.requireSupport();
        return checkouts.openDispute(actor.subject(), request.checkout(), request.line(), request.amount(),
                request.reason(), request.caseReference());
    }

    @PostMapping("/disputes/{id}/resolve")
    public CheckoutService.Dispute resolve(@PathVariable UUID id, @Valid @RequestBody Resolve request) {
        Actor actor = Actor.current();
        actor.requireFinance();
        Map<String, Object> payload = new HashMap<>();
        payload.put("disputeId", id.toString());
        payload.put("outcome", request.outcome());
        if (request.note() != null) payload.put("note", request.note()); // never persist the literal "null"
        dualControl.hold("DISPUTE_RESOLUTION", "dispute", id.toString(), null, payload, request.note(), actor);
        var dispute = checkouts.resolveDispute(actor.subject(), id, request.outcome(), request.note());
        // A customer-won dispute paid by MoMo or card goes back through the original rail, only
        // after the resolution commits so the rail intent is durable before the network call.
        checkouts.disputeRefundRailTx(id).ifPresent(tx -> AfterCommit.run(() -> rails.submit(tx)));
        return dispute;
    }

    @PostMapping("/settlements/run")
    public List<CheckoutService.SettlementBatch> settle(@RequestBody(required = false) SettlementRun request) {
        Actor actor = Actor.current();
        actor.requireFinance();
        boolean force = request != null && Boolean.TRUE.equals(request.ignoreDelay());
        UUID merchantId = request == null ? null : request.merchantId();
        if (force) {
            // Releasing funds inside the settlement delay window is a dual-control action.
            Map<String, Object> payload = new HashMap<>();
            if (merchantId != null) payload.put("merchantId", merchantId.toString());
            dualControl.hold("FORCE_SETTLEMENT", "settlement", merchantId == null ? "all" : merchantId.toString(),
                    merchantId, payload, "force settlement", actor);
        }
        return checkouts.settleDue(force, merchantId);
    }
}
