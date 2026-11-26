package com.elepay.cell;

import com.elepay.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operations REST API for cell sharding topology, merchant routing/assignment,
 * and deterministic cell migration orchestration.
 */
@RestController
@RequestMapping("/v1/ops/cells")
public class CellAdminController {

    private static final Set<String> OPS_AUTHORITIES = Set.of(
            "ROLE_ops",
            "ROLE_ops_admin",
            "ROLE_support_agent",
            "ROLE_onboarding_officer",
            "ROLE_finance_ops",
            "ROLE_risk_compliance"
    );

    private final CellRegistryService cellRegistryService;
    private final CellRouter cellRouter;
    private final CellMigrationService migrationService;

    public CellAdminController(
            CellRegistryService cellRegistryService,
            CellRouter cellRouter,
            CellMigrationService migrationService) {
        this.cellRegistryService = cellRegistryService;
        this.cellRouter = cellRouter;
        this.migrationService = migrationService;
    }

    public record RegisterCellRequest(
            @NotBlank String cellId,
            @NotBlank String displayName,
            String jdbcUrl,
            String replicaJdbcUrl,
            Integer weight
    ) {}

    public record AssignMerchantRequest(
            @NotNull UUID merchantId,
            @NotBlank String cellId,
            UUID affinityGroupId,
            boolean pinned
    ) {}

    public record PlanMigrationRequest(
            @NotNull UUID merchantId,
            @NotBlank String targetCellId,
            String reason
    ) {}

    public record RollbackMigrationRequest(String reason) {}

    @GetMapping
    public Map<String, Object> listCells(Authentication authentication) {
        requireOpsActor(authentication);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("cells", cellRegistryService.listCells());
        response.put("ringDistribution", cellRouter.ringDistribution());
        return response;
    }

    @PostMapping
    public CellRecord registerCell(
            @Valid @RequestBody RegisterCellRequest request,
            Authentication authentication) {
        requireOpsActor(authentication);
        int effectiveWeight = request.weight() != null ? request.weight() : 100;
        CellRecord cell = cellRegistryService.registerCell(
                request.cellId(),
                request.displayName(),
                request.jdbcUrl(),
                request.replicaJdbcUrl(),
                effectiveWeight
        );
        cellRouter.rebuildRing();
        return cell;
    }

    @GetMapping("/resolve/{merchantId}")
    public Map<String, Object> resolveCell(
            @PathVariable UUID merchantId,
            Authentication authentication) {
        requireOpsActor(authentication);
        String cellId = cellRouter.resolveCell(merchantId);
        return Map.of("merchantId", merchantId.toString(), "cellId", cellId);
    }

    @PostMapping("/assign")
    public Map<String, Object> assignMerchant(
            @Valid @RequestBody AssignMerchantRequest request,
            Authentication authentication) {
        requireOpsActor(authentication);
        cellRouter.assignMerchant(
                request.merchantId(),
                request.cellId(),
                request.affinityGroupId(),
                request.pinned()
        );
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("merchantId", request.merchantId().toString());
        response.put("cellId", request.cellId().trim());
        response.put("affinityGroupId", request.affinityGroupId() != null ? request.affinityGroupId().toString() : null);
        response.put("pinned", request.pinned());
        return response;
    }

    @PostMapping("/migrations")
    public CellMigrationService.MigrationJob planMigration(
            @Valid @RequestBody PlanMigrationRequest request,
            Authentication authentication) {
        String actor = requireOpsActor(authentication);
        return migrationService.planMigration(
                request.merchantId(),
                request.targetCellId(),
                actor,
                request.reason()
        );
    }

    @PostMapping("/migrations/{id}/advance")
    public CellMigrationService.MigrationJob advanceMigration(
            @PathVariable UUID id,
            Authentication authentication) {
        String actor = requireOpsActor(authentication);
        return migrationService.advanceState(id, actor);
    }

    @PostMapping("/migrations/{id}/rollback")
    public CellMigrationService.MigrationJob rollbackMigration(
            @PathVariable UUID id,
            @RequestBody(required = false) RollbackMigrationRequest request,
            Authentication authentication) {
        String actor = requireOpsActor(authentication);
        String reason = request != null ? request.reason() : null;
        return migrationService.rollbackMigration(id, actor, reason);
    }

    private static String requireOpsActor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw ApiException.unauthorized("Authentication required");
        }
        boolean hasOpsRole = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(OPS_AUTHORITIES::contains);
        if (!hasOpsRole) {
            throw ApiException.forbidden("Operations role required");
        }
        String name = authentication.getName();
        return (name == null || name.isBlank()) ? "ops" : name;
    }
}
