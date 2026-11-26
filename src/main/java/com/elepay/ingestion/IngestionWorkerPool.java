package com.elepay.ingestion;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bounded Java 25 virtual-thread worker pool that claims pending rows in
 * {@code ingestion_event} via {@code FOR UPDATE SKIP LOCKED}, dispatches them
 * to registered {@link IngestionHandler}s, applies jittered exponential backoff
 * on transient errors, transitions exhausted rows to {@code DEAD}, recovers
 * expired leases, and drains in-flight work cleanly on shutdown.
 */
@Service
public class IngestionWorkerPool implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorkerPool.class);

    /** Hard ceiling strictly below HikariCP max pool size (50) so workers cannot starve HTTP traffic. */
    static final int MAX_SAFE_CONCURRENCY = 40;
    private static final int MAX_ERROR_LENGTH = 2000;
    private static final int MAX_DRAIN_ITERATIONS = 1000;

    private final JdbcClient db;
    private final TransactionTemplate claimTx;
    private final TransactionTemplate updateTx;
    private final Map<String, IngestionHandler> handlers = new ConcurrentHashMap<>();
    private final IngestionMetrics metrics;

    private final boolean enabled;
    private final int maxConcurrency;
    private final int claimBatchSize;
    private final long pollIntervalMs;
    private final int leaseSeconds;
    private final long staleRecoveryIntervalMs;
    private final long baseBackoffMs;
    private final long maxBackoffMs;
    private final long drainTimeoutMs;
    private final String workerId;

    private final Semaphore concurrencySemaphore;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger inFlight = new AtomicInteger(0);

    private volatile ExecutorService virtualExecutor;
    private volatile Thread pollerThread;
    private volatile Thread reaperThread;

    @Autowired
    public IngestionWorkerPool(
            JdbcClient db,
            PlatformTransactionManager transactionManager,
            List<IngestionHandler> handlers,
            IngestionMetrics metrics,
            @Value("${elepay.ingestion.workers.enabled:true}") boolean enabled,
            @Value("${elepay.ingestion.workers.max-concurrency:16}") int maxConcurrency,
            @Value("${elepay.ingestion.workers.claim-batch-size:32}") int claimBatchSize,
            @Value("${elepay.ingestion.workers.poll-ms:50}") long pollIntervalMs,
            @Value("${elepay.ingestion.workers.lease-seconds:60}") int leaseSeconds,
            @Value("${elepay.ingestion.workers.stale-recovery-ms:15000}") long staleRecoveryIntervalMs,
            @Value("${elepay.ingestion.workers.base-backoff-ms:1000}") long baseBackoffMs,
            @Value("${elepay.ingestion.workers.max-backoff-ms:300000}") long maxBackoffMs,
            @Value("${elepay.ingestion.workers.drain-timeout-ms:10000}") long drainTimeoutMs) {
        this.db = db;
        this.claimTx = new TransactionTemplate(transactionManager);
        this.updateTx = new TransactionTemplate(transactionManager);
        this.updateTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        if (handlers != null) {
            for (IngestionHandler handler : handlers) {
                if (handler != null && handler.eventType() != null) {
                    this.handlers.put(handler.eventType(), handler);
                }
            }
        }
        this.metrics = metrics;
        this.enabled = enabled;
        this.maxConcurrency = clampConcurrency(maxConcurrency);
        this.claimBatchSize = Math.max(1, claimBatchSize);
        this.pollIntervalMs = Math.max(5L, pollIntervalMs);
        this.leaseSeconds = Math.max(5, leaseSeconds);
        this.staleRecoveryIntervalMs = Math.max(500L, staleRecoveryIntervalMs);
        this.baseBackoffMs = Math.max(10L, baseBackoffMs);
        this.maxBackoffMs = Math.max(this.baseBackoffMs, maxBackoffMs);
        this.drainTimeoutMs = Math.max(500L, drainTimeoutMs);
        this.workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        this.concurrencySemaphore = new Semaphore(this.maxConcurrency);
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    static int clampConcurrency(int requested) {
        return Math.max(1, Math.min(requested, MAX_SAFE_CONCURRENCY));
    }

    /** Registers or replaces a handler dynamically (useful for tests). */
    public void registerHandler(IngestionHandler handler) {
        if (handler == null || handler.eventType() == null || handler.eventType().isBlank()) {
            throw new IllegalArgumentException("IngestionHandler and eventType must not be null or blank");
        }
        handlers.put(handler.eventType(), handler);
    }

    /** Removes a dynamically registered handler (useful for test cleanup). */
    public void unregisterHandler(String eventType) {
        if (eventType != null) {
            handlers.remove(eventType);
        }
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }

    public int inFlightCount() {
        return inFlight.get();
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (virtualExecutor == null || virtualExecutor.isShutdown()) {
            virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        }
        if (!enabled) {
            log.info("IngestionWorkerPool started in passive mode (elepay.ingestion.workers.enabled=false)");
            return;
        }
        pollerThread = Thread.ofVirtual()
                .name("ingestion-poller-" + workerId)
                .start(this::pollLoop);
        reaperThread = Thread.ofVirtual()
                .name("ingestion-lease-reaper-" + workerId)
                .start(this::staleLeaseLoop);
        log.info("IngestionWorkerPool {} started (maxConcurrency={}, claimBatchSize={}, pollMs={})",
                workerId, maxConcurrency, claimBatchSize, pollIntervalMs);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping IngestionWorkerPool {} (inFlight={})", workerId, inFlight.get());
        interruptAndJoin(pollerThread);
        interruptAndJoin(reaperThread);
        ExecutorService exec = virtualExecutor;
        if (exec != null) {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(drainTimeoutMs, TimeUnit.MILLISECONDS)) {
                    log.warn("IngestionWorkerPool {} drain timed out after {}ms with {} tasks remaining",
                            workerId, drainTimeoutMs, inFlight.get());
                    exec.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exec.shutdownNow();
            }
        }
        log.info("IngestionWorkerPool {} stopped", workerId);
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                List<Future<?>> dispatched = pollAndDispatchOnce();
                if (dispatched.isEmpty()) {
                    Thread.sleep(pollIntervalMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.warn("IngestionWorkerPool {} poll cycle failed: {}", workerId, e.toString());
                try {
                    Thread.sleep(Math.min(1000L, pollIntervalMs * 4));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void staleLeaseLoop() {
        while (running.get()) {
            try {
                Thread.sleep(staleRecoveryIntervalMs);
                if (!running.get()) {
                    break;
                }
                int recovered = recoverStaleLeases();
                if (recovered > 0) {
                    log.warn("IngestionWorkerPool {} recovered {} stale leases", workerId, recovered);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.warn("IngestionWorkerPool {} stale-lease recovery failed: {}", workerId, e.toString());
            }
        }
    }

    /**
     * Deterministically claims and processes all currently due {@code PENDING} rows
     * and awaits completion of in-flight tasks. Intended for tests and manual drains.
     *
     * @return total number of claimed items processed during this drain
     */
    public int drainNow() {
        ensureExecutorReady();
        int totalProcessed = 0;
        for (int iter = 0; iter < MAX_DRAIN_ITERATIONS; iter++) {
            List<Future<?>> futures = pollAndDispatchOnce();
            if (futures.isEmpty()) {
                waitForInFlight();
                // Re-check once after in-flight tasks finish in case a background worker just finished.
                futures = pollAndDispatchOnce();
                if (futures.isEmpty()) {
                    break;
                }
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return totalProcessed;
                } catch (ExecutionException e) {
                    log.warn("Unexpected error awaiting ingestion task: {}", e.getCause().toString());
                }
            }
            totalProcessed += futures.size();
        }
        waitForInFlight();
        return totalProcessed;
    }

    /**
     * Resets {@code PROCESSING} rows whose lease has expired back to {@code PENDING}
     * (or {@code DEAD} if attempts have reached {@code max_attempts}).
     */
    public int recoverStaleLeases() {
        Integer recovered = updateTx.execute(status -> {
            int requeued = db.sql("""
                    UPDATE ingestion_event
                    SET status = 'PENDING',
                        next_attempt_at = now(),
                        locked_by = NULL,
                        locked_at = NULL,
                        lease_expires_at = NULL,
                        last_error = COALESCE(last_error, 'worker_lease_expired'),
                        updated_at = now()
                    WHERE status = 'PROCESSING'
                      AND lease_expires_at < now()
                      AND attempts < max_attempts""")
                    .update();
            int dead = db.sql("""
                    UPDATE ingestion_event
                    SET status = 'DEAD',
                        locked_by = NULL,
                        locked_at = NULL,
                        lease_expires_at = NULL,
                        last_error = COALESCE(last_error, 'worker_lease_expired_max_attempts'),
                        updated_at = now()
                    WHERE status = 'PROCESSING'
                      AND lease_expires_at < now()
                      AND attempts >= max_attempts""")
                    .update();
            for (int i = 0; i < requeued; i++) {
                metrics.recordRetried();
            }
            for (int i = 0; i < dead; i++) {
                metrics.recordDead();
            }
            return requeued + dead;
        });
        return recovered == null ? 0 : recovered;
    }

    private List<Future<?>> pollAndDispatchOnce() {
        int permitsToClaim = acquireAvailablePermits(claimBatchSize);
        if (permitsToClaim == 0) {
            return List.of();
        }

        List<ClaimedItem> claimed;
        try {
            claimed = claimBatch(permitsToClaim);
        } catch (RuntimeException e) {
            concurrencySemaphore.release(permitsToClaim);
            throw e;
        }

        int unusedPermits = permitsToClaim - claimed.size();
        if (unusedPermits > 0) {
            concurrencySemaphore.release(unusedPermits);
        }
        if (claimed.isEmpty()) {
            return List.of();
        }

        ExecutorService exec = ensureExecutorReady();
        List<Future<?>> futures = new ArrayList<>(claimed.size());
        for (int i = 0; i < claimed.size(); i++) {
            ClaimedItem item = claimed.get(i);
            try {
                inFlight.incrementAndGet();
                futures.add(exec.submit(() -> {
                    try {
                        processItem(item);
                    } finally {
                        inFlight.decrementAndGet();
                        concurrencySemaphore.release();
                    }
                }));
            } catch (RejectedExecutionException rejected) {
                inFlight.decrementAndGet();
                concurrencySemaphore.release(claimed.size() - i);
                break;
            }
        }
        return futures;
    }

    private int acquireAvailablePermits(int maxWanted) {
        int acquired = 0;
        while (acquired < maxWanted && concurrencySemaphore.tryAcquire()) {
            acquired++;
        }
        return acquired;
    }

    private List<ClaimedItem> claimBatch(int batchLimit) {
        List<ClaimedItem> result = claimTx.execute(status -> {
            List<ClaimedItem> rows = db.sql("""
                    SELECT id, tenant_id, event_type, scope, idempotency_key,
                           payload::text AS payload_json, attempts, max_attempts
                    FROM ingestion_event
                    WHERE status = 'PENDING' AND next_attempt_at <= now()
                    ORDER BY next_attempt_at, created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batch""")
                    .param("batch", batchLimit)
                    .query((rs, rowNum) -> {
                        int nextAttempt = rs.getInt("attempts") + 1;
                        int maxAttempts = rs.getInt("max_attempts");
                        IngestionRecord rec = new IngestionRecord(
                                rs.getObject("id", UUID.class),
                                rs.getObject("tenant_id", UUID.class),
                                rs.getString("event_type"),
                                rs.getString("scope"),
                                rs.getString("idempotency_key"),
                                rs.getString("payload_json"),
                                nextAttempt);
                        return new ClaimedItem(rec, nextAttempt, maxAttempts);
                    })
                    .list();

            for (ClaimedItem item : rows) {
                db.sql("""
                        UPDATE ingestion_event
                        SET status = 'PROCESSING',
                            locked_by = :workerId,
                            locked_at = now(),
                            lease_expires_at = now() + make_interval(secs => :leaseSeconds),
                            attempts = attempts + 1,
                            updated_at = now()
                        WHERE id = :id""")
                        .param("workerId", workerId)
                        .param("leaseSeconds", leaseSeconds)
                        .param("id", item.record().id())
                        .update();
            }
            return rows;
        });
        return result == null ? List.of() : result;
    }

    private void processItem(ClaimedItem item) {
        IngestionRecord rec = item.record();
        IngestionHandler handler = handlers.get(rec.eventType());
        if (handler == null) {
            String message = "No IngestionHandler registered for event_type=" + rec.eventType();
            log.error("Marking ingestion {} DEAD: {}", rec.id(), message);
            markDead(rec.id(), message);
            metrics.recordDead();
            return;
        }

        long startNanos = System.nanoTime();
        try {
            String resultJson = handler.handle(rec);
            markCompleted(rec.id(), resultJson);
            metrics.recordCompleted(Duration.ofNanos(System.nanoTime() - startNanos));
        } catch (Exception e) {
            String errorText = truncateError(e);
            if (item.attempts() < item.maxAttempts()) {
                long delayMs = computeBackoffMs(item.attempts());
                double delaySeconds = Math.max(0.05d, delayMs / 1000.0d);
                log.warn("Ingestion {} (type={}, attempt={}/{}) failed, retrying in {}ms: {}",
                        rec.id(), rec.eventType(), item.attempts(), item.maxAttempts(), delayMs, errorText);
                scheduleRetry(rec.id(), delaySeconds, errorText);
                metrics.recordRetried();
            } else {
                log.error("Ingestion {} (type={}, attempt={}/{}) exhausted retries, moving to DEAD: {}",
                        rec.id(), rec.eventType(), item.attempts(), item.maxAttempts(), errorText);
                markDead(rec.id(), errorText);
                metrics.recordDead();
            }
        }
    }

    private void markCompleted(UUID id, String resultJson) {
        updateTx.executeWithoutResult(status -> {
            if (resultJson == null) {
                db.sql("""
                        UPDATE ingestion_event
                        SET status = 'COMPLETED',
                            result = NULL,
                            last_error = NULL,
                            completed_at = now(),
                            locked_by = NULL,
                            locked_at = NULL,
                            lease_expires_at = NULL,
                            updated_at = now()
                        WHERE id = :id""")
                        .param("id", id)
                        .update();
            } else {
                db.sql("""
                        UPDATE ingestion_event
                        SET status = 'COMPLETED',
                            result = CAST(:res AS jsonb),
                            last_error = NULL,
                            completed_at = now(),
                            locked_by = NULL,
                            locked_at = NULL,
                            lease_expires_at = NULL,
                            updated_at = now()
                        WHERE id = :id""")
                        .param("res", resultJson)
                        .param("id", id)
                        .update();
            }
        });
    }

    private void scheduleRetry(UUID id, double delaySeconds, String errorText) {
        updateTx.executeWithoutResult(status ->
                db.sql("""
                        UPDATE ingestion_event
                        SET status = 'PENDING',
                            next_attempt_at = now() + make_interval(secs => :delaySecs),
                            last_error = :err,
                            locked_by = NULL,
                            locked_at = NULL,
                            lease_expires_at = NULL,
                            updated_at = now()
                        WHERE id = :id""")
                        .param("delaySecs", delaySeconds)
                        .param("err", errorText)
                        .param("id", id)
                        .update());
    }

    private void markDead(UUID id, String errorText) {
        updateTx.executeWithoutResult(status ->
                db.sql("""
                        UPDATE ingestion_event
                        SET status = 'DEAD',
                            last_error = :err,
                            locked_by = NULL,
                            locked_at = NULL,
                            lease_expires_at = NULL,
                            updated_at = now()
                        WHERE id = :id""")
                        .param("err", errorText)
                        .param("id", id)
                        .update());
    }

    /** Equal-jitter exponential backoff bounded by {@code [baseBackoffMs/2, maxBackoffMs]}. */
    long computeBackoffMs(int attempt) {
        int shift = Math.max(0, Math.min(attempt - 1, 20));
        long exponential;
        try {
            exponential = Math.multiplyExact(baseBackoffMs, 1L << shift);
        } catch (ArithmeticException overflow) {
            exponential = maxBackoffMs;
        }
        long capped = Math.min(maxBackoffMs, Math.max(baseBackoffMs, exponential));
        long half = Math.max(1L, capped / 2L);
        long jitter = ThreadLocalRandom.current().nextLong(half + 1L);
        return Math.min(maxBackoffMs, half + jitter);
    }

    static String truncateError(Throwable error) {
        if (error == null) {
            return "unknown_error";
        }
        String raw = error.getClass().getSimpleName()
                + (error.getMessage() != null ? ": " + error.getMessage() : "");
        return raw.length() <= MAX_ERROR_LENGTH ? raw : raw.substring(0, MAX_ERROR_LENGTH);
    }

    private ExecutorService ensureExecutorReady() {
        ExecutorService exec = virtualExecutor;
        if (exec == null || exec.isShutdown()) {
            synchronized (this) {
                exec = virtualExecutor;
                if (exec == null || exec.isShutdown()) {
                    exec = Executors.newVirtualThreadPerTaskExecutor();
                    virtualExecutor = exec;
                }
            }
        }
        return exec;
    }

    private void waitForInFlight() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainTimeoutMs);
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static void interruptAndJoin(Thread thread) {
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ClaimedItem(IngestionRecord record, int attempts, int maxAttempts) {}
}
