package com.elepay.security;

import com.elepay.identity.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Operations REST Controller for Mandatory Maker-Checker Dual Control (Dead-End Gate 2).
 */
@RestController
@RequestMapping("/v1/ops/maker-checker")
public class MakerCheckerController {
    private final MakerCheckerService service;

    public MakerCheckerController(MakerCheckerService service) {
        this.service = service;
    }

    public record ProposeRequest(
            @NotBlank String actionType,
            @NotBlank String resourceType,
            @NotBlank String resourceId,
            UUID merchantId,
            Map<String, Object> payload,
            String reason
    ) {}

    public record ReviewRequest(String note) {}

    @PostMapping("/proposals")
    public MakerCheckerService.Proposal propose(@Valid @RequestBody ProposeRequest request) {
        Actor actor = Actor.current();
        requireMakerRole(actor, request.actionType());
        return service.propose(
                request.actionType(),
                request.resourceType(),
                request.resourceId(),
                request.merchantId(),
                request.payload(),
                request.reason(),
                actor.subject()
        );
    }

    private static void requireMakerRole(Actor actor, String rawActionType) {
        String actionType = rawActionType == null ? "" : rawActionType.trim().toUpperCase(java.util.Locale.ROOT);
        switch (actionType) {
            case "REVENUE_SWEEP", "LARGE_PAYOUT", "CUSTODIAN_CONFIG", "OPERATING_ACCOUNT_REGISTER",
                    "VELOCITY_CEILING_CHANGE", "FORCE_SETTLEMENT",
                    "DISPUTE_RESOLUTION", "ESCROW_RELEASE",
                    "SETTLEMENT_RULE_CHANGE", "FEE_SCHEDULE_CHANGE" -> actor.requireFinance();
            case "KILL_SWITCH_CHANGE", "MODULE_ENABLE", "MERCHANT_UNFREEZE" -> actor.requireOpsAdmin();
            case "DESTINATION_OPS_VERIFY", "PHONE_CHANGE" -> actor.requireOnboarding();
            default -> actor.requireOpsAdmin();
        }
    }

    @GetMapping("/proposals")
    public List<MakerCheckerService.Proposal> list(@RequestParam(required = false) String status) {
        Actor.current().requireOps();
        return service.listProposals(status);
    }

    @GetMapping("/proposals/{id}")
    public MakerCheckerService.Proposal get(@PathVariable UUID id) {
        Actor.current().requireOps();
        return service.getProposal(id);
    }

    @PostMapping("/proposals/{id}/approve")
    public MakerCheckerService.Proposal approve(
            @PathVariable UUID id,
            @RequestBody(required = false) ReviewRequest request
    ) {
        Actor actor = Actor.current();
        actor.requireOpsAdmin();
        return service.approve(id, actor.subject(), request != null ? request.note() : null);
    }

    @PostMapping("/proposals/{id}/reject")
    public MakerCheckerService.Proposal reject(
            @PathVariable UUID id,
            @RequestBody(required = false) ReviewRequest request
    ) {
        Actor actor = Actor.current();
        actor.requireOpsAdmin();
        return service.reject(id, actor.subject(), request != null ? request.note() : null);
    }
}
