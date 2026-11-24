package com.elepay.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL transaction-scoped advisory lock for {@code @Scheduled} financial jobs.
 * Ensures only one backend instance runs a scheduled settlement, payout, recovery,
 * audit checkpoint, or key-rotation check at a time in multi-pod production clusters.
 * Because {@code pg_try_advisory_xact_lock} is transaction-scoped, a crashed pod
 * automatically releases the lock without ever leaving stale locks behind.
 */
@Service
public class ScheduledJobLock {
    private static final Logger log = LoggerFactory.getLogger(ScheduledJobLock.class);

    private final JdbcClient db;
    private final TransactionTemplate tx;

    public ScheduledJobLock(JdbcClient db, PlatformTransactionManager txManager) {
        this.db = db;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Attempts to acquire the cluster-wide lock for {@code jobName} within a transaction
     * and runs {@code task} only if this pod won leadership. Returns {@code true} if executed.
     */
    public boolean runIfLeader(String jobName, Runnable task) {
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("jobName must not be blank");
        }
        return Boolean.TRUE.equals(tx.execute(status -> {
            Boolean acquired = db.sql("SELECT pg_try_advisory_xact_lock(hashtext(:job))")
                    .param("job", "elepay:job:" + jobName)
                    .query(Boolean.class)
                    .single();
            if (!Boolean.TRUE.equals(acquired)) {
                log.debug("Skipping scheduled job '{}' — another pod holds the advisory lock", jobName);
                return false;
            }
            task.run();
            return true;
        }));
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
