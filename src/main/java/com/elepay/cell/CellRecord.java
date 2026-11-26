package com.elepay.cell;

import java.time.Instant;

/**
 * Immutable representation of a registered sharding cell in {@code cell_registry}.
 */
public record CellRecord(
        String cellId,
        String displayName,
        String jdbcUrl,
        String replicaJdbcUrl,
        String status,
        int weight,
        Instant createdAt
) {}
