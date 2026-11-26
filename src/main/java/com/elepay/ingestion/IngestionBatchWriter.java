package com.elepay.ingestion;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Crash-safe group-commit micro-batch writer for {@code ingestion_event}.
 *
 * <p>Callers submit commands into a bounded {@link ArrayBlockingQueue}; a dedicated virtual
 * thread drains micro-batches up to {@code batchSize} and executes a single JDBC batch
 * {@code INSERT ... ON CONFLICT (scope, idempotency_key) DO NOTHING} inside a transaction.
 * Caller futures are completed ONLY after the database transaction commits.
 */
@Service
public class IngestionBatchWriter implements IngestionQueue, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IngestionBatchWriter.class);

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private static final String INSERT_SQL = """
            INSERT INTO ingestion_event (
                id, tenant_id, event_type, scope, idempotency_key, payload_hash, payload, max_attempts,
                ordering_key, broker_provider, broker_message_id
            ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
            ON CONFLICT (scope, idempotency_key) DO NOTHING
            """;

    private static final String SELECT_EXISTING_SQL = """
            SELECT id, status, payload_hash
            FROM ingestion_event
            WHERE scope = ? AND idempotency_key = ?
            """;

    private static final String SELECT_STATUS_SQL = """
            SELECT id, tenant_id, event_type, status, attempts, result::text AS result_json,
                   last_error, created_at, completed_at
            FROM ingestion_event
            WHERE id = ?
            """;

    private static final String SELECT_STATUS_TENANT_SQL = """
            SELECT id, tenant_id, event_type, status, attempts, result::text AS result_json,
                   last_error, created_at, completed_at
            FROM ingestion_event
            WHERE id = ? AND tenant_id = ?
            """;

    private static final String SELECT_DEAD_SQL = """
            SELECT id, tenant_id, event_type, status, attempts, result::text AS result_json,
                   last_error, created_at, completed_at
            FROM ingestion_event
            WHERE status = 'DEAD'
            ORDER BY updated_at DESC
            LIMIT ?
            """;

    private static final String REPLAY_UPDATE_SQL = """
            UPDATE ingestion_event
            SET status = 'PENDING',
                attempts = 0,
                next_attempt_at = now(),
                locked_by = NULL,
                locked_at = NULL,
                lease_expires_at = NULL,
                last_error = NULL,
                completed_at = NULL,
                updated_at = now()
            WHERE id = ? AND status IN ('DEAD', 'FAILED')
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final IngestionProperties properties;
    private final IngestionMetrics metrics;
    private final ArrayBlockingQueue<PendingEntry> queue;
    private final Object admissionLock = new Object();
    private final int capacity;
    private final int highWatermark;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile Thread flusherThread;

    private final Counter duplicateCounter;
    private final Counter conflictCounter;
    private final Counter batchCounter;
    private final DistributionSummary batchSizeSummary;

    public IngestionBatchWriter(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            IngestionProperties properties,
            @Autowired(required = false) IngestionMetrics metrics,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.metrics = metrics;
        this.capacity = properties.getQueueCapacity();
        this.highWatermark = properties.highWatermark();
        this.queue = new ArrayBlockingQueue<>(this.capacity);

        if (meterRegistry != null) {
            this.duplicateCounter = Counter.builder("elepay.ingestion.duplicates")
                    .description("Total idempotent duplicate ingestion events").register(meterRegistry);
            this.conflictCounter = Counter.builder("elepay.ingestion.conflicts")
                    .description("Total idempotency payload hash conflicts").register(meterRegistry);
            this.batchCounter = Counter.builder("elepay.ingestion.batches")
                    .description("Total micro-batches committed to PostgreSQL").register(meterRegistry);
            this.batchSizeSummary = DistributionSummary.builder("elepay.ingestion.batch.size")
                    .description("Number of entries per committed ingestion micro-batch").register(meterRegistry);
            Gauge.builder("elepay.ingestion.queue.depth", queue, ArrayBlockingQueue::size)
                    .description("Current buffered entries awaiting batch commit").register(meterRegistry);
        } else {
            this.duplicateCounter = null;
            this.conflictCounter = null;
            this.batchCounter = null;
            this.batchSizeSummary = null;
        }
    }

    @Override
    public IngestionReceipt enqueue(IngestionCommand cmd) {
        if (cmd == null) {
            throw ApiException.badRequest("command_required", "Ingestion command is required");
        }
        ensureRunningForEnqueue();
        String payloadHash = canonicalPayloadHash(cmd.payloadJson());
        PendingEntry entry = new PendingEntry(UUID.randomUUID(), cmd, payloadHash, new CompletableFuture<>());
        synchronized (admissionLock) {
            if (queue.size() >= highWatermark || !queue.offer(entry)) {
                recordBackpressure();
                throw backpressureException();
            }
        }
        return awaitReceipt(entry.future, properties.getEnqueueTimeoutMs());
    }

    @Override
    public List<IngestionReceipt> enqueueBatch(List<IngestionCommand> cmds) {
        if (cmds == null || cmds.isEmpty()) {
            return List.of();
        }
        ensureRunningForEnqueue();
        List<PendingEntry> entries = new ArrayList<>(cmds.size());
        for (IngestionCommand cmd : cmds) {
            if (cmd == null) {
                throw ApiException.badRequest("command_required", "Ingestion command in batch must not be null");
            }
            entries.add(new PendingEntry(
                    UUID.randomUUID(),
                    cmd,
                    canonicalPayloadHash(cmd.payloadJson()),
                    new CompletableFuture<>()));
        }
        synchronized (admissionLock) {
            int currentSize = queue.size();
            if (entries.size() > capacity || (currentSize == 0 && entries.size() >= highWatermark)) {
                recordBackpressure();
                throw backpressureException();
            }
            long waitDeadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(250L, properties.getEnqueueTimeoutMs()));
            while ((queue.size() >= highWatermark || queue.size() + entries.size() > capacity)
                    && System.nanoTime() < waitDeadlineNs) {
                try {
                    admissionLock.wait(2L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            currentSize = queue.size();
            if (currentSize >= highWatermark || currentSize + entries.size() > capacity) {
                recordBackpressure();
                throw backpressureException();
            }
            List<PendingEntry> admitted = new ArrayList<>(entries.size());
            for (PendingEntry entry : entries) {
                if (!queue.offer(entry)) {
                    for (PendingEntry rollback : admitted) {
                        queue.remove(rollback);
                    }
                    recordBackpressure();
                    throw backpressureException();
                }
                admitted.add(entry);
            }
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getEnqueueTimeoutMs());
        List<IngestionReceipt> receipts = new ArrayList<>(entries.size());
        for (PendingEntry entry : entries) {
            long remainingMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
            receipts.add(awaitReceipt(entry.future, remainingMs));
        }
        return receipts;
    }

    @Override
    public Optional<IngestionStatus> findStatus(UUID id, UUID tenantId) {
        if (id == null) {
            return Optional.empty();
        }
        List<IngestionStatus> rows = tenantId != null
                ? jdbc.query(SELECT_STATUS_TENANT_SQL, IngestionBatchWriter::mapStatus, id, tenantId)
                : jdbc.query(SELECT_STATUS_SQL, IngestionBatchWriter::mapStatus, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public IngestionStatus replay(UUID id) {
        if (id == null) {
            throw ApiException.badRequest("ingestion_id_required", "Ingestion ID is required");
        }
        return tx.execute(status -> {
            List<IngestionStatus> existing = jdbc.query(SELECT_STATUS_SQL, IngestionBatchWriter::mapStatus, id);
            if (existing.isEmpty()) {
                throw ApiException.notFound("ingestion_not_found", "Ingestion event not found: " + id);
            }
            IngestionStatus current = existing.getFirst();
            if (!"DEAD".equals(current.status()) && !"FAILED".equals(current.status())) {
                throw ApiException.conflict(
                        "ingestion_not_replayable",
                        "Only DEAD or FAILED ingestion events can be replayed; current status: " + current.status());
            }
            int updated = jdbc.update(REPLAY_UPDATE_SQL, id);
            if (updated == 0) {
                throw ApiException.conflict(
                        "ingestion_not_replayable",
                        "Ingestion event state changed concurrently and could not be replayed: " + id);
            }
            return jdbc.query(SELECT_STATUS_SQL, IngestionBatchWriter::mapStatus, id).getFirst();
        });
    }

    @Override
    public List<IngestionStatus> listDead(int limit) {
        int safeLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        return jdbc.query(SELECT_DEAD_SQL, IngestionBatchWriter::mapStatus, safeLimit);
    }

    public int queueDepth() {
        return queue.size();
    }

    public int queueCapacity() {
        return capacity;
    }

    public boolean isHighWatermark() {
        return queue.size() >= highWatermark;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            stopped.set(false);
            flusherThread = Thread.ofVirtual()
                    .name("ingestion-batch-writer")
                    .start(this::flushLoop);
            log.info("Started IngestionBatchWriter (capacity={}, highWatermark={}, batchSize={}, flushIntervalMs={})",
                    capacity, highWatermark, properties.getBatchSize(), properties.getFlushIntervalMs());
        }
    }

    @Override
    public void stop() {
        stopped.set(true);
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread thread = flusherThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(properties.getShutdownDrainTimeoutMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Stopped IngestionBatchWriter (remainingQueued={})", queue.size());
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private void ensureRunningForEnqueue() {
        if (stopped.get()) {
            recordBackpressure();
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ingestion_backpressure",
                    "Ingestion writer is shutting down; retry shortly");
        }
        if (!running.get()) {
            start();
        }
    }

    private void flushLoop() {
        int batchSize = properties.getBatchSize();
        long flushIntervalMs = properties.getFlushIntervalMs();
        List<PendingEntry> batch = new ArrayList<>(batchSize);

        while (running.get() || !queue.isEmpty()) {
            try {
                batch.clear();
                PendingEntry first = running.get()
                        ? queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS)
                        : queue.poll();
                if (first == null) {
                    continue;
                }
                batch.add(first);
                if (batchSize > 1) {
                    queue.drainTo(batch, batchSize - 1);
                }
                synchronized (admissionLock) {
                    admissionLock.notifyAll();
                }
                // Clear any shutdown interrupt flag before executing the JDBC drain commit.
                Thread.interrupted();
                flushBatch(batch);
            } catch (InterruptedException e) {
                if (!running.get()) {
                    // Clear interrupt flag and continue loop to drain any remaining buffered entries.
                    Thread.interrupted();
                }
            } catch (Throwable t) {
                log.error("Unexpected error in IngestionBatchWriter flush loop", t);
                for (PendingEntry entry : batch) {
                    entry.future.completeExceptionally(t);
                }
            }
        }
    }

    private void flushBatch(List<PendingEntry> batch) {
        if (batch.isEmpty()) {
            return;
        }
        // Group intra-batch entries sharing the same (scope, idempotencyKey) so a single
        // JDBC INSERT represents each unique key while every caller gets an exact outcome.
        Map<ScopeKey, List<PendingEntry>> grouped = new LinkedHashMap<>();
        for (PendingEntry entry : batch) {
            ScopeKey key = new ScopeKey(entry.cmd.scope(), entry.cmd.idempotencyKey());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>(1)).add(entry);
        }
        List<PendingEntry> uniqueCandidates = new ArrayList<>(grouped.size());
        for (List<PendingEntry> entries : grouped.values()) {
            uniqueCandidates.add(entries.getFirst());
        }

        List<EntryOutcome> outcomes;
        try {
            outcomes = tx.execute(status -> commitBatchInTx(uniqueCandidates, grouped));
        } catch (Throwable t) {
            log.warn("Ingestion micro-batch commit failed (size={}): {}", batch.size(), t.getMessage());
            for (PendingEntry entry : batch) {
                entry.future.completeExceptionally(t);
            }
            return;
        }

        increment(batchCounter);
        if (batchSizeSummary != null) {
            batchSizeSummary.record(batch.size());
        }

        // Complete caller futures ONLY AFTER transaction commit has succeeded.
        if (outcomes != null) {
            for (EntryOutcome outcome : outcomes) {
                if (outcome.error != null) {
                    outcome.entry.future.completeExceptionally(outcome.error);
                } else {
                    outcome.entry.future.complete(outcome.receipt);
                }
            }
        }
    }

    private List<EntryOutcome> commitBatchInTx(
            List<PendingEntry> uniqueCandidates,
            Map<ScopeKey, List<PendingEntry>> grouped) {
        int maxAttempts = properties.getMaxAttempts();
        int[] counts = jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                PendingEntry entry = uniqueCandidates.get(i);
                ps.setObject(1, entry.id);
                if (entry.cmd.tenantId() != null) {
                    ps.setObject(2, entry.cmd.tenantId());
                } else {
                    ps.setNull(2, Types.OTHER);
                }
                ps.setString(3, entry.cmd.eventType());
                ps.setString(4, entry.cmd.scope());
                ps.setString(5, entry.cmd.idempotencyKey());
                ps.setString(6, entry.payloadHash);
                ps.setString(7, entry.cmd.payloadJson());
                ps.setInt(8, maxAttempts);
                ps.setString(9, entry.cmd.orderingKey());
                ps.setString(10, entry.cmd.brokerProvider());
                ps.setString(11, entry.cmd.brokerMessageId());
            }

            @Override
            public int getBatchSize() {
                return uniqueCandidates.size();
            }
        });

        List<EntryOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < uniqueCandidates.size(); i++) {
            PendingEntry candidate = uniqueCandidates.get(i);
            ScopeKey key = new ScopeKey(candidate.cmd.scope(), candidate.cmd.idempotencyKey());
            List<PendingEntry> entriesForKey = grouped.get(key);
            int affected = i < counts.length ? counts[i] : 0;

            boolean newlyInserted;
            UUID persistedId;
            String persistedStatus;
            String persistedHash;

            if (affected == 1) {
                newlyInserted = true;
                persistedId = candidate.id;
                persistedStatus = "PENDING";
                persistedHash = candidate.payloadHash;
            } else if (affected == 0 || affected == Statement.SUCCESS_NO_INFO) {
                ExistingRow existing = jdbc.query(
                                SELECT_EXISTING_SQL,
                                (rs, rowNum) -> new ExistingRow(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("status"),
                                        rs.getString("payload_hash")),
                                key.scope,
                                key.idempotencyKey)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> ApiException.internal(
                                "ingestion_state_error",
                                "Ingestion row missing after conflict for key " + key.idempotencyKey));
                persistedId = existing.id;
                persistedStatus = existing.status;
                persistedHash = existing.payloadHash;
                newlyInserted = persistedId.equals(candidate.id);
            } else {
                newlyInserted = true;
                persistedId = candidate.id;
                persistedStatus = "PENDING";
                persistedHash = candidate.payloadHash;
            }

            for (int j = 0; j < entriesForKey.size(); j++) {
                PendingEntry entry = entriesForKey.get(j);
                if (newlyInserted && j == 0) {
                    recordEnqueued();
                    outcomes.add(EntryOutcome.success(entry, new IngestionReceipt(persistedId, persistedStatus, false)));
                } else if (persistedHash.equals(entry.payloadHash)) {
                    increment(duplicateCounter);
                    outcomes.add(EntryOutcome.success(entry, new IngestionReceipt(persistedId, persistedStatus, true)));
                } else {
                    increment(conflictCounter);
                    outcomes.add(EntryOutcome.failure(entry, ApiException.idempotencyConflict()));
                }
            }
        }
        return outcomes;
    }

    private IngestionReceipt awaitReceipt(CompletableFuture<IngestionReceipt> future, long timeoutMs) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ingestion_backpressure",
                    "Interrupted while waiting for ingestion commit");
        } catch (TimeoutException e) {
            recordBackpressure();
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ingestion_backpressure",
                    "Timed out waiting for ingestion batch commit");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApiException apiException) {
                throw apiException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw ApiException.internal("ingestion_commit_failed",
                    cause != null ? cause.getMessage() : "Ingestion commit failed");
        }
    }

    static String canonicalPayloadHash(String payloadJson) {
        try {
            JsonNode tree = CANONICAL.readTree(payloadJson);
            byte[] canonicalBytes = CANONICAL.writeValueAsBytes(sort(tree));
            return Idempotency.sha256(new String(canonicalBytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (JacksonException e) {
            throw ApiException.badRequest("invalid_payload_json", "Ingestion payload must be valid JSON");
        }
    }

    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            var sorted = CANONICAL.createObjectNode();
            node.propertyNames().stream().sorted().forEach(name -> sorted.set(name, sort(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            var array = CANONICAL.createArrayNode();
            node.forEach(child -> array.add(sort(child)));
            return array;
        }
        return node;
    }

    private static IngestionStatus mapStatus(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID tenantId = rs.getObject("tenant_id", UUID.class);
        String eventType = rs.getString("event_type");
        String status = rs.getString("status");
        int attempts = rs.getInt("attempts");
        String resultJson = rs.getString("result_json");
        String lastError = rs.getString("last_error");
        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp completedTs = rs.getTimestamp("completed_at");
        Instant createdAt = createdTs != null ? createdTs.toInstant() : null;
        Instant completedAt = completedTs != null ? completedTs.toInstant() : null;
        return new IngestionStatus(id, tenantId, eventType, status, attempts, resultJson, lastError, createdAt, completedAt);
    }

    private static ApiException backpressureException() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "ingestion_backpressure",
                "Ingestion queue is saturated; retry shortly");
    }

    private void recordEnqueued() {
        if (metrics != null) {
            metrics.recordEnqueued();
        }
    }

    private void recordBackpressure() {
        if (metrics != null) {
            metrics.recordBackpressure();
        }
    }

    private static void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    private record ScopeKey(String scope, String idempotencyKey) {}

    private record ExistingRow(UUID id, String status, String payloadHash) {}

    private record PendingEntry(
            UUID id,
            IngestionCommand cmd,
            String payloadHash,
            CompletableFuture<IngestionReceipt> future) {}

    private record EntryOutcome(PendingEntry entry, IngestionReceipt receipt, Throwable error) {
        static EntryOutcome success(PendingEntry entry, IngestionReceipt receipt) {
            return new EntryOutcome(entry, receipt, null);
        }

        static EntryOutcome failure(PendingEntry entry, Throwable error) {
            return new EntryOutcome(entry, null, error);
        }
    }
}
