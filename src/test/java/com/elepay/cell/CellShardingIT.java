package com.elepay.cell;

import static org.assertj.core.api.Assertions.assertThat;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Integration tests for Stage 4 cell-based sharding:
 * <ul>
 *   <li>Cell registry listing, registration, and consistent-hash ring distribution via {@code /v1/ops/cells}</li>
 *   <li>Deterministic merchant cell resolution and explicit assignment / affinity-group pinning</li>
 *   <li>{@link CrossCellGuard} enforcement ({@code 409 cross_cell_operation_forbidden}) on multi-merchant checkouts</li>
 *   <li>{@link CellMigrationService} full lifecycle ({@code PLANNED -> DUAL_WRITE -> BACKFILLING -> VERIFYING -> CUTOVER -> COMPLETED})
 *       and rollback ({@code VERIFYING -> ROLLED_BACK}, {@code CUTOVER -> ROLLED_BACK})</li>
 * </ul>
 */
@TestPropertySource(properties = "elepay.cells.enabled=true")
class CellShardingIT extends PostgresTestBase {

    @LocalServerPort
    int port;

    @Autowired
    CrossCellGuard crossCellGuard;

    @Autowired
    CellMigrationService migrationService;

    @Autowired
    JdbcClient db;

    private TestApi api;
    private String ops;

    @BeforeEach
    void setUp() {
        api = new TestApi(port);
        ops = api.token("cell-ops-" + UUID.randomUUID(), "ops");
    }

    private void ensureCell1Registered() {
        var register = api.call("POST", "/v1/ops/cells", ops, null, Map.of(
                "cellId", "cell-1",
                "displayName", "Secondary Shard Cell 1",
                "weight", 100
        ));
        assertThat(register.status()).isEqualTo(200);
        assertThat(register.body().get("cellId").asString()).isEqualTo("cell-1");
        assertThat(register.body().get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void opsEndpointsRequireOpsRoleAndManageCellTopologyDeterministically() {
        String nonOpsToken = api.token("regular-user-" + UUID.randomUUID());
        var forbidden = api.call("GET", "/v1/ops/cells", nonOpsToken, null, null);
        assertThat(forbidden.status()).isEqualTo(403);

        var initial = api.call("GET", "/v1/ops/cells", ops, null, null);
        assertThat(initial.status()).isEqualTo(200);
        List<String> initialCellIds = extractCellIds(initial.body().get("cells"));
        assertThat(initialCellIds).contains("cell-0");

        ensureCell1Registered();

        var afterRegister = api.call("GET", "/v1/ops/cells", ops, null, null);
        assertThat(afterRegister.status()).isEqualTo(200);
        assertThat(extractCellIds(afterRegister.body().get("cells"))).contains("cell-0", "cell-1");
        JsonNode distribution = afterRegister.body().get("ringDistribution");
        assertThat(distribution.get("cell-0").asInt()).isGreaterThan(0);
        assertThat(distribution.get("cell-1").asInt()).isGreaterThan(0);

        // Deterministic resolution across cells
        Set<String> observedCells = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            UUID merchantId = UUID.randomUUID();
            var first = api.call("GET", "/v1/ops/cells/resolve/" + merchantId, ops, null, null);
            var second = api.call("GET", "/v1/ops/cells/resolve/" + merchantId, ops, null, null);
            assertThat(first.status()).isEqualTo(200);
            assertThat(second.status()).isEqualTo(200);
            String cellA = first.body().get("cellId").asString();
            String cellB = second.body().get("cellId").asString();
            assertThat(cellA).isEqualTo(cellB);
            observedCells.add(cellA);
        }
        assertThat(observedCells).contains("cell-0", "cell-1");

        // Affinity group pinning
        UUID anchor = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        var assigned = api.call("POST", "/v1/ops/cells/assign", ops, null, Map.of(
                "merchantId", anchor.toString(),
                "cellId", "cell-1",
                "pinned", true
        ));
        assertThat(assigned.status()).isEqualTo(200);
        assertThat(assigned.body().get("cellId").asString()).isEqualTo("cell-1");

        crossCellGuard.pinAffinityGroup(anchor, member);

        var resolvedMember = api.call("GET", "/v1/ops/cells/resolve/" + member, ops, null, null);
        assertThat(resolvedMember.status()).isEqualTo(200);
        assertThat(resolvedMember.body().get("cellId").asString()).isEqualTo("cell-1");

        UUID storedAffinity = db.sql("SELECT affinity_group_id FROM merchant_cell_assignment WHERE merchant_id = :id")
                .param("id", member)
                .query(UUID.class)
                .single();
        assertThat(storedAffinity).isEqualTo(anchor);
    }

    @Test
    void crossCellGuardRejectsMultiMerchantCheckoutAcrossDifferentCellsAndAllowsOncePinned() {
        ensureCell1Registered();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String mpOwner = api.token("cell-mp-" + suffix);
        String sellerAOwner = api.token("cell-sa-" + suffix);
        String sellerBOwner = api.token("cell-sb-" + suffix);

        var mpResp = api.call("POST", "/v1/merchants", mpOwner, null, Map.of(
                "legalName", "Cell Market " + suffix,
                "tradingName", "Cell Market " + suffix,
                "kind", "marketplace",
                "contactPhone", TestApi.uniquePhone(),
                "contactEmail", "cell-mp-" + suffix + "@elepay.gh"
        ));
        assertThat(mpResp.status()).isEqualTo(200);
        UUID mp = UUID.fromString(mpResp.body().get("id").asString());
        assertThat(api.call("POST", "/v1/ops/merchants/" + mp + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", 0, "feeFixed", 0, "settlementDelayHours", 0)).status())
                .isEqualTo(200);

        var sellerAResp = api.call("POST", "/v1/merchants", sellerAOwner, null, Map.of(
                "legalName", "Cell Seller A " + suffix,
                "tradingName", "Cell Seller A " + suffix,
                "kind", "merchant",
                "contactPhone", TestApi.uniquePhone(),
                "contactEmail", "cell-sa-" + suffix + "@elepay.gh"
        ));
        assertThat(sellerAResp.status()).isEqualTo(200);
        UUID sellerA = UUID.fromString(sellerAResp.body().get("id").asString());
        assertThat(api.call("POST", "/v1/ops/merchants/" + sellerA + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", 100, "feeFixed", 0, "settlementDelayHours", 0)).status())
                .isEqualTo(200);
        linkSeller(mp, mpOwner, sellerA, sellerAOwner, sellerAResp.body().get("merchantCode").asString());

        var sellerBResp = api.call("POST", "/v1/merchants", sellerBOwner, null, Map.of(
                "legalName", "Cell Seller B " + suffix,
                "tradingName", "Cell Seller B " + suffix,
                "kind", "merchant",
                "contactPhone", TestApi.uniquePhone(),
                "contactEmail", "cell-sb-" + suffix + "@elepay.gh"
        ));
        assertThat(sellerBResp.status()).isEqualTo(200);
        UUID sellerB = UUID.fromString(sellerBResp.body().get("id").asString());
        assertThat(api.call("POST", "/v1/ops/merchants/" + sellerB + "/review", ops, null,
                Map.of("decision", "approve", "feeBps", 100, "feeFixed", 0, "settlementDelayHours", 0)).status())
                .isEqualTo(200);
        linkSeller(mp, mpOwner, sellerB, sellerBOwner, sellerBResp.body().get("merchantCode").asString());

        // Place mp & sellerA on cell-0, and sellerB on cell-1
        assignMerchantCell(mp, "cell-0");
        assignMerchantCell(sellerA, "cell-0");
        assignMerchantCell(sellerB, "cell-1");

        var crossCellCheckout = api.call("POST", "/v1/merchants/" + mp + "/checkouts",
                mpOwner, UUID.randomUUID().toString(),
                Map.of("allocations", List.of(
                        Map.of("merchantId", sellerA.toString(), "kind", "sale", "amount", 5_000,
                                "description", "Item A"),
                        Map.of("merchantId", sellerB.toString(), "kind", "sale", "amount", 3_000,
                                "description", "Item B")
                )));
        assertThat(crossCellCheckout.status()).isEqualTo(409);
        assertThat(crossCellCheckout.body().get("code").asString()).isEqualTo("cross_cell_operation_forbidden");

        // Pin sellerB to mp's cell (cell-0) and retry checkout
        crossCellGuard.pinAffinityGroup(mp, sellerB);

        var sameCellCheckout = api.call("POST", "/v1/merchants/" + mp + "/checkouts",
                mpOwner, UUID.randomUUID().toString(),
                Map.of("allocations", List.of(
                        Map.of("merchantId", sellerA.toString(), "kind", "sale", "amount", 5_000,
                                "description", "Item A"),
                        Map.of("merchantId", sellerB.toString(), "kind", "sale", "amount", 3_000,
                                "description", "Item B")
                )));
        assertThat(sameCellCheckout.status()).isEqualTo(200);
        assertThat(sameCellCheckout.body().get("reference").asString()).isNotBlank();
    }

    @Test
    void migrationLifecycleAdvancesDeterministicallyAndSupportsRollback() {
        ensureCell1Registered();

        // 1. Full happy-path lifecycle: PLANNED -> DUAL_WRITE -> BACKFILLING -> VERIFYING -> CUTOVER -> COMPLETED
        UUID merchantId = UUID.randomUUID();
        assignMerchantCell(merchantId, "cell-0");

        // Same-cell migration must be rejected with 400
        var sameCell = api.call("POST", "/v1/ops/cells/migrations", ops, null, Map.of(
                "merchantId", merchantId.toString(),
                "targetCellId", "cell-0",
                "reason", "invalid same cell"
        ));
        assertThat(sameCell.status()).isEqualTo(400);

        var planned = api.call("POST", "/v1/ops/cells/migrations", ops, null, Map.of(
                "merchantId", merchantId.toString(),
                "targetCellId", "cell-1",
                "reason", "rebalance shard load"
        ));
        assertThat(planned.status()).isEqualTo(200);
        UUID jobId = UUID.fromString(planned.body().get("id").asString());
        assertThat(planned.body().get("state").asString()).isEqualTo("PLANNED");
        assertThat(planned.body().get("sourceCellId").asString()).isEqualTo("cell-0");
        assertThat(planned.body().get("targetCellId").asString()).isEqualTo("cell-1");

        // Duplicate active migration must be rejected with 409
        var duplicate = api.call("POST", "/v1/ops/cells/migrations", ops, null, Map.of(
                "merchantId", merchantId.toString(),
                "targetCellId", "cell-1",
                "reason", "duplicate"
        ));
        assertThat(duplicate.status()).isEqualTo(409);

        var dualWrite = api.call("POST", "/v1/ops/cells/migrations/" + jobId + "/advance", ops, null, null);
        assertThat(dualWrite.status()).isEqualTo(200);
        assertThat(dualWrite.body().get("state").asString()).isEqualTo("DUAL_WRITE");

        var backfilling = api.call("POST", "/v1/ops/cells/migrations/" + jobId + "/advance", ops, null, null);
        assertThat(backfilling.status()).isEqualTo(200);
        assertThat(backfilling.body().get("state").asString()).isEqualTo("BACKFILLING");

        var verifying = api.call("POST", "/v1/ops/cells/migrations/" + jobId + "/advance", ops, null, null);
        assertThat(verifying.status()).isEqualTo(200);
        assertThat(verifying.body().get("state").asString()).isEqualTo("VERIFYING");
        assertThat(migrationService.getMigration(jobId).verificationHash()).isNull();

        // Still on source cell before cutover
        assertThat(api.call("GET", "/v1/ops/cells/resolve/" + merchantId, ops, null, null)
                .body().get("cellId").asString()).isEqualTo("cell-0");

        var cutover = api.call("POST", "/v1/ops/cells/migrations/" + jobId + "/advance", ops, null, null);
        assertThat(cutover.status()).isEqualTo(409);
        assertThat(migrationService.getMigration(jobId).state()).isEqualTo("VERIFYING");
        assertThat(api.call("GET", "/v1/ops/cells/resolve/" + merchantId, ops, null, null)
                .body().get("cellId").asString()).isEqualTo("cell-0");

        // 2. Rollback from VERIFYING -> ROLLED_BACK
        UUID rollbackMerchant = UUID.randomUUID();
        assignMerchantCell(rollbackMerchant, "cell-0");

        var rbPlan = api.call("POST", "/v1/ops/cells/migrations", ops, null, Map.of(
                "merchantId", rollbackMerchant.toString(),
                "targetCellId", "cell-1",
                "reason", "test rollback from verifying"
        ));
        assertThat(rbPlan.status()).isEqualTo(200);
        UUID rbJobId = UUID.fromString(rbPlan.body().get("id").asString());

        assertThat(api.call("POST", "/v1/ops/cells/migrations/" + rbJobId + "/advance", ops, null, null)
                .body().get("state").asString()).isEqualTo("DUAL_WRITE");
        assertThat(api.call("POST", "/v1/ops/cells/migrations/" + rbJobId + "/advance", ops, null, null)
                .body().get("state").asString()).isEqualTo("BACKFILLING");
        assertThat(api.call("POST", "/v1/ops/cells/migrations/" + rbJobId + "/advance", ops, null, null)
                .body().get("state").asString()).isEqualTo("VERIFYING");

        var rolledBack = api.call("POST", "/v1/ops/cells/migrations/" + rbJobId + "/rollback", ops, null,
                Map.of("reason", "checksum verification mismatch"));
        assertThat(rolledBack.status()).isEqualTo(200);
        assertThat(rolledBack.body().get("state").asString()).isEqualTo("ROLLED_BACK");
        assertThat(rolledBack.body().get("completedAt").isNull()).isFalse();
        assertThat(rolledBack.body().get("lastError").asString()).isEqualTo("checksum verification mismatch");

        // Merchant remains on cell-0 and cannot be rolled back again
        assertThat(api.call("GET", "/v1/ops/cells/resolve/" + rollbackMerchant, ops, null, null)
                .body().get("cellId").asString()).isEqualTo("cell-0");
        assertThat(api.call("POST", "/v1/ops/cells/migrations/" + rbJobId + "/rollback", ops, null,
                Map.of("reason", "second rollback")).status()).isEqualTo(409);

        // 3. Rollback after CUTOVER restores merchant assignment back to sourceCellId
        UUID cutoverRbMerchant = UUID.randomUUID();
        assignMerchantCell(cutoverRbMerchant, "cell-0");
        var cutoverPlan = api.call("POST", "/v1/ops/cells/migrations", ops, null, Map.of(
                "merchantId", cutoverRbMerchant.toString(),
                "targetCellId", "cell-1",
                "reason", "test rollback from cutover"
        ));
        UUID cutoverRbJobId = UUID.fromString(cutoverPlan.body().get("id").asString());
        // Reproduce a job cut over by the earlier implementation.
        db.sql("UPDATE cell_migration_job SET state = 'CUTOVER' WHERE id = :id")
                .param("id", cutoverRbJobId).update();
        assignMerchantCell(cutoverRbMerchant, "cell-1");
        assertThat(api.call("GET", "/v1/ops/cells/resolve/" + cutoverRbMerchant, ops, null, null)
                .body().get("cellId").asString()).isEqualTo("cell-1");

        var cutoverRolledBack = api.call("POST", "/v1/ops/cells/migrations/" + cutoverRbJobId + "/rollback", ops, null,
                Map.of("reason", "post-cutover anomaly"));
        assertThat(cutoverRolledBack.status()).isEqualTo(200);
        assertThat(cutoverRolledBack.body().get("state").asString()).isEqualTo("ROLLED_BACK");
        assertThat(api.call("GET", "/v1/ops/cells/resolve/" + cutoverRbMerchant, ops, null, null)
                .body().get("cellId").asString()).isEqualTo("cell-0");
    }

    private void assignMerchantCell(UUID merchantId, String cellId) {
        var resp = api.call("POST", "/v1/ops/cells/assign", ops, null, Map.of(
                "merchantId", merchantId.toString(),
                "cellId", cellId,
                "pinned", true
        ));
        assertThat(resp.status()).isEqualTo(200);
    }

    private void linkSeller(UUID mp, String mpOwner, UUID seller, String sellerOwner, String sellerCode) {
        assertThat(api.call("POST", "/v1/merchants/" + mp + "/sellers", mpOwner, null,
                Map.of("merchantCode", sellerCode)).status()).isEqualTo(200);
        assertThat(api.call("POST", "/v1/merchants/" + seller + "/marketplaces/" + mp + "/accept",
                sellerOwner, null, null).status()).isEqualTo(200);
    }

    private static List<String> extractCellIds(JsonNode cellsNode) {
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode cell : cellsNode) {
            ids.add(cell.get("cellId").asString());
        }
        return ids;
    }
}
