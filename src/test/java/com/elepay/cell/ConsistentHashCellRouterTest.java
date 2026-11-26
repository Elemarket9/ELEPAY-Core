package com.elepay.cell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsistentHashCellRouterTest {

    private CellRegistryService registry;
    private CellProperties properties;
    private ConsistentHashCellRouter router;

    @BeforeEach
    void setUp() {
        registry = mock(CellRegistryService.class);
        when(registry.findAssignedCell(any(UUID.class))).thenReturn(Optional.empty());

        properties = new CellProperties();
        properties.setEnabled(true);
        properties.setVirtualNodesPerCell(150);

        router = new ConsistentHashCellRouter(registry, properties);
    }

    @Test
    void resolutionIsDeterministicAcrossCallsAndInstances() {
        List<CellRecord> cells = fourActiveCells();
        when(registry.listCells()).thenReturn(cells);
        router.rebuildRing();

        ConsistentHashCellRouter secondRouter = new ConsistentHashCellRouter(registry, properties);
        secondRouter.rebuildRing();

        for (int i = 0; i < 100; i++) {
            UUID merchantId = syntheticMerchantId(i);
            String first = router.resolveCell(merchantId);
            String repeated = router.resolveCell(merchantId);
            router.invalidateCache(merchantId);
            String afterInvalidate = router.resolveCell(merchantId);
            String fromSecondInstance = secondRouter.resolveCell(merchantId);

            assertThat(first).isIn("cell-0", "cell-1", "cell-2", "cell-3");
            assertThat(repeated).isEqualTo(first);
            assertThat(afterInvalidate).isEqualTo(first);
            assertThat(fromSecondInstance).isEqualTo(first);
        }
    }

    @Test
    void distributionAcrossFourCellsIsBalancedForThousandMerchants() {
        when(registry.listCells()).thenReturn(fourActiveCells());
        router.rebuildRing();

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 1_000; i++) {
            String cell = router.resolveCell(syntheticMerchantId(i));
            counts.merge(cell, 1, Integer::sum);
        }

        assertThat(counts).containsOnlyKeys("cell-0", "cell-1", "cell-2", "cell-3");
        // Ideal is 250 per cell; with 150 SHA-256 virtual nodes, each cell should stay well within [175, 325].
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            assertThat(entry.getValue())
                    .as("Merchant count on %s", entry.getKey())
                    .isBetween(175, 325);
        }
    }

    @Test
    void addingFifthCellCausesMinimalKeyMovement() {
        List<CellRecord> fourCells = fourActiveCells();
        when(registry.listCells()).thenReturn(fourCells);
        router.rebuildRing();

        Map<UUID, String> before = new HashMap<>();
        for (int i = 0; i < 1_000; i++) {
            UUID merchantId = syntheticMerchantId(i);
            before.put(merchantId, router.resolveCell(merchantId));
        }

        List<CellRecord> fiveCells = new ArrayList<>(fourCells);
        fiveCells.add(activeCell("cell-4", 100));
        when(registry.listCells()).thenReturn(fiveCells);
        router.rebuildRing();

        int moved = 0;
        int movedToNewCell = 0;
        for (Map.Entry<UUID, String> entry : before.entrySet()) {
            String afterCell = router.resolveCell(entry.getKey());
            if (!afterCell.equals(entry.getValue())) {
                moved++;
                if ("cell-4".equals(afterCell)) {
                    movedToNewCell++;
                }
            }
        }

        // Consistent hashing property: ~1/5 (~200) keys move, and every moved key moves to the newly added cell.
        assertThat(moved).isBetween(120, 300);
        assertThat(movedToNewCell).isEqualTo(moved);
    }

    @Test
    void explicitAssignmentOverridesConsistentHashRing() {
        when(registry.listCells()).thenReturn(fourActiveCells());
        router.rebuildRing();

        UUID merchantId = syntheticMerchantId(42);
        String ringCell = router.resolveCell(merchantId);
        String overrideCell = "cell-0".equals(ringCell) ? "cell-3" : "cell-0";
        UUID affinityGroupId = UUID.randomUUID();

        router.assignMerchant(merchantId, overrideCell, affinityGroupId, true);
        verify(registry).upsertAssignment(merchantId, overrideCell, affinityGroupId, true);
        assertThat(router.resolveCell(merchantId)).isEqualTo(overrideCell);

        // After cache invalidation, DB lookup returns the explicit assignment.
        when(registry.findAssignedCell(merchantId)).thenReturn(Optional.of(overrideCell));
        router.invalidateCache(merchantId);
        assertThat(router.resolveCell(merchantId)).isEqualTo(overrideCell);
    }

    @Test
    void disabledCellsModeDefaultsToCellZeroUnlessExplicitlyAssigned() {
        properties.setEnabled(false);
        when(registry.listCells()).thenReturn(fourActiveCells());

        ConsistentHashCellRouter disabledRouter = new ConsistentHashCellRouter(registry, properties);
        UUID unassigned = syntheticMerchantId(7);
        UUID assigned = syntheticMerchantId(8);
        when(registry.findAssignedCell(assigned)).thenReturn(Optional.of("cell-2"));

        assertThat(disabledRouter.resolveCell(unassigned)).isEqualTo("cell-0");
        assertThat(disabledRouter.resolveCell(assigned)).isEqualTo("cell-2");
    }

    @Test
    void ringDistributionReflectsWeightAndExcludesNonActiveCells() {
        when(registry.listCells()).thenReturn(List.of(
                activeCell("cell-0", 100),
                activeCell("cell-1", 200),
                new CellRecord("cell-2", "Draining Cell", null, null, "DRAINING", 100, Instant.now()),
                new CellRecord("cell-3", "Offline Cell", null, null, "OFFLINE", 100, Instant.now())
        ));
        router.rebuildRing();

        Map<String, Integer> distribution = router.ringDistribution();
        assertThat(distribution)
                .containsOnlyKeys("cell-0", "cell-1")
                .containsEntry("cell-0", 150)
                .containsEntry("cell-1", 300);
    }

    private static List<CellRecord> fourActiveCells() {
        return List.of(
                activeCell("cell-0", 100),
                activeCell("cell-1", 100),
                activeCell("cell-2", 100),
                activeCell("cell-3", 100)
        );
    }

    private static CellRecord activeCell(String cellId, int weight) {
        return new CellRecord(cellId, "Cell " + cellId, null, null, "ACTIVE", weight, Instant.now());
    }

    private static UUID syntheticMerchantId(int index) {
        return UUID.nameUUIDFromBytes(("synthetic-merchant-" + index).getBytes(StandardCharsets.UTF_8));
    }
}
