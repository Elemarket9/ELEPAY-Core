package com.elepay.common.datasource;

import java.util.Objects;

/**
 * Composite lookup key for {@link CellAwareRoutingDataSource}, identifying both the logical
 * shard cell and whether the connection targets the cell's primary or read replica.
 */
public record CellDataSourceKey(String cellId, RouteType routeType) {

    public static final String DEFAULT_CELL_ID = "cell-0";

    public CellDataSourceKey {
        if (cellId == null || cellId.isBlank()) {
            cellId = DEFAULT_CELL_ID;
        } else {
            cellId = cellId.trim();
        }
        Objects.requireNonNull(routeType, "routeType must not be null");
    }

    public static CellDataSourceKey primary(String cellId) {
        return new CellDataSourceKey(cellId, RouteType.PRIMARY);
    }

    public static CellDataSourceKey replica(String cellId) {
        return new CellDataSourceKey(cellId, RouteType.REPLICA);
    }

    public static CellDataSourceKey defaultPrimary() {
        return new CellDataSourceKey(DEFAULT_CELL_ID, RouteType.PRIMARY);
    }

    public static CellDataSourceKey defaultReplica() {
        return new CellDataSourceKey(DEFAULT_CELL_ID, RouteType.REPLICA);
    }
}
