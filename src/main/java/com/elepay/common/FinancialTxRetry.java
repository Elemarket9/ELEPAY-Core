package com.elepay.common;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transaction-boundary retry helper for PostgreSQL transient concurrency failures
 * ({@code SQLSTATE 40P01} deadlock detected and {@code SQLSTATE 40001} serialization failure).
 *
 * <p>PostgreSQL aborts the entire active transaction on {@code 40P01}/{@code 40001}; any retry
 * inside the same transaction fails with {@code SQLSTATE 25P02}. This helper therefore runs each
 * attempt in a fresh {@code PROPAGATION_REQUIRES_NEW} transaction after the previous transaction
 * has completely rolled back, applying bounded exponential backoff with full jitter.
 *
 * <p>Callers must NEVER perform non-idempotent external network calls (e.g. payment rail HTTP
 * submissions) inside {@link #execute(Supplier)}; submit external rail calls only after the
 * database transaction commits.
 */
@Component
public class FinancialTxRetry {
    private static final Logger log = LoggerFactory.getLogger(FinancialTxRetry.class);

    public static final int DEFAULT_MAX_ATTEMPTS = 4;
    private static final long BASE_DELAY_MS = 15L;
    private static final long MAX_DELAY_MS = 250L;

    private final TransactionTemplate requiresNewTx;

    public FinancialTxRetry(PlatformTransactionManager transactionManager) {
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T execute(Supplier<T> work) {
        return execute(DEFAULT_MAX_ATTEMPTS, work);
    }

    public <T> T execute(int maxAttempts, Supplier<T> work) {
        int attempts = Math.max(1, maxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return requiresNewTx.execute(status -> work.get());
            } catch (RuntimeException ex) {
                if (attempt < attempts && isTransientConcurrencyFailure(ex)) {
                    long backoffMs = computeJitteredBackoffMs(attempt);
                    log.warn("Transient PostgreSQL concurrency failure on attempt {}/{}; rolling back and retrying in {}ms",
                            attempt, attempts, backoffMs);
                    if (sleepQuietly(backoffMs)) {
                        continue;
                    }
                }
                if (isTransientConcurrencyFailure(ex)) {
                    throw ApiException.conflict("lock_contention",
                            "Concurrent update conflict after " + attempts + " attempts; retry with the same idempotency key");
                }
                throw ex;
            }
        }
        throw ApiException.conflict("lock_contention", "Concurrent update conflict; retry with the same idempotency key");
    }

    public static boolean isTransientConcurrencyFailure(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof ConcurrencyFailureException) {
                return true;
            }
            if (cur instanceof SQLException sqlEx) {
                String state = sqlEx.getSQLState();
                if ("40P01".equals(state) || "40001".equals(state)) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static long computeJitteredBackoffMs(int attempt) {
        long ceiling = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * (1L << Math.min(attempt - 1, 6)));
        return ThreadLocalRandom.current().nextLong(1L, Math.max(2L, ceiling + 1L));
    }

    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return !Thread.currentThread().isInterrupted();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
