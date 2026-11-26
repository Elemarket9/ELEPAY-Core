package com.elepay.operations;

import com.elepay.common.ScheduledJobLock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Automated partition lifecycle management and operational visibility for
 * PostgreSQL declarative partitions introduced in V77:
 * <ul>
 *   <li>Daily leader-elected pre-provisioning of future monthly partitions via
 *       {@code ensure_monthly_partitions(months_ahead)}.</li>
 *   <li>Daily bounded-batch TTL pruning of expired {@code idempotent_request} rows
 *       via {@code prune_idempotent_requests(retention_days, batch_size)}.</li>
 *   <li>Read-only partition inventory and storage statistics for operations.</li>
 * </ul>
 */
@Service
public class PartitionMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceService.class);

    public record PartitionStat(
            String parentTable,
            String partitionName,
            long estimatedRows,
            long totalBytes) {}

    private final JdbcClient db;
    private final ScheduledJobLock jobLock;
    private final boolean enabled;
    private final int monthsAhead;
    private final int idempotencyRetentionDays;
    private final int pruneBatchSize;
    private final int pruneMaxBatches;

    public PartitionMaintenanceService(
            JdbcClient db,
            ScheduledJobLock jobLock,
            @Value("${elepay.partitioning.maintenance.enabled:true}") boolean enabled,
            @Value("${elepay.partitioning.months-ahead:6}") int monthsAhead,
            @Value("${elepay.partitioning.idempotency-retention-days:30}") int idempotencyRetentionDays,
            @Value("${elepay.partitioning.idempotency-prune-batch-size:10000}") int pruneBatchSize,
            @Value("${elepay.partitioning.idempotency-prune-max-batches:10}") int pruneMaxBatches) {
        this.db = db;
        this.jobLock = jobLock;
        this.enabled = enabled;
        this.monthsAhead = Math.max(1, monthsAhead);
        this.idempotencyRetentionDays = Math.max(1, idempotencyRetentionDays);
        this.pruneBatchSize = Math.max(1, pruneBatchSize);
        this.pruneMaxBatches = Math.max(1, pruneMaxBatches);
    }

    @Scheduled(fixedDelayString = "${elepay.partitioning.maintenance-ms:86400000}", initialDelay = 120_000)
    public void runDailyMaintenance() {
        if (!enabled) {
            return;
        }
        jobLock.runIfLeader("partition-maintenance", () -> {
            int created = ensurePartitions(monthsAhead);
            long pruned = pruneExpiredIdempotentRequests(idempotencyRetentionDays, pruneBatchSize, pruneMaxBatches);
            if (created > 0 || pruned > 0) {
                log.info("Partition maintenance completed: createdPartitions={}, prunedIdempotentRequests={}",
                        created, pruned);
            }
        });
    }

    /**
     * Idempotently ensures monthly partitions (plus default partition) exist for all
     * range-partitioned tables up to {@code monthsAhead} months in the future.
     *
     * @return number of newly created partitions
     */
    public int ensurePartitions(int monthsAhead) {
        int horizon = Math.max(1, monthsAhead);
        Integer created = db.sql("SELECT ensure_monthly_partitions(:months)")
                .param("months", horizon)
                .query(Integer.class)
                .single();
        return created != null ? created : 0;
    }

    /**
     * Prunes expired {@code idempotent_request} rows older than {@code retentionDays}
     * in bounded batches to avoid long lock holds or WAL spikes.
     *
     * @return total number of pruned rows across all batches
     */
    public long pruneExpiredIdempotentRequests(int retentionDays, int batchSize, int maxBatches) {
        int days = Math.max(1, retentionDays);
        int limit = Math.max(1, batchSize);
        int batches = Math.max(1, maxBatches);
        long totalDeleted = 0L;
        for (int i = 0; i < batches; i++) {
            Long deleted = db.sql("SELECT prune_idempotent_requests(:days, :batchSize)")
                    .param("days", days)
                    .param("batchSize", limit)
                    .query(Long.class)
                    .single();
            long count = deleted != null ? deleted : 0L;
            totalDeleted += count;
            if (count < limit) {
                break;
            }
        }
        return totalDeleted;
    }

    /**
     * Returns size and estimated row count statistics for all child partitions of
     * declarative partitioned tables in the {@code public} schema.
     */
    @Transactional(readOnly = true)
    public List<PartitionStat> listPartitionStats() {
        return db.sql("""
                SELECT parent.relname AS parent_table,
                       child.relname AS partition_name,
                       COALESCE(s.n_live_tup, GREATEST(child.reltuples::bigint, 0)) AS estimated_rows,
                       pg_total_relation_size(child.oid) AS total_bytes
                FROM pg_inherits i
                JOIN pg_class parent ON parent.oid = i.inhparent
                JOIN pg_namespace pn ON pn.oid = parent.relnamespace
                JOIN pg_class child ON child.oid = i.inhrelid
                LEFT JOIN pg_stat_user_tables s ON s.relid = child.oid
                WHERE pn.nspname = 'public'
                  AND parent.relkind = 'p'
                ORDER BY parent.relname, child.relname
                """)
                .query((rs, rowNum) -> new PartitionStat(
                        rs.getString("parent_table"),
                        rs.getString("partition_name"),
                        rs.getLong("estimated_rows"),
                        rs.getLong("total_bytes")))
                .list();
    }
}
