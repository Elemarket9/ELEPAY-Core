package com.elepay.cell;

import com.elepay.common.ApiException;
import com.elepay.common.datasource.DataSourceRouteContext;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Enforces cell-locality invariants for multi-merchant transactions (such as marketplace
 * checkouts and splits) and supports pinning related merchants into an affinity group on
 * the anchor merchant's cell.
 */
@Service
public class CrossCellGuard {

    private final CellRouter cellRouter;

    public CrossCellGuard(CellRouter cellRouter) {
        this.cellRouter = cellRouter;
    }

    /**
     * Verifies that all non-null merchants in {@code merchantIds} resolve to the same cell.
     * Ignores {@code null}, empty, or single-merchant collections.
     *
     * @throws ApiException HTTP 409 Conflict ({@code cross_cell_operation_forbidden}) if
     *     merchants span more than one cell
     */
    public void assertSameCell(Collection<UUID> merchantIds) {
        if (merchantIds == null || merchantIds.isEmpty()) {
            return;
        }
        Set<UUID> distinctMerchants = new LinkedHashSet<>();
        for (UUID merchantId : merchantIds) {
            if (merchantId != null) {
                distinctMerchants.add(merchantId);
            }
        }
        if (distinctMerchants.size() <= 1) {
            return;
        }
        Set<String> cells = new LinkedHashSet<>();
        for (UUID merchantId : distinctMerchants) {
            String cellId = cellRouter.resolveCell(merchantId);
            cells.add(cellId == null || cellId.isBlank() ? DataSourceRouteContext.DEFAULT_CELL_ID : cellId);
        }
        if (cells.size() > 1) {
            throw ApiException.conflict(
                    "cross_cell_operation_forbidden",
                    "All merchants in a multi-merchant transaction must reside in the same cell: " + cells);
        }
    }

    /**
     * Pins {@code memberMerchantId} to the same cell as {@code anchorMerchantId}, recording
     * {@code anchorMerchantId} as the affinity group anchor.
     */
    public void pinAffinityGroup(UUID anchorMerchantId, UUID memberMerchantId) {
        Objects.requireNonNull(anchorMerchantId, "anchorMerchantId must not be null");
        Objects.requireNonNull(memberMerchantId, "memberMerchantId must not be null");
        String anchorCell = cellRouter.resolveCell(anchorMerchantId);
        if (anchorCell == null || anchorCell.isBlank()) {
            anchorCell = DataSourceRouteContext.DEFAULT_CELL_ID;
        }
        cellRouter.assignMerchant(memberMerchantId, anchorCell, anchorMerchantId, true);
    }
}
