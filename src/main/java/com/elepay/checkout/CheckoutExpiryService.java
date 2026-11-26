package com.elepay.checkout;

import com.elepay.common.ScheduledJobLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodic checkout background maintenance: settles due allocations and expires stale open
 * checkouts under a transaction-scoped advisory lock.
 */
@Service
class CheckoutExpiryService {
    private final JdbcClient db;
    private final CheckoutReserveService reserves;
    private final boolean jobsEnabled;
    private ScheduledJobLock jobLock;

    @Autowired(required = false)
    void setJobLock(ScheduledJobLock jobLock) {
        this.jobLock = jobLock;
    }

    CheckoutExpiryService(JdbcClient db, CheckoutReserveService reserves,
                          @Value("${elepay.checkout.jobs-enabled:true}") boolean jobsEnabled) {
        this.db = db;
        this.reserves = reserves;
        this.jobsEnabled = jobsEnabled;
    }

    @Scheduled(fixedDelayString = "${elepay.checkout.job-ms:60000}")
    @Transactional
    void jobs() {
        if (!jobsEnabled) return;
        if (jobLock != null && !jobLock.tryLockInCurrentTx("checkout-jobs")) return;
        reserves.settleDue(false, null, null);
        db.sql("""
                UPDATE checkout SET status = 'expired'
                WHERE id IN (
                    SELECT id FROM checkout
                    WHERE status = 'open' AND expires_at < now()
                    ORDER BY expires_at ASC
                    LIMIT 1000
                    FOR UPDATE SKIP LOCKED
                )""").update();
    }
}
