package com.elepay.common;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * PostgreSQL advisory lock for {@code @Scheduled} financial jobs.
 * Ensures only one backend instance runs a scheduled settlement, payout, recovery,
 * audit checkpoint, or custodian balance sync at a time in multi-pod production clusters.
 *
 * <p>{@link #runIfLeader(String, Runnable)} deliberately executes {@code task}
 * <strong>outside</strong> any enclosing database transaction, holding a dedicated
 * session-level {@code pg_try_advisory_lock} connection that is released in {@code finally}
 * (and automatically released by PostgreSQL if the pod crashes). This prevents long-running
 * external HTTP calls from holding open DB transactions and prevents a single item failure
 * from rolling back previously submitted payouts or rail state transitions.
 */
@Service
public class ScheduledJobLock {
    private static final Logger log = LoggerFactory.getLogger(ScheduledJobLock.class);

    private final JdbcClient db;
    private final DataSource dataSource;

    public ScheduledJobLock(JdbcClient db, PlatformTransactionManager txManager) {
        this(db, txManager, null);
    }

    @Autowired
    public ScheduledJobLock(
            JdbcClient db,
            PlatformTransactionManager txManager,
            @Autowired(required = false) DataSource dataSource
    ) {
        this.db = db;
        this.dataSource = dataSource;
    }

    /**
     * Attempts to acquire the cluster-wide session-level advisory lock for {@code jobName}
     * outside any database transaction and runs {@code task} only if this pod won leadership.
     * Returns {@code true} if executed.
     */
    public boolean runIfLeader(String jobName, Runnable task) {
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("jobName must not be blank");
        }
        String lockKey = "elepay:job:" + jobName;
        if (dataSource != null) {
            return runWithDedicatedConnection(jobName, lockKey, task);
        }
        return runWithJdbcClient(jobName, lockKey, task);
    }

    private boolean runWithDedicatedConnection(String jobName, String lockKey, Runnable task) {
        try (Connection conn = dataSource.getConnection()) {
            if (!conn.getAutoCommit()) {
                conn.setAutoCommit(true);
            }
            boolean acquired = trySessionLock(conn, lockKey);
            if (!acquired) {
                log.debug("Skipping scheduled job '{}' — another pod holds the advisory lock", jobName);
                return false;
            }
            try {
                task.run();
                return true;
            } finally {
                releaseSessionLock(conn, lockKey);
            }
        } catch (SQLException e) {
            log.error("Failed to acquire/release advisory lock for scheduled job '{}': {}", jobName, e.getMessage());
            return false;
        }
    }

    private boolean runWithJdbcClient(String jobName, String lockKey, Runnable task) {
        Boolean acquired = db.sql("SELECT pg_try_advisory_lock(hashtext(:job))")
                .param("job", lockKey)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("Skipping scheduled job '{}' — another pod holds the advisory lock", jobName);
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            try {
                db.sql("SELECT pg_advisory_unlock(hashtext(:job))")
                        .param("job", lockKey)
                        .query(Boolean.class)
                        .single();
            } catch (RuntimeException e) {
                log.debug("Advisory unlock for '{}' returned error: {}", jobName, e.getMessage());
            }
        }
    }

    private static boolean trySessionLock(Connection conn, String lockKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
            ps.setString(1, lockKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void releaseSessionLock(Connection conn, String lockKey) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
            ps.setString(1, lockKey);
            ps.executeQuery().close();
        } catch (SQLException e) {
            log.debug("Failed to unlock advisory lock '{}': {}", lockKey, e.getMessage());
        }
    }

    /**
     * Acquires the transaction-scoped advisory lock inside an already-active {@code @Transactional}
     * method. Returns {@code false} if another pod is currently executing {@code jobName}.
     */
    public boolean tryLockInCurrentTx(String jobName) {
        Boolean acquired = db.sql("SELECT pg_try_advisory_xact_lock(hashtext(:job))")
                .param("job", "elepay:job:" + jobName)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(acquired);
    }
}
