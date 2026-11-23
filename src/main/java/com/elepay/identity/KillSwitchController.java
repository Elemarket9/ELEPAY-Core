package com.elepay.identity;

import com.elepay.common.ApiException;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/**
 * Stage 1 Kill-Switch Operations & Admin Endpoints.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code POST /admin/kill-switch/global} and {@code POST /v1/ops/kill-switch/global}</li>
 *   <li>{@code POST /admin/kill-switch/merchants/{merchantId}} and {@code POST /v1/ops/kill-switch/merchants/{merchantId}}</li>
 *   <li>{@code POST /admin/kill-switch/modules/{moduleKey}} and {@code POST /v1/ops/kill-switch/modules/{moduleKey}}</li>
 *   <li>{@code POST /admin/kill-switch/api-keys/{keyId}} and {@code POST /v1/ops/kill-switch/api-keys/{keyId}}</li>
 *   <li>{@code GET /admin/kill-switch/status} and {@code GET /v1/ops/kill-switches}</li>
 * </ul>
 */
@RestController
public class KillSwitchController {
    private final KillSwitchService killSwitchService;
    private final ApiKeys apiKeys;

    public KillSwitchController(KillSwitchService killSwitchService, ApiKeys apiKeys) {
        this.killSwitchService = killSwitchService;
        this.apiKeys = apiKeys;
    }

    public record GlobalKillSwitchRequest(
            Boolean active,
            Boolean frozen,
            Boolean enabled,
            String reason,
            String proposedBy,
            String approvedBy
    ) {
        public boolean isFreezeAction() {
            if (frozen != null) return frozen;
            if (active != null) return active;
            if (enabled != null) return enabled;
            return true;
        }
    }

    public record MerchantKillSwitchRequest(
            Boolean active,
            Boolean frozen,
            Boolean enabled,
            String reason
    ) {
        public boolean isFreezeAction() {
            if (frozen != null) return frozen;
            if (active != null) return active;
            if (enabled != null) return enabled;
            return true;
        }
    }

    public record ModuleKillSwitchRequest(
            Boolean enabled,
            Boolean active,
            String reason
    ) {
        public boolean isEnabledAction() {
            if (enabled != null) return enabled;
            if (active != null) return active;
            return false;
        }
    }

    @GetMapping({"/admin/kill-switch/status", "/v1/ops/kill-switches", "/v1/ops/kill-switch/status"})
    public KillSwitchService.KillSwitchStatus status() {
        Actor.current().requireOps();
        return killSwitchService.status();
    }

    @PostMapping({"/admin/kill-switch/global", "/v1/ops/kill-switch/global", "/v1/ops/kill-switches/payouts/freeze"})
    public Map<String, Object> toggleGlobal(@RequestBody(required = false) GlobalKillSwitchRequest request) {
        Actor actor = Actor.current();
        actor.requireOps();
        boolean freeze = request == null || request.isFreezeAction();
        String reason = (request != null && request.reason() != null)
                ? request.reason()
                : (freeze ? "Emergency global payout freeze" : "Global payout unfreeze");

        if (!freeze) {
            // Unfreezing requires Maker-Checker dual control (proposed_by != approved_by) when proposedBy is supplied
            String proposer = request != null ? request.proposedBy() : null;
            String approver = (request != null && request.approvedBy() != null) ? request.approvedBy() : actor.subject();
            if (proposer != null && proposer.equals(approver)) {
                throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                        "maker_checker_self_approval",
                        "Dual control violation: Maker (" + proposer + ") cannot unfreeze global kill switch alone");
            }
        }

        killSwitchService.setGlobalFrozen(freeze, reason, actor.subject());
        return Map.of(
                "globalFrozen", killSwitchService.isGlobalFrozen(),
                "status", freeze ? "FROZEN" : "ACTIVE",
                "reason", reason
        );
    }

    @PostMapping({"/admin/kill-switch/merchants/{merchantId}", "/v1/ops/kill-switch/merchants/{merchantId}"})
    public Map<String, Object> toggleMerchant(
            @PathVariable UUID merchantId,
            @RequestBody(required = false) MerchantKillSwitchRequest request
    ) {
        Actor actor = Actor.current();
        actor.requireOps();
        boolean freeze = request == null || request.isFreezeAction();
        String reason = (request != null && request.reason() != null)
                ? request.reason()
                : (freeze ? "Merchant emergency payout freeze" : "Merchant payout unfreeze");
        killSwitchService.setMerchantFrozen(merchantId, freeze, reason, actor.subject());
        return Map.of(
                "merchantId", merchantId,
                "payoutsFrozen", killSwitchService.isMerchantFrozen(merchantId),
                "status", freeze ? "FROZEN" : "ACTIVE",
                "reason", reason
        );
    }

    @PostMapping({"/admin/kill-switch/modules/{moduleKey}", "/v1/ops/kill-switch/modules/{moduleKey}"})
    public Map<String, Object> toggleModule(
            @PathVariable String moduleKey,
            @RequestBody(required = false) ModuleKillSwitchRequest request
    ) {
        Actor actor = Actor.current();
        actor.requireOps();
        boolean enabled = request != null && request.isEnabledAction();
        String reason = (request != null && request.reason() != null)
                ? request.reason()
                : (enabled ? "Module enabled" : "Module disabled by kill switch");
        killSwitchService.setModuleEnabled(moduleKey, enabled, reason, actor.subject());
        return Map.of(
                "module", moduleKey,
                "enabled", killSwitchService.isModuleEnabled(moduleKey),
                "reason", reason
        );
    }

    @PostMapping({"/admin/kill-switch/api-keys/{keyId}", "/v1/ops/kill-switch/api-keys/{keyId}"})
    public Map<String, Object> emergencyRevokeApiKey(@PathVariable UUID keyId) {
        Actor actor = Actor.current();
        actor.requireOps();
        boolean revoked = apiKeys.emergencyRevokeById(keyId, actor.subject());
        return Map.of(
                "keyId", keyId,
                "revoked", revoked,
                "status", revoked ? "REVOKED" : "NOT_FOUND"
        );
    }
}
