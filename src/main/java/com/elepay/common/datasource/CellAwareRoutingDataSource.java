package com.elepay.common.datasource;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cell-aware, read/write-splitting {@link AbstractRoutingDataSource} keyed by
 * {@link CellDataSourceKey}.
 *
 * <p>Routing rules in {@link #determineCurrentLookupKey()}:
 * <ol>
 *   <li>Reads {@code cellId = DataSourceRouteContext.getCellId()} (default {@code "cell-0"}).</li>
 *   <li>If {@code cellId} is not registered (e.g. single-cell mode), falls back to
 *       {@code (DEFAULT_CELL_ID, PRIMARY)}.</li>
 *   <li>Determines desired {@link RouteType}: explicit {@link DataSourceRouteContext#getRouteOverride()}
 *       if present, else {@link RouteType#REPLICA} when
 *       {@link TransactionSynchronizationManager#isCurrentTransactionReadOnly()} is {@code true},
 *       else {@link RouteType#PRIMARY}.</li>
 *   <li>Forces {@link RouteType#PRIMARY} if {@link DataSourceRouteContext#isPrimaryWritten()}
 *       (RYOW sticky-primary), or {@code !replicaHealthTracker.isReplicaHealthy(cellId)}, or no
 *       distinct replica is configured for {@code cellId}.</li>
 *   <li>Increments Micrometer counter {@code elepay.datasource.route} (tags {@code cell}, {@code route})
 *       when a {@link MeterRegistry} is attached.</li>
 * </ol>
 */
public class CellAwareRoutingDataSource extends AbstractRoutingDataSource implements DisposableBean {

    public static final String METRIC_ROUTE_COUNTER = "elepay.datasource.route";

    private static final Logger log = LoggerFactory.getLogger(CellAwareRoutingDataSource.class);

    private final ReplicaHealthTracker replicaHealthTracker;
    private volatile MeterRegistry meterRegistry;

    private final ConcurrentHashMap<CellDataSourceKey, DataSource> dynamicDataSources = new ConcurrentHashMap<>();
    private final Set<String> distinctReplicaCells = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Counter> routeCounters = new ConcurrentHashMap<>();
    private volatile DataSource defaultDataSource;

    public CellAwareRoutingDataSource() {
        this(new ReplicaHealthTracker(), null);
    }

    public CellAwareRoutingDataSource(ReplicaHealthTracker replicaHealthTracker) {
        this(replicaHealthTracker, null);
    }

    public CellAwareRoutingDataSource(ReplicaHealthTracker replicaHealthTracker, MeterRegistry meterRegistry) {
        this.replicaHealthTracker = Objects.requireNonNull(replicaHealthTracker, "replicaHealthTracker must not be null");
        this.meterRegistry = meterRegistry;
    }

    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.routeCounters.clear();
    }

    public ReplicaHealthTracker getReplicaHealthTracker() {
        return replicaHealthTracker;
    }

    /**
     * Dynamically registers or updates a cell's primary and optional read-replica {@link DataSource}.
     * If {@code replica} is {@code null} or identical to {@code primary}, the cell operates in
     * single-pool mode and all reads route to {@code primary}.
     */
    public void registerCell(String cellId, DataSource primary, DataSource replica) {
        String normalizedCellId = normalizeCellId(cellId);
        Objects.requireNonNull(primary, "primary DataSource must not be null");

        dynamicDataSources.put(CellDataSourceKey.primary(normalizedCellId), primary);
        if (replica != null && replica != primary) {
            dynamicDataSources.put(CellDataSourceKey.replica(normalizedCellId), replica);
            distinctReplicaCells.add(normalizedCellId);
        } else {
            dynamicDataSources.remove(CellDataSourceKey.replica(normalizedCellId));
            distinctReplicaCells.remove(normalizedCellId);
        }

        if (CellDataSourceKey.DEFAULT_CELL_ID.equals(normalizedCellId) && this.defaultDataSource == null) {
            setDefaultTargetDataSource(primary);
        }
        super.setTargetDataSources(new HashMap<>(dynamicDataSources));
    }

    @Override
    public void setTargetDataSources(Map<Object, Object> targetDataSources) {
        Objects.requireNonNull(targetDataSources, "targetDataSources must not be null");
        dynamicDataSources.clear();
        distinctReplicaCells.clear();

        for (Map.Entry<Object, Object> entry : targetDataSources.entrySet()) {
            if (entry.getKey() instanceof CellDataSourceKey key && entry.getValue() instanceof DataSource ds) {
                dynamicDataSources.put(key, ds);
            }
        }
        for (Map.Entry<CellDataSourceKey, DataSource> entry : dynamicDataSources.entrySet()) {
            CellDataSourceKey key = entry.getKey();
            if (key.routeType() == RouteType.REPLICA) {
                DataSource primaryDs = dynamicDataSources.get(CellDataSourceKey.primary(key.cellId()));
                if (entry.getValue() != null && entry.getValue() != primaryDs) {
                    distinctReplicaCells.add(key.cellId());
                }
            }
        }
        super.setTargetDataSources(targetDataSources);
    }

    @Override
    public void setDefaultTargetDataSource(Object defaultTargetDataSource) {
        if (defaultTargetDataSource instanceof DataSource ds) {
            this.defaultDataSource = ds;
        }
        super.setDefaultTargetDataSource(defaultTargetDataSource);
    }

    public boolean hasCell(String cellId) {
        return dynamicDataSources.containsKey(CellDataSourceKey.primary(normalizeCellId(cellId)));
    }

    public boolean hasDistinctReplica(String cellId) {
        return distinctReplicaCells.contains(normalizeCellId(cellId));
    }

    public Set<String> registeredCells() {
        return Collections.unmodifiableSet(
                dynamicDataSources.keySet().stream()
                        .filter(k -> k.routeType() == RouteType.PRIMARY)
                        .map(CellDataSourceKey::cellId)
                        .collect(Collectors.toSet()));
    }

    @Override
    protected Object determineCurrentLookupKey() {
        String requestedCellId = DataSourceRouteContext.getCellId();
        boolean cellRegistered = hasCell(requestedCellId);

        String effectiveCellId;
        RouteType effectiveRoute;

        if (!cellRegistered) {
            effectiveCellId = CellDataSourceKey.DEFAULT_CELL_ID;
            effectiveRoute = RouteType.PRIMARY;
        } else {
            effectiveCellId = requestedCellId;
            RouteType override = DataSourceRouteContext.getRouteOverride();
            RouteType desiredRoute = (override != null)
                    ? override
                    : (TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                            ? RouteType.REPLICA
                            : RouteType.PRIMARY);

            if (desiredRoute == RouteType.REPLICA) {
                boolean forcePrimary = DataSourceRouteContext.isPrimaryWritten()
                        || !hasDistinctReplica(effectiveCellId)
                        || !replicaHealthTracker.isReplicaHealthy(effectiveCellId);
                effectiveRoute = forcePrimary ? RouteType.PRIMARY : RouteType.REPLICA;
            } else {
                effectiveRoute = RouteType.PRIMARY;
            }
        }

        recordRouteMetric(effectiveCellId, effectiveRoute);
        return new CellDataSourceKey(effectiveCellId, effectiveRoute);
    }

    @Override
    protected DataSource determineTargetDataSource() {
        Object lookupKey = determineCurrentLookupKey();
        if (lookupKey instanceof CellDataSourceKey key) {
            DataSource ds = dynamicDataSources.get(key);
            if (ds != null) {
                return ds;
            }
            if (key.routeType() == RouteType.REPLICA) {
                ds = dynamicDataSources.get(CellDataSourceKey.primary(key.cellId()));
                if (ds != null) {
                    return ds;
                }
            }
            ds = dynamicDataSources.get(CellDataSourceKey.defaultPrimary());
            if (ds != null) {
                return ds;
            }
            if (defaultDataSource != null) {
                return defaultDataSource;
            }
        }
        return super.determineTargetDataSource();
    }

    @Override
    public void destroy() {
        Set<DataSource> uniquePools = Collections.newSetFromMap(new IdentityHashMap<>());
        uniquePools.addAll(dynamicDataSources.values());
        if (defaultDataSource != null) {
            uniquePools.add(defaultDataSource);
        }
        for (DataSource ds : uniquePools) {
            if (ds instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ex) {
                    log.warn("Failed to close managed DataSource {}: {}", ds, ex.getMessage());
                }
            }
        }
    }

    private void recordRouteMetric(String cellId, RouteType routeType) {
        MeterRegistry registry = this.meterRegistry;
        if (registry == null) {
            return;
        }
        String routeTag = routeType.name().toLowerCase(Locale.ROOT);
        String counterKey = cellId + ":" + routeTag;
        Counter counter = routeCounters.computeIfAbsent(
                counterKey,
                k -> Counter.builder(METRIC_ROUTE_COUNTER)
                        .description("JDBC datasource routing decisions by cell and role")
                        .tag("cell", cellId)
                        .tag("route", routeTag)
                        .register(registry));
        counter.increment();
    }

    private static String normalizeCellId(String cellId) {
        return (cellId == null || cellId.isBlank()) ? CellDataSourceKey.DEFAULT_CELL_ID : cellId.trim();
    }
}
