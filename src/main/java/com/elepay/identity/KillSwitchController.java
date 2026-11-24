package com.elepay.identity;

import com.elepay.common.ApiException;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

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
    private final JdbcClient db;
    private final ObjectMapper json;
    private final boolean dualControl;

    public KillSwitchController(KillSwitchService killSwitchService, ApiKeys apiKeys, JdbcClient db,
                                @Value("${elepay.security.dual-control.enforced:true}") boolean dualControl) {
        this(killSwitchService, apiKeys, db, new ObjectMapper(), dualControl);
    }

    @Autowired
    public KillSwitchController(KillSwitchService killSwitchService, ApiKeys apiKeys, JdbcClient db,
                                ObjectMapper json,
                                @Value("${elepay.security.dual-control.enforced:true}") boolean dualControl) {
        this.killSwitchService = killSwitchService;
        this.apiKeys = apiKeys;
        this.db = db;
        this.json = json != null ? json : new ObjectMapper();
        this.dualControl = dualControl;
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
            actor.requireOpsAdmin();
            String freezer = killSwitchService.globalFrozenBy();
            if (dualControl) {
                propose("KILL_SWITCH_CHANGE", "kill_switch", "global", null,
                        Map.of("target", "global", "frozen", false), reason, actor.subject());
            }
            if (freezer != null && freezer.equals(actor.subject())) {
                throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                        "maker_checker_self_approval",
                        "Dual control violation: the operator who froze payouts cannot unfreeze them");
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
        if (!freeze) {
            actor.requireOpsAdmin();
            if (dualControl) {
                propose("MERCHANT_UNFREEZE", "merchant", merchantId.toString(), merchantId,
                        Map.of("merchantId", merchantId.toString(), "frozen", false), reason, actor.subject());
            }
            String freezer = killSwitchService.merchantFrozenBy(merchantId);
            if (freezer != null && freezer.equals(actor.subject())) {
                throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                        "maker_checker_self_approval",
                        "Dual control violation: the operator who froze this merchant cannot unfreeze it");
            }
        }
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
        if (enabled && dualControl) {
            actor.requireOpsAdmin();
            propose("MODULE_ENABLE", "module", moduleKey, null,
                    Map.of("target", "module", "module", moduleKey, "frozen", false),
                    reason, actor.subject());
        }
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

    private void propose(String action, String resourceType, String resourceId, UUID merchantId,
                         Map<String, Object> payload, String reason, String proposer) {
        UUID id = UUID.randomUUID();
        String payloadJson = json.writeValueAsString(payload != null ? payload : Map.of());
        db.sql("""
                INSERT INTO maker_checker_proposal (
                    id, action_type, resource_type, resource_id, merchant_id,
                    payload, reason, status, proposed_by, proposed_at, expires_at)
                VALUES (:id, :action, :rt, :rid, :mid,
                    CAST(:payload AS jsonb), :reason, 'PENDING', :by, now(), now() + interval '24 hours')""")
                .param("id", id).param("action", action).param("rt", resourceType).param("rid", resourceId)
                .param("mid", merchantId).param("payload", payloadJson).param("reason", reason).param("by", proposer)
                .update();
        throw new ApiException(org.springframework.http.HttpStatus.ACCEPTED, "pending_approval",
                "A different operations administrator must approve proposal " + id);
    }
}
