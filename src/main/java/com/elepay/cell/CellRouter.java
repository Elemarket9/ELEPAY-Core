package com.elepay.cell;

import java.util.Map;
import java.util.UUID;

/**
 * Resolves merchants to sharding cells and manages explicit assignments and ring topology.
 */
public interface CellRouter extends com.elepay.ingestion.IngestionWorkerPool.TenantCellResolver {

    @Override
    String resolveCell(UUID merchantId);

    Map<String, Integer> ringDistribution();

    void assignMerchant(UUID merchantId, String cellId, UUID affinityGroupId, boolean pinned);

    void invalidateCache(UUID merchantId);

    void rebuildRing();
}
