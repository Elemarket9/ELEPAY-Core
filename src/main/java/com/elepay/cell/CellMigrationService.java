package com.elepay.cell;

import com.elepay.common.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates deterministic, auditable merchant cell migrations across sharding cells.
 *
 * <p>State machine:
 * {@code PLANNED -> DUAL_WRITE -> BACKFILLING -> VERIFYING -> CUTOVER -> COMPLETED},
 * with rollback from any non-terminal state to {@code ROLLED_BACK} and terminal {@code FAILED}.
 */
@Service
public class CellMigrationService {

    public static final String STATE_PLANNED = "PLANNED";
    public static final String STATE_DUAL_WRITE = "DUAL_WRITE";
    public static final String STATE_BACKFILLING = "BACKFILLING";
    public static final String STATE_VERIFYING = "VERIFYING";
    public static final String STATE_CUTOVER = "CUTOVER";
    public static final String STATE_COMPLETED = "COMPLETED";
    public static final String STATE_ROLLED_BACK = "ROLLED_BACK";
    public static final String STATE_FAILED = "FAILED";

    private static final Set<String> TERMINAL_STATES = Set.of(
            STATE_COMPLETED, STATE_ROLLED_BACK, STATE_FAILED
    );

    public record MigrationJob(
            UUID id,
            UUID merchantId,
            String sourceCellId,
            String targetCellId,
            String state,
            String createdBy,
            String reason,
            long rowsCopied,
            String verificationHash,
            String lastError,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt
    ) {}

    private static final String SELECT_COLUMNS = """
            id, merchant_id, source_cell_id, target_cell_id, state, created_by, reason,
            rows_copied, verification_hash, last_error, created_at, updated_at, completed_at
            """;

    private final JdbcClient db;
    private final CellRegistryService cellRegistryService;
    private final CellRouter cellRouter;

    public CellMigrationService(
            JdbcClient db,
            CellRegistryService cellRegistryService,
            CellRouter cellRouter) {
        this.db = db;
        this.cellRegistryService = cellRegistryService;
        this.cellRouter = cellRouter;
    }

    /**
     * Plans a new migration for {@code merchantId} to {@code targetCellId}.
     * Validates that the target cell exists and is {@code ACTIVE}, that source and target differ,
     * and that no active migration already exists for the merchant.
     */
    @Transactional
    public MigrationJob planMigration(UUID merchantId, String targetCellId, String actor, String reason) {
        if (merchantId == null) {
            throw ApiException.badRequest("invalid_merchant_id", "merchantId is required");
        }
        if (targetCellId == null || targetCellId.isBlank()) {
            throw ApiException.badRequest("invalid_target_cell", "targetCellId is required");
        }
        String normalizedTarget = targetCellId.trim();

        CellRecord targetCell = cellRegistryService.listCells().stream()
                .filter(c -> normalizedTarget.equals(c.cellId()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("cell_not_found", "Target cell not found: " + normalizedTarget));

        if (!"ACTIVE".equalsIgnoreCase(targetCell.status())) {
            throw ApiException.badRequest(
                    "target_cell_not_active",
                    "Target cell " + normalizedTarget + " is not ACTIVE (status=" + targetCell.status() + ")");
        }

        String sourceCellId = cellRouter.resolveCell(merchantId);
        if (normalizedTarget.equals(sourceCellId)) {
            throw ApiException.badRequest(
                    "same_cell_migration",
                    "Merchant " + merchantId + " is already assigned to cell " + normalizedTarget);
        }

        long activeCount = db.sql("""
                SELECT count(*) FROM cell_migration_job
                WHERE merchant_id = :merchantId
                  AND state NOT IN ('COMPLETED', 'ROLLED_BACK', 'FAILED')
                """)
                .param("merchantId", merchantId)
                .query(Long.class)
                .single();
        if (activeCount > 0) {
            throw ApiException.conflict(
                    "active_migration_exists",
                    "An active migration already exists for merchant " + merchantId);
        }

        UUID id = UUID.randomUUID();
        String effectiveActor = (actor == null || actor.isBlank()) ? "system" : actor.trim();

        db.sql("""
                INSERT INTO cell_migration_job (
                    id, merchant_id, source_cell_id, target_cell_id, state,
                    created_by, reason, rows_copied, created_at, updated_at
                ) VALUES (
                    :id, :merchantId, :sourceCellId, :targetCellId, :state,
                    :createdBy, :reason, 0, now(), now()
                )
                """)
                .param("id", id)
                .param("merchantId", merchantId)
                .param("sourceCellId", sourceCellId)
                .param("targetCellId", normalizedTarget)
                .param("state", STATE_PLANNED)
                .param("createdBy", effectiveActor)
                .param("reason", reason, Types.VARCHAR)
                .update();

        return fetchById(id);
    }

    /**
     * Advances a migration job to the exact next state in the linear pipeline.
     * Locks the job row {@code FOR UPDATE}. On {@code CUTOVER}, atomically updates
     * {@code merchant_cell_assignment} to {@code targetCellId} and invalidates router cache.
     * On {@code COMPLETED}, stamps {@code completed_at = now()}.
     */
    @Transactional
    public MigrationJob advanceState(UUID migrationId, String actor) {
        MigrationJob current = lockForUpdate(migrationId);
        if (TERMINAL_STATES.contains(current.state())) {
            throw ApiException.conflict(
                    "migration_already_terminal",
                    "Migration " + migrationId + " is already in terminal state " + current.state());
        }

        String nextState = nextStateOf(current.state());
        String verificationHash = current.verificationHash();

        if (STATE_CUTOVER.equals(nextState)) {
            // The migration workflow does not yet copy and reconcile physical ledger data.
            // Legacy verification hashes describe job metadata and cannot authorize a cutover.
            throw ApiException.conflict("migration_data_unverified",
                    "Cutover requires verified ledger data on the destination cell");
        }

        if (STATE_COMPLETED.equals(nextState)) {
            db.sql("""
                    UPDATE cell_migration_job
                    SET state = :state,
                        updated_at = now(),
                        completed_at = now()
                    WHERE id = :id
                    """)
                    .param("state", nextState)
                    .param("id", migrationId)
                    .update();
        } else {
            db.sql("""
                    UPDATE cell_migration_job
                    SET state = :state,
                        verification_hash = :verificationHash,
                        updated_at = now()
                    WHERE id = :id
                    """)
                    .param("state", nextState)
                    .param("verificationHash", verificationHash, Types.VARCHAR)
                    .param("id", migrationId)
                    .update();
        }

        return fetchById(migrationId);
    }

    /**
     * Rolls back a non-terminal migration job to {@code ROLLED_BACK}.
     * If the migration had already reached {@code CUTOVER}, restores the merchant's assignment
     * back to {@code sourceCellId} and invalidates router cache.
     */
    @Transactional
    public MigrationJob rollbackMigration(UUID migrationId, String actor, String reason) {
        MigrationJob current = lockForUpdate(migrationId);
        if (TERMINAL_STATES.contains(current.state())) {
            throw ApiException.conflict(
                    "migration_cannot_rollback",
                    "Migration " + migrationId + " cannot be rolled back from state " + current.state());
        }

        if (STATE_CUTOVER.equals(current.state())) {
            UUID affinityGroupId = findAffinityGroupId(current.merchantId());
            cellRouter.assignMerchant(current.merchantId(), current.sourceCellId(), affinityGroupId, true);
            cellRouter.invalidateCache(current.merchantId());
        }

        String effectiveError = (reason != null && !reason.isBlank()) ? reason : current.lastError();
        db.sql("""
                UPDATE cell_migration_job
                SET state = :state,
                    last_error = :lastError,
                    updated_at = now(),
                    completed_at = now()
                WHERE id = :id
                """)
                .param("state", STATE_ROLLED_BACK)
                .param("lastError", effectiveError, Types.VARCHAR)
                .param("id", migrationId)
                .update();

        return fetchById(migrationId);
    }

    @Transactional(readOnly = true)
    public MigrationJob getMigration(UUID migrationId) {
        return fetchById(migrationId);
    }

    @Transactional(readOnly = true)
    public List<MigrationJob> listMigrations() {
        return db.sql("SELECT " + SELECT_COLUMNS + " FROM cell_migration_job ORDER BY created_at DESC, id DESC")
                .query(CellMigrationService::mapRow)
                .list();
    }

    private MigrationJob lockForUpdate(UUID migrationId) {
        if (migrationId == null) {
            throw ApiException.badRequest("invalid_migration_id", "migrationId is required");
        }
        return db.sql("SELECT " + SELECT_COLUMNS + " FROM cell_migration_job WHERE id = :id FOR UPDATE")
                .param("id", migrationId)
                .query(CellMigrationService::mapRow)
                .optional()
                .orElseThrow(() -> ApiException.notFound("migration_not_found", "Migration job not found: " + migrationId));
    }

    private MigrationJob fetchById(UUID migrationId) {
        if (migrationId == null) {
            throw ApiException.badRequest("invalid_migration_id", "migrationId is required");
        }
        return db.sql("SELECT " + SELECT_COLUMNS + " FROM cell_migration_job WHERE id = :id")
                .param("id", migrationId)
                .query(CellMigrationService::mapRow)
                .optional()
                .orElseThrow(() -> ApiException.notFound("migration_not_found", "Migration job not found: " + migrationId));
    }

    private UUID findAffinityGroupId(UUID merchantId) {
        return db.sql("SELECT affinity_group_id FROM merchant_cell_assignment WHERE merchant_id = :merchantId")
                .param("merchantId", merchantId)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private static String nextStateOf(String currentState) {
        return switch (currentState) {
            case STATE_PLANNED -> STATE_DUAL_WRITE;
            case STATE_DUAL_WRITE -> STATE_BACKFILLING;
            case STATE_BACKFILLING -> STATE_VERIFYING;
            case STATE_VERIFYING -> STATE_CUTOVER;
            case STATE_CUTOVER -> STATE_COMPLETED;
            default -> throw ApiException.conflict(
                    "invalid_migration_state",
                    "Cannot advance migration from state " + currentState);
        };
    }

    private static MigrationJob mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new MigrationJob(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("source_cell_id"),
                rs.getString("target_cell_id"),
                rs.getString("state"),
                rs.getString("created_by"),
                rs.getString("reason"),
                rs.getLong("rows_copied"),
                rs.getString("verification_hash"),
                rs.getString("last_error"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                toInstant(rs.getTimestamp("completed_at"))
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
