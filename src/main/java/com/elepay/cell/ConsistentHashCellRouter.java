package com.elepay.cell;

import com.elepay.common.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * SHA-256 virtual-node consistent hash router backed by {@link CellRegistryService} and an
 * in-memory assignment cache.
 */
@Service
public class ConsistentHashCellRouter implements CellRouter {

    private static final String DEFAULT_FALLBACK_CELL = "cell-0";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final CellRegistryService registry;
    private final CellProperties properties;
    private final ConcurrentHashMap<UUID, String> assignmentCache = new ConcurrentHashMap<>();
    private final Object ringLock = new Object();

    private volatile RingSnapshot ringSnapshot;

    public ConsistentHashCellRouter(CellRegistryService registry, CellProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @Override
    public String resolveCell(UUID merchantId) {
        String defaultCell = defaultCellId();
        if (merchantId == null) {
            return defaultCell;
        }

        String cached = assignmentCache.get(merchantId);
        if (cached != null) {
            return cached;
        }

        Optional<String> assigned = registry.findAssignedCell(merchantId);
        if (assigned.isPresent()) {
            cachePut(merchantId, assigned.get());
            return assigned.get();
        }

        if (!properties.isEnabled()) {
            cachePut(merchantId, defaultCell);
            return defaultCell;
        }

        RingSnapshot snapshot = getOrBuildRing();
        String resolved = lookupOnRing(snapshot, merchantId, defaultCell);
        cachePut(merchantId, resolved);
        return resolved;
    }

    @Override
    public Map<String, Integer> ringDistribution() {
        return getOrBuildRing().distribution();
    }

    @Override
    public void assignMerchant(UUID merchantId, String cellId, UUID affinityGroupId, boolean pinned) {
        if (merchantId == null) {
            throw ApiException.badRequest("invalid_merchant_id", "merchantId must not be null");
        }
        if (cellId == null || cellId.isBlank()) {
            throw ApiException.badRequest("invalid_cell_id", "cellId must not be blank");
        }
        String trimmedCellId = cellId.trim();
        registry.upsertAssignment(merchantId, trimmedCellId, affinityGroupId, pinned);
        cachePut(merchantId, trimmedCellId);
    }

    @Override
    public void invalidateCache(UUID merchantId) {
        if (merchantId != null) {
            assignmentCache.remove(merchantId);
        }
    }

    @Override
    public void rebuildRing() {
        synchronized (ringLock) {
            List<CellRecord> allCells = registry.listCells();
            List<CellRecord> activeCells = allCells == null
                    ? List.of()
                    : allCells.stream()
                            .filter(c -> c != null && STATUS_ACTIVE.equalsIgnoreCase(c.status()))
                            .sorted(Comparator.comparing(CellRecord::cellId))
                            .toList();

            TreeMap<Long, String> ring = new TreeMap<>();
            Map<String, Integer> distribution = new LinkedHashMap<>();
            Set<String> activeCellIds = new LinkedHashSet<>();

            int baseVirtualNodes = Math.max(1, properties.getVirtualNodesPerCell());
            for (CellRecord cell : activeCells) {
                int weight = Math.max(1, cell.weight());
                int vnodes = Math.max(1, (int) Math.round(baseVirtualNodes * (weight / 100.0)));
                for (int i = 0; i < vnodes; i++) {
                    long hash = sha256Hash(cell.cellId() + "#vn-" + i);
                    ring.put(hash, cell.cellId());
                }
                distribution.put(cell.cellId(), vnodes);
                activeCellIds.add(cell.cellId());
            }

            this.ringSnapshot = new RingSnapshot(
                    Collections.unmodifiableNavigableMap(ring),
                    Collections.unmodifiableMap(distribution),
                    Collections.unmodifiableSet(activeCellIds)
            );
            assignmentCache.clear();
        }
    }

    private RingSnapshot getOrBuildRing() {
        RingSnapshot current = this.ringSnapshot;
        if (current == null) {
            rebuildRing();
            current = this.ringSnapshot;
        }
        return current;
    }

    private static String lookupOnRing(RingSnapshot snapshot, UUID merchantId, String defaultCell) {
        if (snapshot == null || snapshot.ring().isEmpty()) {
            return defaultCell;
        }
        if (snapshot.activeCellIds().size() == 1) {
            return snapshot.activeCellIds().iterator().next();
        }
        long hash = sha256Hash(merchantId.toString());
        Map.Entry<Long, String> entry = snapshot.ring().ceilingEntry(hash);
        if (entry == null) {
            entry = snapshot.ring().firstEntry();
        }
        return entry != null ? entry.getValue() : defaultCell;
    }

    private void cachePut(UUID merchantId, String cellId) {
        int maxSize = Math.max(1, properties.getCacheMaxSize());
        if (assignmentCache.size() >= maxSize) {
            assignmentCache.clear();
        }
        assignmentCache.put(merchantId, cellId);
    }

    private String defaultCellId() {
        String configured = properties.getDefaultCellId();
        return (configured == null || configured.isBlank()) ? DEFAULT_FALLBACK_CELL : configured.trim();
    }

    static long sha256Hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(bytes, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private record RingSnapshot(
            NavigableMap<Long, String> ring,
            Map<String, Integer> distribution,
            Set<String> activeCellIds
    ) {}
}
