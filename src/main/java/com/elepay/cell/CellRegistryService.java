package com.elepay.cell;

import com.elepay.common.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Persistence service for {@code cell_registry} and {@code merchant_cell_assignment}.
 */
@Service
public class CellRegistryService {

    private final JdbcClient db;

    public CellRegistryService(JdbcClient db) {
        this.db = db;
    }

    public List<CellRecord> listCells() {
        return db.sql("""
                SELECT cell_id, display_name, jdbc_url, replica_jdbc_url, status, weight, created_at
                FROM cell_registry
                ORDER BY cell_id ASC
                """)
                .query(CellRegistryService::mapCellRecord)
                .list();
    }

    public Optional<CellRecord> getCell(String cellId) {
        if (cellId == null || cellId.isBlank()) {
            return Optional.empty();
        }
        return db.sql("""
                SELECT cell_id, display_name, jdbc_url, replica_jdbc_url, status, weight, created_at
                FROM cell_registry
                WHERE cell_id = :cellId
                """)
                .param("cellId", cellId.trim())
                .query(CellRegistryService::mapCellRecord)
                .optional();
    }

    public CellRecord registerCell(
            String cellId,
            String displayName,
            String jdbcUrl,
            String replicaJdbcUrl,
            int weight
    ) {
        if (cellId == null || cellId.isBlank()) {
            throw ApiException.badRequest("invalid_cell_id", "cellId must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw ApiException.badRequest("invalid_display_name", "displayName must not be blank");
        }
        if (weight <= 0) {
            throw ApiException.badRequest("invalid_cell_weight", "weight must be greater than zero");
        }

        String trimmedCellId = cellId.trim();
        String trimmedDisplayName = displayName.trim();

        db.sql("""
                INSERT INTO cell_registry (
                    cell_id, display_name, jdbc_url, replica_jdbc_url, status, weight, created_at, updated_at
                )
                VALUES (
                    :cellId, :displayName, :jdbcUrl, :replicaJdbcUrl, 'ACTIVE', :weight, now(), now()
                )
                ON CONFLICT (cell_id) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    jdbc_url = EXCLUDED.jdbc_url,
                    replica_jdbc_url = EXCLUDED.replica_jdbc_url,
                    weight = EXCLUDED.weight,
                    updated_at = now()
                """)
                .param("cellId", trimmedCellId)
                .param("displayName", trimmedDisplayName)
                .param("jdbcUrl", jdbcUrl, Types.VARCHAR)
                .param("replicaJdbcUrl", replicaJdbcUrl, Types.VARCHAR)
                .param("weight", weight)
                .update();

        return getCell(trimmedCellId)
                .orElseThrow(() -> ApiException.internal("cell_registration_failed",
                        "Registered cell could not be loaded: " + trimmedCellId));
    }

    public Optional<String> findAssignedCell(UUID merchantId) {
        if (merchantId == null) {
            return Optional.empty();
        }
        return db.sql("""
                SELECT cell_id
                FROM merchant_cell_assignment
                WHERE merchant_id = :merchantId
                """)
                .param("merchantId", merchantId)
                .query(String.class)
                .optional();
    }

    public void upsertAssignment(UUID merchantId, String cellId, UUID affinityGroupId, boolean pinned) {
        if (merchantId == null) {
            throw ApiException.badRequest("invalid_merchant_id", "merchantId must not be null");
        }
        if (cellId == null || cellId.isBlank()) {
            throw ApiException.badRequest("invalid_cell_id", "cellId must not be blank");
        }
        String trimmedCellId = cellId.trim();
        if (getCell(trimmedCellId).isEmpty()) {
            throw ApiException.notFound("cell_not_found", "Cell not found: " + trimmedCellId);
        }

        db.sql("""
                INSERT INTO merchant_cell_assignment (
                    merchant_id, cell_id, affinity_group_id, pinned, assigned_at, updated_at
                )
                VALUES (
                    :merchantId, :cellId, :affinityGroupId, :pinned, now(), now()
                )
                ON CONFLICT (merchant_id) DO UPDATE SET
                    cell_id = EXCLUDED.cell_id,
                    affinity_group_id = EXCLUDED.affinity_group_id,
                    pinned = EXCLUDED.pinned,
                    updated_at = now()
                """)
                .param("merchantId", merchantId)
                .param("cellId", trimmedCellId)
                .param("affinityGroupId", affinityGroupId, Types.OTHER)
                .param("pinned", pinned)
                .update();
    }

    private static CellRecord mapCellRecord(ResultSet rs, int rowNum) throws SQLException {
        Timestamp ts = rs.getTimestamp("created_at");
        Instant createdAt = ts != null ? ts.toInstant() : null;
        return new CellRecord(
                rs.getString("cell_id"),
                rs.getString("display_name"),
                rs.getString("jdbc_url"),
                rs.getString("replica_jdbc_url"),
                rs.getString("status"),
                rs.getInt("weight"),
                createdAt
        );
    }
}
